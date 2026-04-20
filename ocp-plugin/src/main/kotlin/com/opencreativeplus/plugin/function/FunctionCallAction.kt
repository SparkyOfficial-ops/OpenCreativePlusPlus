package com.opencreativeplus.plugin.function

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction

/**
 * Action node that calls a named custom function.
 *
 * Creates a new local scope for the function, passes parameters,
 * and detects recursion exceeding [MAX_DEPTH].
 *
 * The call depth counter is stored in [ExecutionContext.callStackSize] (an AtomicInteger)
 * rather than a ThreadLocal, so it survives coroutine thread switches (Bug 3 fix).
 *
 15.3, 15.4, 15.5
 */
class FunctionCallAction(
    private val functionName: String,
    private val arguments: Map<String, Any>,
    private val registry: FunctionRegistry
) : IAction {

    override val nodeId = "function_call"
    override val displayName = "Function Call"

    companion object {
        const val MAX_DEPTH = 100
    }

    override suspend fun execute(context: ExecutionContext) {
        val depth = context.callStackSize.incrementAndGet()
        try {
            if (depth > MAX_DEPTH) {
                throw StackOverflowError("Function call depth exceeded $MAX_DEPTH for '$functionName'")
            }

            val definition = registry.get(context.plotId, functionName)
                ?: throw IllegalArgumentException("Unknown function: $functionName")

            // Set parameters in local scope before executing function body
            definition.parameterNames.forEach { paramName ->
                arguments[paramName]?.let { context.localScope.set(paramName, it) }
            }

            for (action in definition.actions) {
                action.execute(context)
            }
        } finally {
            context.callStackSize.decrementAndGet()
        }
    }
}
