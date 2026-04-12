package com.opencreativeplus.plugin.node.action

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import kotlinx.coroutines.delay

/**
 * Action node that pauses script execution for a specified duration.
 * Represented by CLOCK in the coding grid.
 * Uses coroutine delay to suspend without blocking the main thread.
 7.3, 7.6
 */
class WaitAction(private val params: Map<String, Any>) : IAction {
    override val nodeId: String = "wait"
    override val displayName: String = "Wait"

    override suspend fun execute(context: ExecutionContext) {
        val durationTicks = when (val raw = params["duration"]) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: 20L
            else -> 20L
        }
        // Convert ticks to milliseconds (20 ticks = 1 second)
        delay(durationTicks * 50L)
    }
}
