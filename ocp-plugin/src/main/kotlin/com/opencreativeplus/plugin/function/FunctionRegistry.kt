package com.opencreativeplus.plugin.function

import com.opencreativeplus.api.node.IAction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores custom function definitions per plot.
 * A function is a named list of actions with optional parameter names.
 *
 * Requirements: 15.1, 15.2
 */
class FunctionRegistry {

    data class FunctionDefinition(
        val name: String,
        val parameterNames: List<String>,
        val actions: List<IAction>
    )

    /** plotId → (functionName → definition) */
    private val functions = ConcurrentHashMap<UUID, ConcurrentHashMap<String, FunctionDefinition>>()

    /**
     * Register a function for a plot.
     * Requirements: 15.1, 15.2
     */
    fun register(plotId: UUID, definition: FunctionDefinition) {
        functions.getOrPut(plotId) { ConcurrentHashMap() }[definition.name] = definition
    }

    /**
     * Look up a function by name for a plot.
     */
    fun get(plotId: UUID, name: String): FunctionDefinition? =
        functions[plotId]?.get(name)

    /**
     * Remove all functions for a plot (called on plot unload).
     */
    fun clearPlot(plotId: UUID) {
        functions.remove(plotId)
    }
}
