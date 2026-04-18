package com.opencreativeplus.plugin.node.loop

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction

private const val MAX_ITERATIONS = 1000

/**
 * Iterates over a list variable, setting a loop variable in localScope for each element.
 * Caps at 1000 iterations to prevent infinite loops.
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
        var iterations = 0
        for (element in list) {
            if (++iterations > MAX_ITERATIONS) {
                // Cap reached — stop iterating silently
                println("[OCP] ForEachNode: exceeded $MAX_ITERATIONS iterations, terminating loop")
                break
            }
            val value = element ?: continue
            context.localScope.set(loopVar, value)
            context.operationCount.incrementAndGet()
            body.forEach { it.execute(context) }
        }
    }
}

/**
 * Executes the body N times, setting a 0-based index variable in localScope each iteration.
 * Caps at 1000 iterations to prevent runaway loops.
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
        val effectiveCount = minOf(count, MAX_ITERATIONS)
        if (count > MAX_ITERATIONS) {
            println("[OCP] RepeatNode: count $count exceeds $MAX_ITERATIONS, capping iterations")
        }
        for (i in 0 until effectiveCount) {
            context.localScope.set(indexVar, i)
            context.operationCount.incrementAndGet()
            body.forEach { it.execute(context) }
        }
    }
}
