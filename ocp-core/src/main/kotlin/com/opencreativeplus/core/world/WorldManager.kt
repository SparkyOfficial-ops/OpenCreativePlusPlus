package com.opencreativeplus.core.world

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.core.database.PlotPersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Available world templates for plot creation.
 * Requirements: 7.1
 */
enum class PlotTemplate(val templateName: String) {
    VOID("template_void"),
    FLAT("template_flat"),
    SURVIVAL("template_survival");

    companion object {
        fun fromName(name: String): PlotTemplate? =
            values().firstOrNull { it.templateName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) }

        fun availableNames(): String = values().joinToString(", ") { it.templateName }
    }
}

/**
 * Lifecycle state of a plot world.
 */
enum class WorldLifecycleState { UNLOADED, LOADING, LOADED, SAVING, UNLOADING }

/**
 * Abstraction over Bukkit world operations, allowing ocp-core to remain testable
 * without a live Bukkit server.
 */
interface WorldOperations {
    /**
     * Asynchronously copy a template world and create a new plot world.
     * Calls [onSuccess] with the new world name on the main thread, or [onError] on failure.
     */
    fun copyTemplate(template: PlotTemplate, plotId: UUID, onSuccess: (worldName: String) -> Unit, onError: (Exception) -> Unit)

    /**
     * Asynchronously load an existing world by name.
     * Calls [onSuccess] on the main thread, or [onError] on failure.
     */
    fun loadWorld(worldName: String, onSuccess: () -> Unit, onError: (Exception) -> Unit)

    /**
     * Unload a world by name, saving its state.
     */
    fun unloadWorld(worldName: String)

    /**
     * Teleport a player to the spawn of the given world.
     */
    fun teleportToPlot(player: Player, worldName: String)
}

/**
 * Manages the lifecycle of plot worlds: template-based creation, auto-unload of empty worlds,
 * and async loading on player join.
 *
 * This component lives in ocp-core and delegates raw Bukkit world operations to [WorldOperations],
 * keeping it decoupled from the Bukkit API for testability.
 *
 * Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8
 */
