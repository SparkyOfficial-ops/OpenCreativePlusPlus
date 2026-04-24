package com.opencreativeplus.plugin.listener

import com.opencreativeplus.api.plot.ModeManager
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import com.opencreativeplus.plugin.registry.CategoryRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.TileState
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/**
 * Protects the Coding_Zone from accidental block placement and destruction while a player
 * is in DEV mode.
 *
 * Whitelist (allowed to place/break):
 *   - WHITE_STAINED_GLASS, GRAY_STAINED_GLASS, BLUE_STAINED_GLASS
 *   - CHEST, OAK_SIGN
 *   - All Category_Block materials from [CategoryRegistry]
 *
 * Requirements: 12.6, 12.7, 12.8, 12.9, 12.10
 */
class PlotProtectionListener(
    private val modeManager: ModeManager,
    private val categoryRegistry: CategoryRegistry,
    private val plotManager: PlotManagerImpl,
    private val scope: CoroutineScope,
    private val plugin: Plugin
) : Listener {

    companion object {
        private val KEY_PARAM_CHEST = NamespacedKey("ocp", "param_chest")
        private val KEY_ACTION_ID   = NamespacedKey("ocp", "action_id")

        /** Fixed whitelist materials (excluding category materials, which are dynamic). */
        private val FIXED_WHITELIST = setOf(
            Material.WHITE_STAINED_GLASS,
            Material.GRAY_STAINED_GLASS,
            Material.BLUE_STAINED_GLASS,
            Material.CHEST,
            Material.OAK_SIGN,
            Material.OAK_WALL_SIGN
        )

        /** Materials that must never be placed, even if somehow in the whitelist. */
        private val ALWAYS_BLOCKED_PLACE = setOf(
            Material.LAVA,
            Material.WATER
        )
    }

    // -------------------------------------------------------------------------
    // BlockPlaceEvent — Requirement 12.6, 12.7, 12.9
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val material = event.blockPlaced.type

        scope.launch {
            val plot = plotManager.getPlayerPlot(player.uniqueId) ?: return@launch
            if (modeManager.getCurrentMode(player, plot) != PlotMode.DEV) return@launch

            // Req 12.7: LAVA and WATER are always blocked
            if (material in ALWAYS_BLOCKED_PLACE) {
                cancelAndNotify(event, player, material)
                return@launch
            }

            // Req 12.6: only whitelist materials are allowed
            if (!isWhitelisted(material)) {
                cancelAndNotify(event, player, material)
            }
        }
    }

    // -------------------------------------------------------------------------
    // BlockBreakEvent — Requirement 12.8, 12.9, 12.10
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block
        val material = block.type

        scope.launch {
            val plot = plotManager.getPlayerPlot(player.uniqueId) ?: return@launch
            if (modeManager.getCurrentMode(player, plot) != PlotMode.DEV) return@launch

            if (!isWhitelisted(material)) {
                // Req 12.8: cancel break of non-whitelisted blocks
                cancelAndNotify(event, player, material)
                return@launch
            }

            // Req 12.10: if this is a Category_Block, cascade-break chest above and sign beside
            if (categoryRegistry.isCategoryMaterial(material)) {
                cascadeBreakAttachments(block)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns true if [material] is in the combined whitelist. */
    private fun isWhitelisted(material: Material): Boolean =
        material in FIXED_WHITELIST || categoryRegistry.isCategoryMaterial(material)

    /**
     * Cancels [event] and sends the player a descriptive message.
     * Req 12.9
     */
    private fun cancelAndNotify(event: org.bukkit.event.Cancellable, player: Player, material: Material) {
        event.isCancelled = true
        player.sendMessage("§c[OCP] Нельзя взаимодействовать с блоком §e${material.name}§c в DEV-режиме. " +
                "Разрешены только блоки кодирования.")
    }

    /**
     * When a Category_Block is broken, also remove:
     *  - the parameter chest directly above (if it has `ocp:param_chest` PDC tag)
     *  - the adjacent sign on the nearest horizontal face
     *
     * Req 12.10
     */
    private fun cascadeBreakAttachments(categoryBlock: Block) {
        // Break the parameter chest above
        val above = categoryBlock.getRelative(BlockFace.UP)
        if (above.type == Material.CHEST && hasParamChestTag(above)) {
            above.type = Material.AIR
        }

        // Break the adjacent sign (first matching horizontal face)
        val horizontalFaces = listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)
        for (face in horizontalFaces) {
            val adjacent = categoryBlock.getRelative(face)
            if (adjacent.type == Material.OAK_SIGN || adjacent.type == Material.OAK_WALL_SIGN) {
                adjacent.type = Material.AIR
                break
            }
        }
    }

    /** Returns true if [block] has the `ocp:param_chest = "true"` PDC tag. */
    private fun hasParamChestTag(block: Block): Boolean {
        val pdc = (block.state as? TileState)?.persistentDataContainer ?: return false
        return pdc.get(KEY_PARAM_CHEST, PersistentDataType.STRING) == "true"
    }
}
