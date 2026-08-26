package com.opencreativeplus.plugin.gui

import com.opencreativeplus.api.plot.ModeManager
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import com.opencreativeplus.plugin.registry.ActionDescriptor
import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.registry.NodeCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
 * Writes the selected `action_id` to the block's PDC. Parameters are then
 * edited via SmartGUI (right-click the block again). No physical barrels are used.
 *
 * Баг 2: для IF_* и LOOP категорий автоматически ставит поршневую скобку
 * [STICKY_PISTON] → [тело] → [PISTON], чтобы синтаксис был всегда корректным.
 *
 * Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 6.1, 6.2, 6.3, 6.4
 */
class NodeSelectionGUI(
    private val categoryRegistry: CategoryRegistry,
    private val plugin: Plugin,
    private val modeManager: ModeManager? = null,
    private val plotManager: PlotManagerImpl? = null,
    private val scope: CoroutineScope? = null
) : Listener {

    companion object {
        const val ITEMS_PER_PAGE = 45
        private val KEY_ACTION_ID = NamespacedKey("ocp", "action_id")
        private const val GUI_TITLE_PREFIX = "Выбор действия: "

        private val GLASS_STRIP_MATERIALS = setOf(
            Material.BLUE_STAINED_GLASS,
            Material.WHITE_STAINED_GLASS,
            Material.GRAY_STAINED_GLASS
        )

        /** Categories that require an auto-placed piston bracket after selection. */
        private val BRACKET_CATEGORIES = setOf(
            NodeCategory.IF_PLAYER,
            NodeCategory.IF_VARIABLE,
            NodeCategory.IF_ENTITY,
            NodeCategory.LOOP
        )
    }

    internal val pendingBlocks = ConcurrentHashMap<UUID, Block>()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

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

        if (safePage > 0) inventory.setItem(45, createNavItem(Material.ARROW, "← Назад"))
        if (safePage < totalPages - 1) inventory.setItem(53, createNavItem(Material.ARROW, "Вперёд →"))

        player.openInventory(inventory)
    }

    // -------------------------------------------------------------------------
    // Listeners
    // -------------------------------------------------------------------------

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        val category = categoryRegistry.getCategoryForMaterial(block.type) ?: return
        val player = event.player

        event.isCancelled = true

        if (modeManager != null && plotManager != null && scope != null) {
            scope.launch {
                val plot = plotManager.getPlayerPlot(player.uniqueId) ?: return@launch
                if (modeManager.getCurrentMode(player, plot) != com.opencreativeplus.api.plot.PlotMode.DEV) return@launch
                pendingBlocks[player.uniqueId] = block
                plugin.server.scheduler.runTask(plugin) { _ ->
                    open(player, block, category)
                }
            }
        } else {
            pendingBlocks[player.uniqueId] = block
            open(player, block, category)
        }
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

        // Записываем action_id в PDC блока
        writeActionId(targetBlock, actionId)
        placeOrUpdateSign(targetBlock, categoryRegistry.getDescriptorById(actionId)?.displayName ?: actionId)

        // Баг 2: авто-ставим поршневую скобку для IF и LOOP категорий
        val descriptor = categoryRegistry.getDescriptorById(actionId)
        if (descriptor != null && descriptor.category in BRACKET_CATEGORIES) {
            autoPlacePistonBracket(targetBlock)
        }
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

    // -------------------------------------------------------------------------
    // Public helpers (also used by tests)
    // -------------------------------------------------------------------------

    /**
     * Place a new sign or update an existing sign on an adjacent face of [block].
     * Requirements: 6.1, 6.2, 6.3, 6.4
     */
    fun placeOrUpdateSign(block: Block, displayName: String) {
        val faces = listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)

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

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Баг 2: авто-простановка поршневой скобки
    // -------------------------------------------------------------------------

    /**
     * Automatically builds a piston bracket structure east of [conditionBlock]:
     *
     *   ... [conditionBlock] [glass+STICKY_PISTON] [glass body] [glass+PISTON] ...
     *
     * This guarantees syntactically correct bracket pairs so ASTCompiler never
     * throws "Unclosed bracket". Players cannot break pistons — PlotProtectionListener
     * cancels all piston physics events in dev worlds.
     *
     * Skips silently if:
     * - conditionBlock is not sitting on a glass strip
     * - a bracket already exists (STICKY_PISTON already placed)
     * - blocks ahead are occupied by non-glass
     */
    private fun autoPlacePistonBracket(conditionBlock: Block) {
        val glassBelow = conditionBlock.getRelative(BlockFace.DOWN)
        if (glassBelow.type !in GLASS_STRIP_MATERIALS) return

        // Opening bracket glass (one east of the condition's glass)
        val glassOpen = glassBelow.getRelative(BlockFace.EAST)
        when {
            glassOpen.type == Material.AIR -> glassOpen.type = Material.WHITE_STAINED_GLASS
            glassOpen.type !in GLASS_STRIP_MATERIALS -> return  // occupied — skip
        }

        val aboveOpen = glassOpen.getRelative(BlockFace.UP)
        if (aboveOpen.type == Material.STICKY_PISTON) return  // bracket already there

        if (aboveOpen.type == Material.AIR) aboveOpen.type = Material.STICKY_PISTON

        // Body glass tile
        val glassBody = glassOpen.getRelative(BlockFace.EAST)
        if (glassBody.type == Material.AIR) glassBody.type = Material.WHITE_STAINED_GLASS

        // Closing bracket glass
        val glassClose = glassBody.getRelative(BlockFace.EAST)
        if (glassClose.type == Material.AIR) glassClose.type = Material.WHITE_STAINED_GLASS

        val aboveClose = glassClose.getRelative(BlockFace.UP)
        if (aboveClose.type == Material.AIR) aboveClose.type = Material.PISTON
    }
}
