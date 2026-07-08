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
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.LeavesDecayEvent
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
            Material.BARREL,
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

        // Glass strips must never be broken in DEV mode — cancel immediately
        if (material in setOf(Material.BLUE_STAINED_GLASS, Material.WHITE_STAINED_GLASS, Material.GRAY_STAINED_GLASS)) {
            // We cannot check mode asynchronously here because the event must be cancelled
            // synchronously. Cancel immediately and let the player know.
            // The mode check is a best-effort guard — glass strips are always protected.
            event.isCancelled = true
            player.sendMessage("§c[OCP] Нельзя сломать стеклянную полосу в DEV-режиме.")
            return
        }

        if (!isWhitelisted(material)) {
            // Only enforce in DEV mode — check asynchronously, then restore block if needed.
            // We cannot cancel the event here (already past the synchronous window),
            // so we restore the block on the main thread after the async check.
            scope.launch {
                val plot = plotManager.getPlayerPlot(player.uniqueId) ?: return@launch
                if (modeManager.getCurrentMode(player, plot) != PlotMode.DEV) return@launch
                // Player is in DEV mode — restore the block on main thread
                org.bukkit.Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (block.type == org.bukkit.Material.AIR) {
                        block.type = material
                        player.sendMessage("§c[OCP] Нельзя сломать §e${material.name}§c в DEV-режиме. " +
                            "Разрешены только блоки кодирования.")
                    }
                })
            }
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
     * NOTE: We cannot cancel the event asynchronously. Instead we check the plot
     * settings synchronously using the cached mode/plot state.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        // getPlayerPlot and getCurrentMode are in-memory lookups — safe to call on main thread
        val plot = plotManager.getPlayerPlotSync(player.uniqueId) ?: return
        if (modeManager.getCurrentMode(player, plot) != PlotMode.PLAY) return
        if (plot.settings.allowInteractions) return
        if (plot.owner == player.uniqueId) return
        if (plot.trustedPlayers.contains(player.uniqueId)) return
        event.isCancelled = true
    }

    /**
     * Cancel EntityExplodeEvent when allowExplosions is false.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        val location = event.location
        val worldName = location.world?.name ?: return
        val plot = plotManager.getAllLoadedPlotsSync().firstOrNull {
            it.mainWorldName == worldName || it.devWorldName == worldName
        } ?: return
        if (!plot.settings.allowExplosions) {
            event.blockList().clear()
            event.isCancelled = true
        }
    }

    /**
     * Cancel BlockExplodeEvent when allowExplosions is false.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        val location = event.block.location
        val worldName = location.world?.name ?: return
        val plot = plotManager.getAllLoadedPlotsSync().firstOrNull {
            it.mainWorldName == worldName || it.devWorldName == worldName
        } ?: return
        if (!plot.settings.allowExplosions) {
            event.blockList().clear()
            event.isCancelled = true
        }
    }

    /**
     * Cancel BlockIgniteEvent when allowFire is false.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockIgnite(event: BlockIgniteEvent) {
        val location = event.block.location
        val worldName = location.world?.name ?: return
        val plot = plotManager.getAllLoadedPlotsSync().firstOrNull {
            it.mainWorldName == worldName || it.devWorldName == worldName
        } ?: return
        if (!plot.settings.allowFire) {
            event.isCancelled = true
        }
    }

    // -------------------------------------------------------------------------
    // DEV-world physics freeze — pistons, block physics, leaves decay
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (isDevWorld(event.block.world.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (isDevWorld(event.block.world.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPhysics(event: BlockPhysicsEvent) {
        if (isDevWorld(event.block.world.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onLeavesDecay(event: LeavesDecayEvent) {
        if (isDevWorld(event.block.world.name)) event.isCancelled = true
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun isDevWorld(worldName: String): Boolean =
        plotManager.getAllLoadedPlotsSync().any { it.devWorldName == worldName }

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
        // Break the parameter barrel above
        val above = categoryBlock.getRelative(BlockFace.UP)
        if (above.type == Material.BARREL && hasParamChestTag(above)) {
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
