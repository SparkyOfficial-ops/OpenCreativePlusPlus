package com.opencreativeplus.plugin.listener

import com.opencreativeplus.api.plot.ModeManager
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.registry.NodeCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.block.data.type.WallSign
import org.bukkit.block.sign.Side
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.plugin.Plugin

/**
 * Auto-decoration listener: when a player places a Category_Block on a coding strip
 * in DEV mode, a coloured glowing wall sign is automatically placed on the south face
 * showing the category's Russian label.
 *
 * Sign cleanup on break is already handled by [PlotProtectionListener.cascadeBreakAttachments].
 *
 * Requirements: 3.2, 3.3, 12.10
 */
class AutoDecorationListener(
    private val modeManager: ModeManager,
    private val categoryRegistry: CategoryRegistry,
    private val plotManager: PlotManagerImpl,
    private val scope: CoroutineScope,
    private val plugin: Plugin
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player   = event.player
        val block    = event.blockPlaced
        val material = block.type

        val category = categoryRegistry.getCategoryForMaterial(material) ?: return

        scope.launch {
            val plot = plotManager.getPlayerPlot(player.uniqueId) ?: return@launch
            if (modeManager.getCurrentMode(player, plot) != PlotMode.DEV) return@launch

            // Only decorate when placed on a coding strip (glass floor)
            val below = block.getRelative(BlockFace.DOWN)
            if (!isGlassStrip(below.type)) return@launch

            Bukkit.getScheduler().runTask(plugin, Runnable {
                placeSign(block, category)
            })
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun placeSign(categoryBlock: Block, category: NodeCategory) {
        // Prefer south face; fall back to first free horizontal face
        val face = listOf(BlockFace.SOUTH, BlockFace.NORTH, BlockFace.EAST, BlockFace.WEST)
            .firstOrNull { categoryBlock.getRelative(it).type == Material.AIR }
            ?: return // no room for a sign

        val signBlock = categoryBlock.getRelative(face)
        val signMaterial = signMaterialFor(category)
        signBlock.type = signMaterial

        // Orient the WallSign to face outward
        val data = signBlock.blockData
        if (data is WallSign) {
            data.facing = face
            signBlock.blockData = data
        }

        // Write label + glow
        val state = signBlock.state
        if (state is Sign) {
            val front = state.getSide(Side.FRONT)
            front.line(0, Component.text(category.russianLabel)
                .color(textColorFor(category))
                .decoration(TextDecoration.BOLD, true))
            front.isGlowingText = true
            state.update(true, false)
        }
    }

    private fun isGlassStrip(type: Material): Boolean = type in setOf(
        Material.BLUE_STAINED_GLASS,
        Material.WHITE_STAINED_GLASS,
        Material.GRAY_STAINED_GLASS,
        Material.BLACK_STAINED_GLASS
    )

    private fun signMaterialFor(category: NodeCategory): Material = when (category) {
        NodeCategory.PLAYER_EVENT  -> Material.DARK_OAK_WALL_SIGN
        NodeCategory.IF_PLAYER     -> Material.OAK_WALL_SIGN
        NodeCategory.PLAYER_ACTION -> Material.ACACIA_WALL_SIGN
        NodeCategory.GAME_ACTION   -> Material.CRIMSON_WALL_SIGN
        NodeCategory.IF_VARIABLE   -> Material.BIRCH_WALL_SIGN
        NodeCategory.SET_VARIABLE  -> Material.SPRUCE_WALL_SIGN
        NodeCategory.SELECT_OBJECT -> Material.MANGROVE_WALL_SIGN
        NodeCategory.IF_ENTITY     -> Material.JUNGLE_WALL_SIGN
        NodeCategory.ARRAY_OP      -> Material.BAMBOO_WALL_SIGN
        NodeCategory.LOOP          -> Material.CHERRY_WALL_SIGN
        NodeCategory.FUNCTION      -> Material.WARPED_WALL_SIGN
    }

    private fun textColorFor(category: NodeCategory): NamedTextColor = when (category) {
        NodeCategory.PLAYER_EVENT  -> NamedTextColor.AQUA
        NodeCategory.IF_PLAYER     -> NamedTextColor.GREEN
        NodeCategory.PLAYER_ACTION -> NamedTextColor.YELLOW
        NodeCategory.GAME_ACTION   -> NamedTextColor.RED
        NodeCategory.IF_VARIABLE   -> NamedTextColor.LIGHT_PURPLE
        NodeCategory.SET_VARIABLE  -> NamedTextColor.GOLD
        NodeCategory.SELECT_OBJECT -> NamedTextColor.WHITE
        NodeCategory.IF_ENTITY     -> NamedTextColor.DARK_GREEN
        NodeCategory.ARRAY_OP      -> NamedTextColor.DARK_AQUA
        NodeCategory.LOOP          -> NamedTextColor.DARK_PURPLE
        NodeCategory.FUNCTION      -> NamedTextColor.BLUE
    }
}
