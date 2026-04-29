package com.opencreativeplus.plugin.mode

import com.opencreativeplus.api.plot.ModeManager
import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.core.execution.ExecutionEngine
import com.opencreativeplus.plugin.compiler.BytecodeCompiler
import com.opencreativeplus.plugin.compiler.ASTCompiler
import com.opencreativeplus.plugin.compiler.CompilationResult
import com.opencreativeplus.plugin.event.EventDispatcher
import com.opencreativeplus.plugin.inventory.InventoryManager
import com.opencreativeplus.plugin.scanner.BlockScanner
import com.opencreativeplus.plugin.visualizer.DevVisualizer
import com.opencreativeplus.plugin.visualizer.HologramReporter
import com.opencreativeplus.plugin.world.WorldManager
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages transitions between BUILD, DEV, and PLAY modes for players on plots.
 *
 * On every mode switch:
 *  1. Save the player's current inventory for the old mode.
 *  2. Apply mode-specific setup.
 *  3. Restore the player's inventory for the new mode.
 *
 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 5.1, 5.3, 23.4, 23.5, 26.2
 */
class ModeManagerImpl(
    private val inventoryManager: InventoryManager,
    private val worldManager: WorldManager,
    private val blockScannerFactory: (plot: Plot) -> BlockScanner,
    private val astCompiler: ASTCompiler,
    private val eventDispatcher: EventDispatcher,
    private val executionEngine: ExecutionEngine,
    private val plugin: Plugin = Bukkit.getPluginManager().getPlugin("OpenCreativePlus")
        ?: error("OpenCreativePlus plugin not found"),
    private val devVisualizer: DevVisualizer? = null,
    private val hologramReporter: HologramReporter? = null,
    private val bytecodeCompiler: BytecodeCompiler? = null
) : ModeManager {

    /** "$playerId:$plotId" → current PlotMode */
    private val currentModes = ConcurrentHashMap<String, PlotMode>()

    // -------------------------------------------------------------------------
    // ModeManager interface
    // -------------------------------------------------------------------------

    override suspend fun switchMode(player: Player, plot: Plot, mode: PlotMode) {
        val oldMode = getCurrentMode(player, plot)
        if (oldMode == mode) return

        // All Bukkit API calls (inventory, teleport, gameMode) must run on the main thread.
        // We wrap the entire switch sequence in runOnMain, using a nested suspend bridge.
        runOnMain {
            // 1. Save inventory snapshot synchronously (we are on main thread here)
            val contents = player.inventory.contents.clone()
            val armor = player.inventory.armorContents.clone()
            val offhand = player.inventory.itemInOffHand.clone()
            Triple(contents, armor, offhand)
        }.let { (contents, armor, offhand) ->
            // Persist snapshot to DB (IO, off main thread is fine)
            inventoryManager.saveInventorySnapshot(player, plot.id, oldMode, contents, armor, offhand)
        }

        // 2. Cleanup for the mode we are leaving
        onModeExit(player, plot, oldMode)

        // 3. Apply new mode setup
        val switched = onModeEnter(player, plot, mode)
        if (!switched) return

        // 4. Load inventory from DB, then apply on main thread
        val doc = inventoryManager.fetchInventoryDoc(player, plot.id, mode)
        runOnMain { inventoryManager.applyInventoryDoc(player, doc) }

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
     2.8, 2.9, 5.5, 26.2
     */
    private suspend fun onModeExit(player: Player, plot: Plot, mode: PlotMode) {
        when (mode) {
            PlotMode.PLAY -> {
                // Cancel all running coroutines for this plot (req 26.2)
                executionEngine.cancelAllExecutions(plot.id)
                // Unregister scripts so no stale handlers remain
                eventDispatcher.unregisterScripts(plot.id)
                // Req 13.4: invalidate compiled cache when switching to dev mode
                bytecodeCompiler?.invalidate(plot.id)
            }
            PlotMode.DEV -> {
                // Stop particle rendering for this player (req 9.2)
                devVisualizer?.stopFor(player)
                // Hide error holograms when leaving dev mode (Req 12.7)
                hologramReporter?.hideFromPlayer(player)
                // Hide all arg holograms when leaving dev mode (Req 9.4)
                hologramReporter?.hideArgHolograms(player)
            }
            PlotMode.BUILD -> {
                // Disable flight that was granted in BUILD mode
                runOnMain {
                    player.allowFlight = false
                    player.isFlying = false
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Mode enter setup
    // -------------------------------------------------------------------------

    /**
     * Apply mode-specific setup when entering [mode].
     * Returns true if the switch should proceed, false if it was aborted.
     2.4, 2.5, 2.6, 2.7, 3.1
     */
    private suspend fun onModeEnter(player: Player, plot: Plot, mode: PlotMode): Boolean {
        return when (mode) {
            PlotMode.BUILD -> { applyBuildMode(player, plot); true }
            PlotMode.DEV   -> { applyDevMode(player, plot); true }
            PlotMode.PLAY  -> applyPlayMode(player, plot)
        }
    }

    /**
     * BUILD mode: teleport to main world, creative gamemode + flight (req 2.4, 2.5).
     */
    private suspend fun applyBuildMode(player: Player, plot: Plot) {
        val mainWorld = worldManager.getLoadedWorlds(plot.id)?.first
        runOnMain {
            resetPlayerState(player)
            if (mainWorld != null) player.teleport(mainWorld.spawnLocation)
            player.gameMode = GameMode.CREATIVE
            player.allowFlight = true
            player.isFlying = true
        }
    }

    /**
     * DEV mode: teleport to dev world coding zone, provision coding inventory (req 2.7, 3.1).
     */
    private suspend fun applyDevMode(player: Player, plot: Plot) {
        val worlds = worldManager.getLoadedWorlds(plot.id)
        if (worlds != null) {
            val spawnLoc: Location = worlds.second.spawnLocation
            runOnMain {
                resetPlayerState(player)
                player.gameMode = GameMode.CREATIVE
                player.allowFlight = true
                player.teleport(spawnLoc)
            }
        } else {
            runOnMain {
                resetPlayerState(player)
                player.gameMode = GameMode.CREATIVE
            }
        }
        // Provision the coding blocks inventory (req 36.1–36.4)
        runOnMain {
            inventoryManager.provisionDevInventory(player)
            player.sendMessage("§a[OCP] DEV mode — place blocks on the blue glass strips to code.")
        }
        // Start particle visualization for this player (req 9.1)
        val scanner = blockScannerFactory(plot)
        val codeLines = scanner.scanCodingZone()
        devVisualizer?.startFor(player, codeLines)
        // Show active error holograms to the player entering dev mode (Req 12.6)
        hologramReporter?.showToPlayer(player)
    }

    /**
     * PLAY mode: scan blocks → compile → register scripts → enable execution (req 2.6, 5.1, 5.3, 23.4, 23.5).
     * Returns true on success, false if compilation failed and the switch was aborted.
     */
    private suspend fun applyPlayMode(player: Player, plot: Plot): Boolean {
        runOnMain { resetPlayerState(player) }
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

        // Req 13.5: schedule pre-compilation on auxiliary thread when entering play mode
        bytecodeCompiler?.scheduleCompile(plot.id)

        // Teleport to main world and set survival mode
        val mainWorld = worldManager.getLoadedWorlds(plot.id)?.first
        runOnMain {
            if (mainWorld != null) player.teleport(mainWorld.spawnLocation)
            player.gameMode = GameMode.SURVIVAL
            player.sendMessage("§a[OCP] ${result.scripts.size} script(s) compiled and active.")
        }
        return true
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resets transient player state when entering DEV or BUILD mode (req 12.1–12.5).
     * Must be called on the main thread.
     */
    private fun resetPlayerState(player: Player) {
        player.activePotionEffects.toList().forEach { player.removePotionEffect(it.type) }
        player.fireTicks = 0
        val maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0
        player.health = maxHealth
        player.foodLevel = 20
        player.velocity = org.bukkit.util.Vector(0.0, 0.0, 0.0)
        player.fallDistance = 0f
    }

    private fun modeKey(playerId: UUID, plotId: UUID) = "$playerId:$plotId"

    /** Runs [block] on the Bukkit main thread and suspends until it completes. */
    private suspend fun <T> runOnMain(block: () -> T): T =
        suspendCancellableCoroutine { cont ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                try { cont.resume(block()) }
                catch (e: Exception) { cont.resumeWithException(e) }
            })
        }
}
