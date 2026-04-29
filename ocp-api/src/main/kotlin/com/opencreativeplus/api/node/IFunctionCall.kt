package com.opencreativeplus.api.node

/**
 * Marker interface for function call action nodes.
 *
 * When the [ExecutionEngine] encounters an [IAction] that also implements [IFunctionCall],
 * it handles the call specially: looks up the function in [FunctionRegistry], creates an
 * isolated execution context, and enforces the call stack depth limit.
 *
 * Requirements: 5.3, 5.4, 5.5, 5.6
 */
interface IFunctionCall : IAction {
    /**
     * The name of the function to call.
     * Used by [ExecutionEngine] to look up the function in [FunctionRegistry].
     */
    val targetFunctionName: String
}
