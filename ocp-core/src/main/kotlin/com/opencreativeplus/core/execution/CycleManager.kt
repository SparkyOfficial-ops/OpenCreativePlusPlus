package com.opencreativeplus.core.execution

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Represents a single cycle entry point — an EMERALD_BLOCK that runs its code chain
 * on a repeating BukkitScheduler task.
 *
 * Requirements: 4.1, 4.2
 *
 * @param locationKey   String key in the form "world:x:y:z" identifying the block.
 * @param plotId        The UUID of the plot this cycle belongs to.
 * @param intervalTicks The tick interval between executions (default 20). Requirements: 4.2
 * @param execute       The suspend lambda to run on each tick.
 */
class CycleEntry(
    val locationKey: String,        // "world:x:y:z"
    val plotId: UUID,
    val intervalTicks: Long = 20L,  // default 20 ticks (Req 4.2)
    val execute: suspend () -> Unit
)

/**
 * Manages repeating BukkitScheduler tasks for EMERALD_BLOCK cycle entry points.
 *
 * Lifecycle:
 * - [register]: registers a [CycleEntry] and starts its repeating task.
 * - [unregisterByLocation]: cancels and removes the task for a specific location key.
 * - [unregisterAll]: cancels and removes all tasks for a given plot (called on PLAY → stop).
 *
 * Skip-if-running (Req 4.4):
 * Each cycle tracks an [AtomicBoolean] running flag. If the previous tick is still executing
 * when the next tick fires, the tick is skipped and a warning is logged.
 *
 * Requirements: 4.1, 4.2, 4.3, 4.4, 4.5
 */
class CycleManager(
    private val plugin: Plugin,
    private val scope: CoroutineScope,
    private val logger: Logger = Logger.getLogger("CycleManager")
) {

    /** Active BukkitTasks keyed by locationKey. */
    private val tasks = ConcurrentHashMap<String, BukkitTask>()

    /** Per-cycle running flags for skip-if-running logic (Req 4.4). */
    private val runningFlags = ConcurrentHashMap<String, AtomicBoolean>()

    /** Maps locationKey → plotId for bulk unregistration by plot. */
    private val locationToPlot = ConcurrentHashMap<String, UUID>()

    /**
     * Registers a [CycleEntry] and starts its repeating BukkitScheduler task.
     *
     * If a task is already registered for [entry.locationKey], it is cancelled first
     * before the new one is started.
     *
     * Requirements: 4.1, 4.2
     */
    fun register(entry: CycleEntry) {
        // Cancel any existing task for this location
        tasks.remove(entry.locationKey)?.cancel()
        runningFlags.remove(entry.locationKey)

        val running = AtomicBoolean(false)
        runningFlags[entry.locationKey] = running
        locationToPlot[entry.locationKey] = entry.plotId

        val task = plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable {
                // Req 4.4: skip tick if previous execution is still running
                if (!running.compareAndSet(false, true)) {
                    logger.warning(
                        "[OCP] CycleManager: skipping tick for ${entry.locationKey} — previous tick still running"
                    )
                    return@Runnable
                }
                try {
                    scope.launch {
                        try {
                            entry.execute()
                        } finally {
                            running.set(false)
                        }
                    }
                } catch (e: Exception) {
                    running.set(false)
                    logger.warning(
                        "[OCP] CycleManager: error launching cycle at ${entry.locationKey}: ${e.message}"
                    )
                }
            },
            0L,
            entry.intervalTicks
        )

        tasks[entry.locationKey] = task
    }

    /**
     * Cancels and removes the cycle task for the given [locationKey].
     * No-op if no task is registered for that key.
     *
     * Requirements: 4.3
     */
    fun unregisterByLocation(locationKey: String) {
        tasks.remove(locationKey)?.cancel()
        runningFlags.remove(locationKey)
        locationToPlot.remove(locationKey)
    }

    /**
     * Cancels and removes all cycle tasks belonging to [plotId].
     * Called when the plot transitions out of PLAY mode.
     *
     * Requirements: 4.5
     */
    fun unregisterAll(plotId: UUID) {
        val keysToRemove = locationToPlot.entries
            .filter { it.value == plotId }
            .map { it.key }

        for (key in keysToRemove) {
            tasks.remove(key)?.cancel()
            runningFlags.remove(key)
            locationToPlot.remove(key)
        }
    }
}
