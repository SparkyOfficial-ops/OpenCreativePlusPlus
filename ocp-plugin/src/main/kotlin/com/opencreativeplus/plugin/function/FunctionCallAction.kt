package com.opencreativeplus.plugin.function

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.core.execution.ExecutionContextImpl

/**
 * Action node that calls a named custom function.
 *
 * Creates a new local scope for the function, passes parameters,
 * and detects recursion exceeding [MAX_DEPTH].
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
        private val callDepth = ThreadLocal.withInitial { 0 }
    }

    override suspend fun execute(context: ExecutionContext) {
        val depth = callDepth.get()
        if (depth >= MAX_DEPTH) {
            throw StackOverflowError("Function call depth exceeded $MAX_DEPTH for '$functionName'")
        }

        val definition = registry.get(context.plotId, functionName)
            ?: throw IllegalArgumentException("Unknown function: $functionName")

        callDepth.set(depth + 1)
        try {
            // Create a child local scope with the function's parameters
            val fnScope = context.localScope
            definition.parameterNames.forEach { paramName ->
                arguments[paramName]?.let { fnScope.set(paramName, it) }
            }

            for (action in definition.actions) {
                action.execute(context)
            }
        } finally {
            callDepth.set(depth)
        }
    }
}
