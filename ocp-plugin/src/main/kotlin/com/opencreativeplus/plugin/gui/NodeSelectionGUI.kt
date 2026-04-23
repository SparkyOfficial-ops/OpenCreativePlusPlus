package com.opencreativeplus.plugin.gui

import com.opencreativeplus.api.plot.ModeManager
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import com.opencreativeplus.plugin.registry.ActionDescriptor
import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.registry.NodeCategory
import com.opencreativeplus.plugin.scanner.ParameterPlacer
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.TileState
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import kotlinx.coroutines.CoroutineScope

/**
 * Bukkit inventory GUI for selecting an action from a NodeCategory.
 *
 * Layout (54 slots):
 *   Slots 0-44  - Action_Items (up to ITEMS_PER_PAGE per page)
 *   Slot  45    - Previous page
 *   Slot  53    - Next page
 *
 * Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 6.1, 6.2, 6.3, 6.4
 */
class NodeSelectionGUI(
    private val categoryRegistry: CategoryRegistry,
    private val parameterPlacer: ParameterPlacer,
    private val modeManager: ModeManager,
    private val plotManager: PlotManagerImpl,
    private val scope: CoroutineScope,
    private val plugin: Plugin
) : Listener {

    private val keyActionId = NamespacedKey(plugin, "action_id")

    private val sessions = mutableMapOf<String, Triple<Block, NodeCategory, Int>>()

    fun open(player: Player, block: Block, category: NodeCategory, page: Int = 0) {
        val descriptors = categoryRegistry.getDescriptors(category)
        val totalPages = totalPages(descriptors.size)
        val safePage = page.coerceIn(0, maxOf(0, totalPages - 1))

        val title = category.russianLabel
        val inventory = Bukkit.createInventory(null, 54, Component.text(title))

        val pageStart = safePage * ITEMS_PER_PAGE
        val pageItems = descriptors.drop(pageStart).take(ITEMS_PER_PAGE)

        val existingId = readActionId(block)

        pageItems.forEachIndexed { slot, descriptor ->
            val item = createActionItem(descriptor, highlighted = descriptor.id == existingId)
            inventory.setItem(slot, item)
        }

        if (safePage > 0) {
            inventory.setItem(SLOT_PREV, createNavItem(Material.ARROW, "Prev"))
        }
        if (safePage < totalPages - 1) {
            inventory.setItem(SLOT_NEXT, createNavItem(Material.ARROW, "Next"))
        }

        sessions[player.name] = Triple(block, category, safePage)
        player.openInventory(inventory)
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        val category = categoryRegistry.getCategoryForMaterial(block.type) ?: return
        event.isCancelled = true
        open(event.player, block, category)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = sessions[player.name] ?: return
        event.isCancelled = true

        val (block, category, page) = session
        val slot = event.rawSlot

        if (slot == SLOT_PREV && page > 0) {
            open(player, block, category, page - 1)
            return
        }
        if (slot == SLOT_NEXT) {
            open(player, block, category, page + 1)
            return
        }

        if (slot !in 0 until ITEMS_PER_PAGE) return

        val descriptors = categoryRegistry.getDescriptors(category)
        val pageStart = page * ITEMS_PER_PAGE
        val descriptor = descriptors.getOrNull(pageStart + slot) ?: return

        writeActionId(block, descriptor.id)

        if (descriptor.expectedParams.isNotEmpty()) {
            parameterPlacer.placeChest(block)
        }

        placeOrUpdateSign(block, descriptor.displayName)

        sessions.remove(player.name)
        player.closeInventory()
    }

    private fun createActionItem(descriptor: ActionDescriptor, highlighted: Boolean): ItemStack {
        val item = ItemStack(descriptor.icon)
        val meta: ItemMeta = item.itemMeta ?: return item
        meta.displayName(Component.text(descriptor.displayName))
        item.itemMeta = meta
        return item
    }

    private fun createNavItem(material: Material, name: String): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(Component.text(name))
        item.itemMeta = meta
        return item
    }

    fun writeActionId(block: Block, actionId: String) {
        val state = block.state as? TileState ?: return
        state.persistentDataContainer.set(keyActionId, PersistentDataType.STRING, actionId)
        state.update()
    }

    fun readActionId(block: Block): String? {
        val state = block.state as? TileState ?: return null
        return state.persistentDataContainer.get(keyActionId, PersistentDataType.STRING)
    }

    @Suppress("DEPRECATION")
    internal fun placeOrUpdateSign(block: Block, displayName: String) {
        val faces = listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)
        val existingSignFace = faces.firstOrNull { face ->
            block.getRelative(face).type == Material.OAK_WALL_SIGN
        }
        val targetFace = existingSignFace ?: faces.firstOrNull { face ->
            block.getRelative(face).type == Material.AIR
        }
        if (targetFace == null) {
            plugin.logger.warning("NodeSelectionGUI: no available face for sign at ${block.location}")
            return
        }
        val signBlock = block.getRelative(targetFace)
        signBlock.type = Material.OAK_WALL_SIGN
        val signState = signBlock.state as? org.bukkit.block.Sign ?: return
        signState.setLine(0, displayName)
        signState.update()
    }

    private fun totalPages(n: Int): Int =
        if (n == 0) 1 else (n + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE

    companion object {
        const val ITEMS_PER_PAGE = 45
        private const val SLOT_PREV = 45
        private const val SLOT_NEXT = 53
    }
}
