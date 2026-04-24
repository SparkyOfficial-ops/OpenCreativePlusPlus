package com.opencreativeplus.plugin.node.action

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import org.bukkit.plugin.Plugin
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Action node that pauses script execution for a specified number of server ticks.
 * Uses BukkitScheduler.runTaskLater to schedule resumption on the main server thread,
 * ensuring tick-accurate timing regardless of wall-clock time.
 *
 * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5
 */
class WaitAction(
    private val params: Map<String, Any>,
    private val plugin: Plugin
) : IAction {
    override val nodeId: String = "wait"
    override val displayName: String = "Wait"

    override suspend fun execute(context: ExecutionContext) {
        val durationTicks = when (val raw = params["duration"]) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 20
            else -> 20
        }
        delayTicks(durationTicks)
    }

    /**
     * Suspends the coroutine for the given number of server ticks, resuming on the
     * main server thread via BukkitScheduler.
     *
     * @param ticks Number of ticks to wait. Must be >= 0.
     * @throws IllegalArgumentException if ticks is negative.
     */
    suspend fun delayTicks(ticks: Int) {
        require(ticks >= 0) {
            "delayTicks requires a non-negative tick count, but got $ticks"
        }
        suspendCoroutine { continuation ->
            val task = Runnable { continuation.resume(Unit) }
            if (ticks == 0) {
                // Resume on the next server tick without scheduling a delay
                plugin.server.scheduler.runTask(plugin, task)
            } else {
                plugin.server.scheduler.runTaskLater(plugin, task, ticks.toLong())
            }
        }
    }
}
