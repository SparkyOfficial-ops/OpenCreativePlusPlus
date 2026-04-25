package com.opencreativeplus.plugin.gui

import com.opencreativeplus.plugin.input.SignInputManager
import com.opencreativeplus.core.serialization.ParamSerializer
import com.opencreativeplus.api.registry.NodeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import java.util.UUID

/**
 * Inventory-based GUI for editing Action_Node block parameters.
 *
 * Each parameter is rendered as a PAPER item with its current value in the lore.
 * VARIABLE_REF params also get a BOOK "Choose Variable" item.
 * Slot 53 is always the EMERALD "Save" item.
 *
 * s: 1.1, 1.2, 1.3, 1.9
 */
class SmartGUI(
    private val player: Player,
    private val block: Block,
    private val nodeRegistry: NodeRegistry,
    private val signInputManager: SignInputManager,
    private val paramSerializer: ParamSerializer,
    private val scope: CoroutineScope,
    private val plotId: UUID,
    private val variableManager: com.opencreativeplus.core.execution.VariableManager,
    inventoryFactory: () -> Inventory = { Bukkit.createInventory(null, 54, GUI_TITLE) },
    private val itemFactory: (Material, String, List<String>) -> ItemStack = { mat, name, lore ->
        smartGuiMakeItem(mat, name, lore)
    },
    private val menuInventoryFactory: (String) -> Inventory = { title ->
        Bukkit.createInventory(null, 54, title)
    }
) : Listener {

    companion object {
        private const val GUI_TITLE = "Node Parameters"
        private const val SAVE_SLOT = 53

        fun defaultMakeItem(material: Material, name: String, lore: List<String>): ItemStack {
            val item = ItemStack(material)
            val meta: ItemMeta = item.itemMeta ?: return item
            meta.setDisplayName(name)
            meta.lore = lore
            item.itemMeta = meta
            return item
        }

        // Slot layout

        /**
         * Build a list of (displayName, loreValue) pairs for the given params map.
         * Pure function — no Bukkit Inventory needed — used by property tests.
         */
        fun buildParamItems(params: Map<String, Any>): List<Pair<String, String>> =
            params.map { (key, value) -> key to value.toString() }
    }

    private val inventory: Inventory = inventoryFactory()

    /**
     * Internal state: param name → (current value, ParamType).
     * Populated during renderParams and updated on edit/save.
     */
    internal val currentParams: MutableMap<String, Pair<Any, ParamType>> = mutableMapOf()

    /**
     * Slot → param name mapping for click dispatch.
     */
    private val slotToParam: MutableMap<Int, String> = mutableMapOf()

    /**
     * Slot → "choose variable" flag.
     */
    private val variableSlots: MutableSet<Int> = mutableSetOf()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Load current PDC params, render them as items, and open the inventory for the player.
     * s: 1.1, 1.2
     */
    fun open() {
        // Clear previous state
        slotToParam.clear()
        variableSlots.clear()
        for (i in 0 until 54) inventory.setItem(i, null)

        // Reload params from PDC
        val params = currentParams.toMap()
        renderParams(params.mapValues { it.value.first })

        // Save button
        val saveItem = makeItem(Material.EMERALD, "§aSave", listOf("§7Click to save all parameters"))
        inventory.setItem(SAVE_SLOT, saveItem)

        player.openInventory(inventory)
    }

    /**
     * Render [params] as items in the inventory.
     * Each param gets an Edit item (PAPER); VARIABLE_REF params also get a Choose Variable item (BOOK).
     * s: 1.2
     */
    internal fun renderParams(params: Map<String, Any>) {
        var slot = 0
        for ((name, value) in params) {
            if (slot >= SAVE_SLOT - 1) break  // leave room for save slot

            val paramType = currentParams[name]?.second ?: ParamType.STRING
            val editItem = makeItem(
                Material.PAPER,
                "§eEdit: $name",
                listOf("§7Current: §f$value", "§8[param:$name:${paramType.name}]")
            )
            inventory.setItem(slot, editItem)
            slotToParam[slot] = name
            slot++

            if (paramType == ParamType.VARIABLE_REF) {
                val varItem = makeItem(
                    Material.BOOK,
                    "§bChoose Variable: $name",
                    listOf("§7Click to pick a variable", "§8[var:$name]")
                )
                inventory.setItem(slot, varItem)
                variableSlots.add(slot)
                slotToParam[slot] = name
                slot++
            }
        }
    }

    /**
     * Handle an inventory click at [slot] by [player].
     * Dispatches to editParam, save, or VariableSuggestionMenu.
     * s: 1.3, 1.9
     */
    fun handleClick(slot: Int, player: Player) {
        when {
            slot == SAVE_SLOT -> save()
            slot in variableSlots -> {
                val paramName = slotToParam[slot] ?: return
                player.closeInventory()
                val menu = VariableSuggestionMenu(
                    player = player,
                    plotId = plotId,
                    variableManager = variableManager,
                    onSelect = { varName ->
                        currentParams[paramName] = varName to ParamType.VARIABLE_REF
                        paramSerializer.save(block, paramName, varName)
                        open()
                    },
                    inventoryFactory = menuInventoryFactory,
                    itemFactory = itemFactory
                )
                scope.launch { menu.open() }
            }
            slot in slotToParam -> {
                val paramName = slotToParam[slot] ?: return
                val paramType = currentParams[paramName]?.second ?: ParamType.STRING
                scope.launch { editParam(paramName, paramType) }
            }
        }
    }

    /**
     * Suspend: close inventory, await sign input, save, reopen.
     * s: 1.3, 1.4, 1.5, 1.6, 1.7, 1.8
     */
    suspend fun editParam(paramName: String, paramType: ParamType) {
        player.closeInventory()
        val currentValue = currentParams[paramName]?.first?.toString() ?: ""
        val value = signInputManager.awaitSignInput(player, currentValue)
        if (value != null) {
            currentParams[paramName] = value to paramType
            paramSerializer.save(block, paramName, value)
        }
        open()
    }

    /**
     * Persist all current params to the block's PDC and notify the player.
     * s: 1.9
     */
    fun save() {
        collectCurrentParams().forEach { (key, value) ->
            paramSerializer.save(block, key, value)
        }
        player.sendMessage("§aParameters saved.")
    }

    // -------------------------------------------------------------------------
    // Listener
    // -------------------------------------------------------------------------

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val p = event.whoClicked as? Player ?: return
        if (event.view.title != GUI_TITLE) return
        event.isCancelled = true
        handleClick(event.rawSlot, p)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Collect current param values from [currentParams].
     */
    internal fun collectCurrentParams(): Map<String, Any> =
        currentParams.mapValues { it.value.first }

    /**
     * Register a parameter with its type so SmartGUI knows how to render it.
     * Call this before [open] to declare which params exist and their types.
     */
    fun registerParam(name: String, type: ParamType, initialValue: Any = "") {
        val existing = paramSerializer.load(block, name)
        currentParams[name] = (existing ?: initialValue) to type
    }

    private fun makeItem(material: Material, name: String, lore: List<String>): ItemStack =
        itemFactory(material, name, lore)
}

/**
 * Parameter types supported by SmartGUI.
 */
enum class ParamType {
    STRING, INT, DOUBLE, BOOLEAN, LOCATION, VARIABLE_REF
}

/**
 * Default item factory for SmartGUI — creates a real Bukkit ItemStack.
 * Extracted as a top-level function so it can be referenced as a default parameter.
 */
internal fun smartGuiMakeItem(material: Material, name: String, lore: List<String>): ItemStack {
    val item = ItemStack(material)
    val meta: ItemMeta = item.itemMeta ?: return item
    meta.setDisplayName(name)
    meta.lore = lore
    item.itemMeta = meta
    return item
}
