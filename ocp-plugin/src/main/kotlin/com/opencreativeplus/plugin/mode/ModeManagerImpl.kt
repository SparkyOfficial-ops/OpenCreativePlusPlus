package com.opencreativeplus.plugin.mode

import com.opencreativeplus.api.plot.ModeManager
import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.core.execution.ExecutionEngine
import com.opencreativeplus.plugin.compiler.ASTCompiler
import com.opencreativeplus.plugin.compiler.CompilationResult
import com.opencreativeplus.plugin.event.EventDispatcher
import com.opencreativeplus.plugin.inventory.InventoryManager
import com.opencreativeplus.plugin.scanner.BlockScanner
import com.opencreativeplus.plugin.world.WorldManager
import org.bukkit.GameMode
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages transitions between BUILD, DEV, and PLAY modes for players on plots.
 *
 * On every mode switch:
 *  1. Save the player's current inventory for the old mode.
 *  2. Apply mode-specific setup.
 *  3. Restore the player's inventory for the new mode.
 *
 * Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 5.1, 5.3, 23.4, 23.5, 26.2
 */
class ModeManagerImpl(
    private val inventoryManager: InventoryManager,
    private val worldManager: WorldManager,
    private val blockScannerFactory: (plot: Plot) -> BlockScanner,
    private val astCompiler: ASTCompiler,
    private val eventDispatcher: EventDispatcher,
    private val executionEngine: ExecutionEngine
) : ModeManager {

    /** "$playerId:$plotId" → current PlotMode */
    private val currentModes = ConcurrentHashMap<String, PlotMode>()

    // -------------------------------------------------------------------------
    // ModeManager interface
    // -------------------------------------------------------------------------

    override suspend fun switchMode(player: Player, plot: Plot, mode: PlotMode) {
        val oldMode = getCurrentMode(player, plot)
        if (oldMode == mode) return

        // 1. Save inventory for the mode we are leaving (req 2.8, 14.5)
        inventoryManager.saveInventory(player, plot.id, oldMode)

        // 2. Cleanup for the mode we are leaving
        onModeExit(player, plot, oldMode)

        // 3. Apply new mode setup — returns false if the switch was aborted (e.g. compilation failure)
        val switched = onModeEnter(player, plot, mode)
        if (!switched) return

        // 4. Restore inventory for the new mode (req 14.2, 14.3, 14.4)
        inventoryManager.loadInventory(player, plot.id, mode)

        // 5. Persist current mode
        currentModes[modeKey(player.uniqueId, plot.id)] = mode
    }

    override fun getCurrentMode(player: Player, plot: Plot): PlotMode =
        currentModes[modeKey(player.uniqueId, plot.id)] ?: PlotMode.BUILD

    // -------------------------------------------------------------------------
    // Mode exit cleanup
    // -------------------------------------------------------------------------

    /**
     * Perform cleanup when leaving [mode].
     * Requirements: 2.8, 2.9, 5.5, 26.2
     */
    private fun onModeExit(player: Player, plot: Plot, mode: PlotMode) {
        when (mode) {
            PlotMode.PLAY -> {
                // Cancel all running coroutines for this plot (req 26.2)
                executionEngine.cancelAllExecutions(plot.id)
                // Unregister scripts so no stale handlers remain
                eventDispatcher.unregisterScripts(plot.id)
            }
            PlotMode.DEV -> {
                // Nothing extra — inventory already saved above
            }
            PlotMode.BUILD -> {
                // Disable flight that was granted in BUILD mode
                player.allowFlight = false
                player.isFlying = false
            }
        }
    }

    // -------------------------------------------------------------------------
    // Mode enter setup
    // -------------------------------------------------------------------------

    /**
     * Apply mode-specific setup when entering [mode].
     * Returns true if the switch should proceed, false if it was aborted.
     * Requirements: 2.4, 2.5, 2.6, 2.7, 3.1
     */
    private suspend fun onModeEnter(player: Player, plot: Plot, mode: PlotMode): Boolean {
        return when (mode) {
            PlotMode.BUILD -> { applyBuildMode(player); true }
            PlotMode.DEV   -> { applyDevMode(player, plot); true }
            PlotMode.PLAY  -> applyPlayMode(player, plot)
        }
    }

    /**
     * BUILD mode: creative gamemode + flight, scripts disabled (req 2.4, 2.5).
     */
    private fun applyBuildMode(player: Player) {
        player.gameMode = GameMode.CREATIVE
        player.allowFlight = true
        player.isFlying = true
    }

    /**
     * DEV mode: teleport to dev world coding zone, provision coding inventory (req 2.7, 3.1).
     */
    private suspend fun applyDevMode(player: Player, plot: Plot) {
        val worlds = worldManager.getLoadedWorlds(plot.id)
        if (worlds != null) {
            val devWorld = worlds.second
            // Teleport to the coding zone spawn point
            player.teleport(devWorld.spawnLocation)
        }
        // Provision the coding blocks inventory (req 36.1–36.4)
        inventoryManager.provisionDevInventory(player)
    }

    /**
     * PLAY mode: scan blocks → compile → register scripts → enable execution (req 2.6, 5.1, 5.3, 23.4, 23.5).
     * Returns true on success, false if compilation failed and the switch was aborted.
     */
    private suspend fun applyPlayMode(player: Player, plot: Plot): Boolean {
        val scanner = blockScannerFactory(plot)
        val codeLines = scanner.scanCodingZone()
        val result: CompilationResult = astCompiler.compile(codeLines)

        if (result.errors.isNotEmpty()) {
            // Notify player of compilation errors and abort mode switch (req 23.4, 23.5)
            player.sendMessage("§c[OCP] Compilation failed with ${result.errors.size} error(s):")
            result.errors.take(5).forEach { err ->
                player.sendMessage("§c  - ${err.location}: ${err.message}")
            }
            return false
        }

        // Register compiled scripts with the event dispatcher (req 16.1, 16.2)
        eventDispatcher.registerScripts(plot.id, result.scripts)

        player.sendMessage("§a[OCP] ${result.scripts.size} script(s) compiled and active.")
        return true
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun modeKey(playerId: UUID, plotId: UUID) = "$playerId:$plotId"
}