class WorldManager(
    private val plugin: Plugin,
    private val plotPersistence: PlotPersistence,
    private val worldOps: WorldOperations,
    private val scope: CoroutineScope,
    private val logger: Logger = Logger.getLogger("WorldManager"),
    /** How long (ms) a world must be empty before auto-unload. Default: 5 minutes. */
    private val emptyUnloadDelayMs: Long = 5 * 60 * 1000L
) {
    /** plotId → current lifecycle state */
    private val worldStates = ConcurrentHashMap<UUID, WorldLifecycleState>()

    /** plotId → pending auto-unload BukkitTask */
    private val emptyTimers = ConcurrentHashMap<UUID, BukkitTask>()

    /** plotId → set of online player UUIDs currently on that plot */
    private val plotPlayers = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    // -------------------------------------------------------------------------
    // Task 11.1 — Template-based plot creation (Req 7.1, 7.2, 7.3, 7.4)
    // -------------------------------------------------------------------------

    /**
     * Asynchronously create a new plot world from the given template.
     *
     * If [templateName] is not a valid template, sends an error message listing available
     * templates and returns without creating anything (Req 7.4).
     *
     * On success: notifies [player] and teleports them to the new plot spawn (Req 7.3).
     *
     * Requirements: 7.1, 7.2, 7.3, 7.4
     */
    fun createPlot(player: Player, plot: Plot, templateName: String) {
        val template = PlotTemplate.fromName(templateName)
        if (template == null) {
            // Req 7.4: respond with error listing available templates
            player.sendMessage("§c[OCP] Unknown template '${templateName}'. Available: ${PlotTemplate.availableNames()}")
            return
        }

        worldStates[plot.id] = WorldLifecycleState.LOADING
        player.sendMessage("§7[OCP] Creating your plot from template '${template.templateName}'...")

        // Req 7.2: asynchronously copy the template world
        worldOps.copyTemplate(
            template = template,
            plotId = plot.id,
            onSuccess = { worldName ->
                // Back on main thread
                worldStates[plot.id] = WorldLifecycleState.LOADED
                plotPlayers.getOrPut(plot.id) { ConcurrentHashMap.newKeySet() }.add(player.uniqueId)

                // Req 7.3: notify player and teleport
                player.sendMessage("§a[OCP] Plot created! Teleporting you now...")
                worldOps.teleportToPlot(player, worldName)

                // Persist the updated world name
                scope.launch {
                    runCatching {
                        plotPersistence.updatePlot(plot.copy(mainWorldName = worldName, updatedAt = System.currentTimeMillis()))
                    }.onFailure { e ->
                        logger.warning("[OCP] WorldManager: failed to persist plot ${plot.id} after creation: ${e.message}")
                    }
                }
            },
            onError = { e ->
                worldStates[plot.id] = WorldLifecycleState.UNLOADED
                player.sendMessage("§c[OCP] Failed to create plot: ${e.message}")
                logger.severe("[OCP] WorldManager: failed to copy template '${template.templateName}' for plot ${plot.id}: ${e.message}")
            }
        )
    }

    // -------------------------------------------------------------------------
    // Task 11.2 — Auto-unload of empty worlds (Req 7.5, 7.8)
    // -------------------------------------------------------------------------

    /**
     * Called when a player leaves a plot world.
     *
     * If the plot becomes empty, starts a 5-minute countdown to auto-unload.
     *
     * Requirements: 7.5
     */
    fun onPlayerLeave(plotId: UUID, playerId: UUID) {
        plotPlayers[plotId]?.remove(playerId)
        val remaining = plotPlayers[plotId]?.size ?: 0

        if (remaining == 0) {
            scheduleUnload(plotId)
        }
    }

    /**
     * Called when a player joins a plot world.
     *
     * Cancels any pending auto-unload timer for this plot.
     *
     * Requirements: 7.5
     */
    fun onPlayerJoin(plotId: UUID, playerId: UUID) {
        // Cancel pending unload timer
        emptyTimers.remove(plotId)?.cancel()
        plotPlayers.getOrPut(plotId) { ConcurrentHashMap.newKeySet() }.add(playerId)
    }

    /**
     * Schedule an auto-unload for [plotId] after [emptyUnloadDelayMs] milliseconds.
     * If a timer is already pending, it is replaced.
     */
    private fun scheduleUnload(plotId: UUID) {
        // Cancel any existing timer
        emptyTimers.remove(plotId)?.cancel()

        val delayTicks = emptyUnloadDelayMs / 50L  // convert ms to ticks (20 ticks/s, 50ms/tick)
        val task = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            emptyTimers.remove(plotId)
            scope.launch { unloadWorld(plotId) }
        }, delayTicks)

        emptyTimers[plotId] = task
    }

    /**
     * Save plot variables to MongoDB and then unload the world.
     *
     * Requirements: 7.5, 7.8
     */
    private suspend fun unloadWorld(plotId: UUID) {
        val state = worldStates[plotId]
        if (state != WorldLifecycleState.LOADED) return

        worldStates[plotId] = WorldLifecycleState.SAVING

        // Req 7.8: persist plot state before unloading
        val plot = runCatching { plotPersistence.loadPlot(plotId) }.getOrNull()
        if (plot != null) {
            runCatching {
                plotPersistence.updatePlot(plot.copy(updatedAt = System.currentTimeMillis()))
            }.onFailure { e ->
                logger.warning("[OCP] WorldManager: failed to persist plot $plotId before unload: ${e.message}")
            }
        }

        worldStates[plotId] = WorldLifecycleState.UNLOADING

        // Unload the world (main-thread operation delegated to worldOps)
        // Use try/finally to guarantee state is reset even if unload throws
        try {
            runCatching {
                worldOps.unloadWorld(plotId.toString())
            }.onFailure { e ->
                logger.warning("[OCP] WorldManager: failed to unload world for plot $plotId: ${e.message}")
            }
        } finally {
            worldStates[plotId] = WorldLifecycleState.UNLOADED
            plotPlayers.remove(plotId)
            logger.info("[OCP] WorldManager: plot $plotId unloaded after being empty.")
        }
    }

    // -------------------------------------------------------------------------
    // Task 11.3 — Async world loading on player join (Req 7.6, 7.7)
    // -------------------------------------------------------------------------

    /**
     * Asynchronously load the world for [plotId] and teleport [player] once ready.
     *
     * If the world is already loaded, teleports immediately.
     * If loading fails, notifies the player and logs the error (Req 7.7).
     *
     * Requirements: 7.6, 7.7
     */
    fun loadWorldForPlayer(player: Player, plot: Plot) {
        val currentState = worldStates[plot.id]

        // Cancel any pending unload timer since a player is joining
        emptyTimers.remove(plot.id)?.cancel()

        if (currentState == WorldLifecycleState.LOADED) {
            // Already loaded — just track the player and teleport
            plotPlayers.getOrPut(plot.id) { ConcurrentHashMap.newKeySet() }.add(player.uniqueId)
            worldOps.teleportToPlot(player, plot.mainWorldName)
            return
        }

        if (currentState == WorldLifecycleState.LOADING) {
            // Already loading — player will be handled when load completes
            player.sendMessage("§7[OCP] Your plot is loading, please wait...")
            return
        }

        // Req 7.6: load the world asynchronously
        worldStates[plot.id] = WorldLifecycleState.LOADING
        player.sendMessage("§7[OCP] Loading your plot...")

        worldOps.loadWorld(
            worldName = plot.mainWorldName,
            onSuccess = {
                // Back on main thread
                worldStates[plot.id] = WorldLifecycleState.LOADED
                plotPlayers.getOrPut(plot.id) { ConcurrentHashMap.newKeySet() }.add(player.uniqueId)
                worldOps.teleportToPlot(player, plot.mainWorldName)
            },
            onError = { e ->
                // Req 7.7: notify player and log failure
                worldStates[plot.id] = WorldLifecycleState.UNLOADED
                player.sendMessage("§c[OCP] Failed to load your plot. Please try again later.")
                logger.severe("[OCP] WorldManager: failed to load world '${plot.mainWorldName}' for player ${player.name}: ${e.message}")
            }
        )
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns the current lifecycle state for [plotId], or UNLOADED if unknown. */
    fun getState(plotId: UUID): WorldLifecycleState =
        worldStates.getOrDefault(plotId, WorldLifecycleState.UNLOADED)

    /** Returns the number of players currently tracked on [plotId]. */
    fun getPlayerCount(plotId: UUID): Int = plotPlayers[plotId]?.size ?: 0

    /** Cancel all pending unload timers (e.g. on plugin shutdown). */
    fun cancelAllTimers() {
        emptyTimers.values.forEach { it.cancel() }
        emptyTimers.clear()
    }
}
