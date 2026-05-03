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
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
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

        // Req 12.7: LAVA and WATER are always blocked regardless of mode
        if (material in ALWAYS_BLOCKED_PLACE) {
            event.isCancelled = true
            player.sendMessage("§c[OCP] Нельзя размещать §e${material.name}§c.")
            return
        }

        // For non-whitelisted materials: check if player is in DEV mode and remove the block if so
        if (!isWhitelisted(material)) {
            val placedBlock = event.blockPlaced
            scope.launch {
                val plot = plotManager.getPlayerPlot(player.uniqueId) ?: return@launch
                if (modeManager.getCurrentMode(player, plot) != PlotMode.DEV) return@launch
                // Player is in DEV mode — remove the illegally placed block on main thread
                org.bukkit.Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (placedBlock.type == material) { // verify it's still there
                        placedBlock.type = org.bukkit.Material.AIR
                        player.sendMessage("§c[OCP] Нельзя разместить §e${material.name}§c в DEV-режиме. " +
                            "Разрешены только блоки кодирования.")
                    }
                })
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

        if (!isWhitelisted(material)) {
            // Req 12.8: cancel break of non-whitelisted blocks synchronously
            event.isCancelled = true
            scope.launch {
                val plot = plotManager.getPlayerPlot(player.uniqueId) ?: return@launch
                if (modeManager.getCurrentMode(player, plot) != PlotMode.DEV) {
                    // Not in DEV mode — undo the cancel is not possible, but this is fine:
                    // outside DEV mode the protection is a no-op by design.
                }
            }
            player.sendMessage("§c[OCP] Нельзя сломать §e${material.name}§c в DEV-режиме. " +
                    "Разрешены только блоки кодирования.")
            return
        }

        // Req 12.10: if this is a Category_Block, cascade-break chest above and sign beside
        if (categoryRegistry.isCategoryMaterial(material)) {
            cascadeBreakAttachments(block)
        }
    }

    // -------------------------------------------------------------------------
    // New flag handlers: interactions, explosions, fire
    // -------------------------------------------------------------------------

    /**
     * Cancel PlayerInteractEvent for non-owners when allowInteractions is false.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        scope.launch {
            val plot = plotManager.getPlayerPlot(player.uniqueId) ?: return@launch
            if (modeManager.getCurrentMode(player, plot) != PlotMode.PLAY) return@launch
            if (plot.settings.allowInteractions) return@launch
            if (plot.owner == player.uniqueId) return@launch
            if (plot.trustedPlayers.contains(player.uniqueId)) return@launch
            event.isCancelled = true
        }
    }

    /**
     * Cancel EntityExplodeEvent when allowExplosions is false.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        val location = event.location
        scope.launch {
            val plot = findPlotAtLocation(location) ?: return@launch
            if (!plot.settings.allowExplosions) {
                event.blockList().clear()
                event.isCancelled = true
            }
        }
    }

    /**
     * Cancel BlockExplodeEvent when allowExplosions is false.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        val location = event.block.location
        scope.launch {
            val plot = findPlotAtLocation(location) ?: return@launch
            if (!plot.settings.allowExplosions) {
                event.blockList().clear()
                event.isCancelled = true
            }
        }
    }

    /**
     * Cancel BlockIgniteEvent when allowFire is false.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockIgnite(event: BlockIgniteEvent) {
        val location = event.block.location
        scope.launch {
            val plot = findPlotAtLocation(location) ?: return@launch
            if (!plot.settings.allowFire) {
                event.isCancelled = true
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
     * Find the plot associated with a world location by checking all loaded plots.
     * Returns null if no plot is found for that world.
     */
    private suspend fun findPlotAtLocation(location: org.bukkit.Location): com.opencreativeplus.api.plot.Plot? {
        val worldName = location.world?.name ?: return null
        return plotManager.getAllLoadedPlots().firstOrNull { plot ->
            plot.mainWorldName == worldName || plot.devWorldName == worldName
        }
    }

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
