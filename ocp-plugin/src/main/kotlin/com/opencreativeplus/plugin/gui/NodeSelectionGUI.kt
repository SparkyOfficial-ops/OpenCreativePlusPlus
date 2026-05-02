package com.opencreativeplus.plugin.gui

import com.opencreativeplus.api.plot.ModeManager
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import com.opencreativeplus.plugin.registry.ActionDescriptor
import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.registry.NodeCategory
import com.opencreativeplus.plugin.scanner.ParameterPlacer
import kotlinx.coroutines.CoroutineScope
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
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * GUI for selecting an action from a NodeCategory.
 *
 * Opens when a player right-clicks a Category_Block in the Coding_Zone.
 * Writes the selected `action_id` to the block's PDC and triggers ParameterPlacer.
 *
 * Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 6.1, 6.2, 6.3, 6.4
 */
class NodeSelectionGUI(
    private val categoryRegistry: CategoryRegistry,
    private val parameterPlacer: ParameterPlacer,
    private val plugin: Plugin,
    private val modeManager: ModeManager? = null,
    private val plotManager: PlotManagerImpl? = null,
    private val scope: CoroutineScope? = null
) : Listener {

    companion object {
        const val ITEMS_PER_PAGE = 45
        private val KEY_ACTION_ID = NamespacedKey("ocp", "action_id")
        private const val GUI_TITLE_PREFIX = "Выбор действия: "
    }

    internal val pendingBlocks = ConcurrentHashMap<UUID, Block>()

    /**
     * Open the action selection GUI for [category] at [page].
     * Requirements: 3.1, 3.2, 3.3
     */
    fun open(player: Player, block: Block, category: NodeCategory, page: Int = 0) {
        val descriptors = categoryRegistry.getDescriptors(category)
        val totalPages = maxOf(1, (descriptors.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE)
        val safePage = page.coerceIn(0, totalPages - 1)

        val inventory = Bukkit.createInventory(null, 54, Component.text("$GUI_TITLE_PREFIX${category.russianLabel}"))

        val start = safePage * ITEMS_PER_PAGE
        val end = minOf(start + ITEMS_PER_PAGE, descriptors.size)
        val currentActionId = readCurrentActionId(block)

        if (descriptors.isEmpty()) {
            // Req 4 AC5: show informational item when no actions are registered for this category
            val infoItem = ItemStack(Material.BARRIER)
            val infoMeta: ItemMeta = infoItem.itemMeta ?: Bukkit.getItemFactory().getItemMeta(Material.BARRIER)!!
            @Suppress("DEPRECATION")
            infoMeta.setDisplayName("§7Нет доступных действий")
            @Suppress("DEPRECATION")
            infoMeta.lore = listOf("§8Для этой категории не зарегистрировано ни одного действия.")
            infoItem.itemMeta = infoMeta
            inventory.setItem(22, infoItem)
        }

        for (i in start until end) {
            val descriptor = descriptors[i]
            val item = createActionItem(descriptor, descriptor.id == currentActionId)
            inventory.setItem(i - start, item)
        }

        // Navigation buttons
        if (safePage > 0) {
            inventory.setItem(45, createNavItem(Material.ARROW, "← Назад"))
        }
        if (safePage < totalPages - 1) {
            inventory.setItem(53, createNavItem(Material.ARROW, "Вперёд →"))
        }

        player.openInventory(inventory)
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        val category = categoryRegistry.getCategoryForMaterial(block.type) ?: return
        // Req 4 AC1: only open GUI when block has no action_id assigned yet
        if (readCurrentActionId(block) != null) return
        event.isCancelled = true
        pendingBlocks[event.player.uniqueId] = block
        open(event.player, block, category)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val title = event.view.title()
        val titleStr = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title)
        if (!titleStr.startsWith(GUI_TITLE_PREFIX)) return

        event.isCancelled = true
        val item = event.currentItem ?: return
        val meta = item.itemMeta ?: return

        val actionId = meta.persistentDataContainer.get(KEY_ACTION_ID, PersistentDataType.STRING) ?: return

        val player = event.whoClicked as? Player ?: return
        player.closeInventory()

        val targetBlock = pendingBlocks[player.uniqueId] ?: return
        if (!categoryRegistry.isCategoryMaterial(targetBlock.type)) return

        writeActionId(targetBlock, actionId)
        placeOrUpdateSign(targetBlock, categoryRegistry.getDescriptorById(actionId)?.displayName ?: actionId)
        parameterPlacer.placeChest(targetBlock)
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val titleStr = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(event.view.title())
        if (titleStr.startsWith(GUI_TITLE_PREFIX)) {
            pendingBlocks.remove(player.uniqueId)
        }
    }

    // -----------------------------------------------------------------------
    // Public helpers (also used by tests)
    // -----------------------------------------------------------------------

    /**
     * Place a new sign or update an existing sign on an adjacent face of [block].
     * Writes [displayName] to the first line.
     * Requirements: 6.1, 6.2, 6.3, 6.4
     */
    fun placeOrUpdateSign(block: Block, displayName: String) {
        val faces = listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)

        // Check for existing sign first — update it
        for (face in faces) {
            val adjacent = block.getRelative(face)
            if (adjacent.type == Material.OAK_SIGN || adjacent.type == Material.OAK_WALL_SIGN) {
                val signState = adjacent.state as? org.bukkit.block.Sign ?: continue
                @Suppress("DEPRECATION")
                signState.setLine(0, displayName)
                signState.update()
                return
            }
        }

        // Place new sign on first available AIR face
        for (face in faces) {
            val adjacent = block.getRelative(face)
            if (adjacent.type == Material.AIR) {
                adjacent.type = Material.OAK_WALL_SIGN
                val signState = adjacent.state as? org.bukkit.block.Sign ?: continue
                @Suppress("DEPRECATION")
                signState.setLine(0, displayName)
                signState.update()
                return
            }
        }

        plugin.logger.warning(
            "NodeSelectionGUI: all four faces of block at ${block.location} are occupied — sign not placed"
        )
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun readCurrentActionId(block: Block): String? {
        val pdc = (block.state as? TileState)?.persistentDataContainer ?: return null
        return pdc.get(KEY_ACTION_ID, PersistentDataType.STRING)
    }

    private fun writeActionId(block: Block, actionId: String) {
        val state = block.state as? TileState ?: return
        state.persistentDataContainer.set(KEY_ACTION_ID, PersistentDataType.STRING, actionId)
        state.update()
    }

    private fun createActionItem(descriptor: ActionDescriptor, highlighted: Boolean): ItemStack {
        val item = ItemStack(descriptor.icon)
        val meta = item.itemMeta ?: return item
        meta.displayName(Component.text(descriptor.displayName))
        meta.persistentDataContainer.set(KEY_ACTION_ID, PersistentDataType.STRING, descriptor.id)
        if (highlighted) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.LUCK, 1, true)
        }
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
}
