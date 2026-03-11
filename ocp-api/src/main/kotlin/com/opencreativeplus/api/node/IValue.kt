package com.opencreativeplus.api.node

import com.opencreativeplus.api.execution.ExecutionContext

/**
 * Interface for value nodes that compute and return data.
 * Values can be used for calculations, comparisons, and data operations.
 *
 * @param T The type of value this node computes
 */
interface IValue<T> : INode {
    /**
     * Compute and return the value within the given execution context.
     *
     * @param context The execution context containing variables and state
     * @return The computed value
     */
    suspend fun compute(context: ExecutionContext): T
}
