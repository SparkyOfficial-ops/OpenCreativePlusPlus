package com.opencreativeplus.plugin.node.loop

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.core.watchdog.Watchdog
import com.opencreativeplus.core.watchdog.WatchdogException

/**
 * Throws [WatchdogException] if the execution has reached the global operation limit.
 * Called on every loop iteration so runaway loops (e.g. Repeat 100000) are stopped
 * immediately at [Watchdog.MAX_OPERATIONS] instead of running to completion.
 */
private fun checkOperationLimit(context: ExecutionContext) {
    if (context.operationCount.get() >= Watchdog.MAX_OPERATIONS) {
        throw WatchdogException(
            "Operation limit exceeded (${Watchdog.MAX_OPERATIONS}) — loop terminated"
        )
    }
}

/**
 * Iterates over a list variable, setting a loop variable in localScope for each element.
 * Each iteration counts toward the watchdog operation limit; exceeding it throws
 * [WatchdogException] which the ExecutionEngine reports to the plot owner.
 *
 * Params:
 *   - "list": String — name of the localScope variable holding the list
 *   - "var": String — name of the loop variable to set per iteration (default: "item")
 *   - "body": List<IAction> — actions to execute per iteration
 */
class ForEachNode(params: Map<String, Any>) : IAction {
    override val nodeId = "foreach"
    override val displayName = "For Each"

    private val listVar: String = params["list"] as? String ?: error("list param required")
    private val loopVar: String = params["var"] as? String ?: "item"
    private val body: List<IAction> = @Suppress("UNCHECKED_CAST") (params["body"] as? List<IAction> ?: emptyList())

    override suspend fun execute(context: ExecutionContext) {
        val list = context.localScope.get(listVar) as? List<*> ?: return
        for (element in list) {
            checkOperationLimit(context)
            val value = element ?: continue
            context.localScope.set(loopVar, value)
            context.operationCount.incrementAndGet()
            body.forEach { it.execute(context) }
        }
    }
}

/**
 * Executes the body N times, setting a 0-based index variable in localScope each iteration.
 * Each iteration counts toward the watchdog operation limit; exceeding it throws
 * [WatchdogException] which the ExecutionEngine reports to the plot owner.
 *
 * Params:
 *   - "count": Int — number of times to repeat (default: 1)
 *   - "index_var": String — name of the index variable (default: "i")
 *   - "body": List<IAction> — actions to execute per iteration
 */
class RepeatNode(params: Map<String, Any>) : IAction {
    override val nodeId = "repeat"
    override val displayName = "Repeat"

    private val count: Int = params["count"] as? Int ?: 1
    private val indexVar: String = params["index_var"] as? String ?: "i"
    private val body: List<IAction> = @Suppress("UNCHECKED_CAST") (params["body"] as? List<IAction> ?: emptyList())

    override suspend fun execute(context: ExecutionContext) {
        for (i in 0 until count) {
            checkOperationLimit(context)
            context.localScope.set(indexVar, i)
            context.operationCount.incrementAndGet()
            body.forEach { it.execute(context) }
        }
    }
}
