package com.opencreativeplus.plugin.node.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.Plugin

/**
 * 54-slot inventory editor for creating and editing a [CustomMenuDefinition].
 *
 * - Placing an item in a slot registers it as the visual for that [MenuSlotDefinition].
 * - The display name and click script name are read from the item's display name and lore
 *   (first lore line prefixed with "Script: ").
 * - On inventory close the current state is persisted to [menuStore] and [PlotMenuRegistry].
 *
 * Requirements: 1.2, 1.3, 1.4, 1.5
 */
class GUIDesignerEditor(
    private val player: Player,
    private val menuName: String,
    private val menuStore: ICustomMenuStore,
    private val plugin: Plugin,
    private val plotId: java.util.UUID? = null,
    inventoryFactory: (String) -> Inventory = { title ->
        Bukkit.createInventory(null, 54, title)
    }
) : Listener {

    companion object {
        private const val TITLE_PREFIX = "GUI Designer: "

        /** Lore prefix used to store the click script name on an item. */
        const val SCRIPT_LORE_PREFIX = "Script: "
    }

    private val inventory: Inventory = inventoryFactory("$TITLE_PREFIX$menuName")

    /** Opens the editor, pre-loading any existing definition from the store. */
    fun open() {
        // Pre-load existing definition if present
        val existing = menuStore.load(menuName)
        if (existing != null) {
            for ((slot, slotDef) in existing.slots) {
                if (slot in 0 until 54) {
                    val item = slotDef.item.clone()
                    // Ensure display name and script lore are reflected on the item
                    val meta: ItemMeta = item.itemMeta ?: Bukkit.getItemFactory().getItemMeta(item.type) ?: continue
                    meta.setDisplayName(slotDef.displayName)
                    val lore = meta.lore?.toMutableList() ?: mutableListOf()
                    // Remove any existing script lore line and re-add
                    lore.removeAll { it.startsWith(SCRIPT_LORE_PREFIX) }
                    if (slotDef.clickScriptName != null) {
                        lore.add("$SCRIPT_LORE_PREFIX${slotDef.clickScriptName}")
                    }
                    meta.lore = lore
                    item.itemMeta = meta
                    inventory.setItem(slot, item)
                }
            }
        }
        player.openInventory(inventory)
    }

    // ── Listener ─────────────────────────────────────────────────────────────

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val p = event.whoClicked as? Player ?: return
        if (p.uniqueId != player.uniqueId) return
        if (!event.view.title.startsWith(TITLE_PREFIX)) return
        // Allow placing/taking items freely — do not cancel the event
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val p = event.player as? Player ?: return
        if (p.uniqueId != player.uniqueId) return
        if (!event.view.title.startsWith(TITLE_PREFIX)) return

        saveCurrentState()
        HandlerList.unregisterAll(this)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Reads all non-null slots from the inventory and persists them as a [CustomMenuDefinition].
     */
    internal fun saveCurrentState() {
        val slots = mutableMapOf<Int, MenuSlotDefinition>()
        for (i in 0 until 54) {
            val item = inventory.getItem(i) ?: continue
            if (item.type == Material.AIR) continue

            val meta = item.itemMeta
            val displayName = meta?.displayName ?: item.type.name
            val clickScriptName = meta?.lore
                ?.firstOrNull { it.startsWith(SCRIPT_LORE_PREFIX) }
                ?.removePrefix(SCRIPT_LORE_PREFIX)
                ?.takeIf { it.isNotBlank() }

            slots[i] = MenuSlotDefinition(
                item = item.clone(),
                displayName = displayName,
                clickScriptName = clickScriptName
            )
        }
        val definition = CustomMenuDefinition(name = menuName, slots = slots)
        menuStore.save(definition)
        // Also register in the in-memory registry so OpenMenuNode can find it
        if (plotId != null) {
            PlotMenuRegistry.put(plotId, definition)
        }
        player.sendMessage("§a[OCP] Menu '$menuName' saved (${slots.size} slot(s)).")
    }
}
