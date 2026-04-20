package com.opencreativeplus.plugin.node.gui

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.plugin.event.EventDispatcher
import kotlinx.coroutines.CoroutineScope
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin

/**
 * Action node that opens a named [CustomMenuDefinition] for a target player.
 *
 * - Loads the menu from [PlotMenuRegistry] (populated by [GUIDesignerEditor]).
 * - Opens a 54-slot inventory populated with the slot items.
 * - Registers a [CustomMenuClickListener] that dispatches the slot's click script on click.
 * - Cancels clicks on slots with no assigned script (req 1.7).
 *
 * nodeId = "open_menu"
 * params:
 *   - `menu_name` (String): name of the menu to open
 *   - `target_player` (String): variable name holding the target [Player]
 *
 * Requirements: 1.1, 1.6, 1.7
 */
class OpenMenuNode(
    private val params: Map<String, Any>,
    private val eventDispatcher: EventDispatcher,
    private val scope: CoroutineScope,
    private val plugin: Plugin,
    inventoryFactory: ((String) -> Inventory)? = null
) : IAction {

    override val nodeId = "open_menu"
    override val displayName = "Open Menu"

    private val inventoryFactory: (String) -> Inventory = inventoryFactory
        ?: { title -> Bukkit.createInventory(null, 54, title) }

    override suspend fun execute(context: ExecutionContext) {
        val menuName = params["menu_name"]?.toString() ?: return
        val targetPlayerVar = params["target_player"]?.toString() ?: return

        // Resolve target player from local scope, then plot scope
        val targetPlayer = (context.localScope.get(targetPlayerVar)
            ?: context.plotScope.get(targetPlayerVar)) as? Player ?: return

        // Load menu definition from the in-memory registry (req 1.6)
        val definition = PlotMenuRegistry.get(context.plotId, menuName) ?: run {
            plugin.logger.warning("[OCP] OpenMenuNode: menu '$menuName' not found for plot ${context.plotId}")
            return
        }

        val inventoryTitle = "§8$menuName"
        val inventory = inventoryFactory(inventoryTitle)

        // Populate inventory with slot items
        for ((slot, slotDef) in definition.slots) {
            if (slot in 0 until 54) {
                inventory.setItem(slot, slotDef.item.clone())
            }
        }

        val listener = CustomMenuClickListener(
            targetPlayer = targetPlayer,
            inventoryTitle = inventoryTitle,
            definition = definition,
            eventDispatcher = eventDispatcher,
            context = context,
            plugin = plugin
        )

        context.syncContext {
            plugin.server.pluginManager.registerEvents(listener, plugin)
            targetPlayer.openInventory(inventory)
        }
    }
}

/**
 * Handles click and close events for an open [CustomMenuDefinition] inventory.
 *
 * On click:
 * - Cancels the event (prevents item movement).
 * - If the clicked slot has a [MenuSlotDefinition] with a click script, dispatches it
 *   via [EventDispatcher] using event type `"menu_click:{scriptName}"`.
 * - If no script is assigned, the click is silently cancelled (req 1.7).
 *
 * On close: unregisters itself.
 *
 * Requirements: 1.6, 1.7
 */
class CustomMenuClickListener(
    private val targetPlayer: Player,
    private val inventoryTitle: String,
    private val definition: CustomMenuDefinition,
    private val eventDispatcher: EventDispatcher,
    private val context: ExecutionContext,
    private val plugin: Plugin
) : Listener {

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val p = event.whoClicked as? Player ?: return
        if (p.uniqueId != targetPlayer.uniqueId) return
        if (event.view.title != inventoryTitle) return

        // Always cancel to prevent item movement (req 1.7)
        event.isCancelled = true

        val slot = event.rawSlot
        if (slot !in 0 until 54) return

        val slotDef = definition.slots[slot] ?: return  // no definition → silently cancelled (req 1.7)
        val scriptName = slotDef.clickScriptName ?: return  // no script → silently cancelled (req 1.7)

        // Dispatch the click script via EventDispatcher (req 1.6)
        // The event type "menu_click:{scriptName}" matches scripts registered with that event type.
        eventDispatcher.dispatchEvent(
            plotId = context.plotId,
            eventType = "menu_click:$scriptName",
            eventData = mapOf(
                "menu_name" to definition.name,
                "slot" to slot,
                "click_script" to scriptName
            ),
            player = targetPlayer
        )
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val p = event.player as? Player ?: return
        if (p.uniqueId != targetPlayer.uniqueId) return
        if (event.view.title != inventoryTitle) return
        HandlerList.unregisterAll(this)
    }
}
