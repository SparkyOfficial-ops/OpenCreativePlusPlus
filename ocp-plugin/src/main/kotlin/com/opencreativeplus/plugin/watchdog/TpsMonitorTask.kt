package com.opencreativeplus.plugin.watchdog

import com.opencreativeplus.core.watchdog.TPSMonitor
import com.opencreativeplus.core.watchdog.Watchdog
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Bukkit repeating task that drives [TPSMonitor] and enforces low-TPS warnings.
 *
 * - Calls [TPSMonitor.tick] every server tick (period = 1).
 * - Tracks how long TPS has been below [Watchdog.MIN_TPS].
 * - Logs a warning when TPS stays below the threshold for more than [LOW_TPS_WARNING_TICKS] ticks.
 * - Tracks which plots are actively executing scripts during TPS drops.
 *
 * Register via [start]; cancel via [stop].
 *
 34.1, 34.3, 34.4, 34.5
 */
class TpsMonitorTask(
    private val plugin: JavaPlugin,
    private val tpsMonitor: TPSMonitor,
    private val watchdog: Watchdog,
    private val logger: Logger = plugin.logger
) {

    /** Plots that are currently executing scripts (updated externally). */
    val activePlots: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    private var lowTpsTicks = 0
    private var taskId = -1

    /** Start the repeating task (1-tick period, 0-tick delay). */
    fun start() {
        val task = object : BukkitRunnable() {
            override fun run() {
                tpsMonitor.tick()
                checkLowTps()
            }
        }
        taskId = task.runTaskTimer(plugin, 0L, 1L).taskId
    }

    /** Cancel the repeating task. */
    fun stop() {
        if (taskId != -1) {
            plugin.server.scheduler.cancelTask(taskId)
            taskId = -1
        }
    }

    private fun checkLowTps() {
        val currentTps = tpsMonitor.getCurrentTPS()

        if (currentTps < Watchdog.MIN_TPS) {
            lowTpsTicks++

            if (lowTpsTicks >= LOW_TPS_WARNING_TICKS && lowTpsTicks % LOW_TPS_WARNING_TICKS == 0) {
                val seconds = lowTpsTicks / TICKS_PER_SECOND
                val plotList = if (activePlots.isEmpty()) "none" else activePlots.joinToString()
                logger.warning(
                    "[OCP Watchdog] TPS has been below ${Watchdog.MIN_TPS} for ${seconds}s " +
                    "(current: ${String.format("%.1f", currentTps)}). " +
                    "Active plots: $plotList"
                )
            }
        } else {
            lowTpsTicks = 0
        }
    }

    companion object {
        private const val TICKS_PER_SECOND = 20

        /** Number of consecutive low-TPS ticks before a warning is logged (5 seconds). */
        const val LOW_TPS_WARNING_TICKS = 5 * TICKS_PER_SECOND
    }
}
