package com.opencreativeplus.plugin.listener

import com.opencreativeplus.api.plot.ModeManager
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import com.opencreativeplus.plugin.registry.CategoryRegistry
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
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
    private val plugin: Plugin
) : Listener {

    companion object {
        /** Fixed whitelist materials (excluding category materials, which are dynamic). */
        private val FIXED_WHITELIST = setOf(
            Material.WHITE_STAINED_GLASS,
            Material.GRAY_STAINED_GLASS,
            Material.BLUE_STAINED_GLASS,
            Material.OAK_SIGN,
            Material.OAK_WALL_SIGN
        )

        /**
         * Piston materials used by the bracket system — auto-placed by NodeSelectionGUI.
         * Players must never be able to break these manually.
         */
        private val PISTON_MATERIALS = setOf(
            Material.STICKY_PISTON,
            Material.PISTON
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

        // For non-whitelisted materials: check synchronously if player is in DEV mode
        // and cancel immediately to prevent block placement (no dupes, no ghost blocks).
        if (!isWhitelisted(material)) {
            // Synchronous lookup — safe on main thread (in-memory ConcurrentHashMap)
            val plot = plotManager.getPlayerPlotSync(player.uniqueId) ?: return
            if (modeManager.getCurrentMode(player, plot) == PlotMode.DEV) {
                event.isCancelled = true
                player.sendMessage("§c[OCP] Нельзя разместить §e${material.name}§c в DEV-режиме. " +
                    "Разрешены только блоки кодирования.")
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
            event.isCancelled = true
            player.sendMessage("§c[OCP] Нельзя сломать стеклянную полосу в DEV-режиме.")
            return
        }

        // Pistons are auto-placed brackets — players must never break them
        if (material in PISTON_MATERIALS) {
            event.isCancelled = true
            player.sendMessage("§c[OCP] Поршни расставляются автоматически. Удалите блок условия, чтобы убрать скобки.")
            return
        }

        if (!isWhitelisted(material)) {
            // Synchronous check — cancel immediately to prevent block break and item drops.
            // No dupes, no ghost blocks, no autoclicker exploitation.
            val plot = plotManager.getPlayerPlotSync(player.uniqueId) ?: return
            if (modeManager.getCurrentMode(player, plot) == PlotMode.DEV) {
                event.isCancelled = true
                player.sendMessage("§c[OCP] Нельзя сломать §e${material.name}§c в DEV-режиме. " +
                    "Разрешены только блоки кодирования.")
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
     *  - the piston bracket structure to the east (STICKY_PISTON → glass → PISTON)
     *  - the adjacent sign on the nearest horizontal face
     *
     * Req 12.10
     */
    private fun cascadeBreakAttachments(categoryBlock: Block) {
        // Remove piston bracket if this is a conditional/loop block
        val glassBelow = categoryBlock.getRelative(BlockFace.DOWN)
        if (glassBelow.type in setOf(Material.BLUE_STAINED_GLASS, Material.WHITE_STAINED_GLASS, Material.GRAY_STAINED_GLASS)) {
            val glassOpen = glassBelow.getRelative(BlockFace.EAST)
            val aboveOpen = glassOpen.getRelative(BlockFace.UP)
            if (aboveOpen.type == Material.STICKY_PISTON) {
                aboveOpen.type = Material.AIR
                val glassBody = glassOpen.getRelative(BlockFace.EAST)
                val glassClose = glassBody.getRelative(BlockFace.EAST)
                val aboveClose = glassClose.getRelative(BlockFace.UP)
                if (aboveClose.type == Material.PISTON) aboveClose.type = Material.AIR
            }
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
}
