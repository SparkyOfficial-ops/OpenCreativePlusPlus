package com.opencreativeplus.core.execution

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for named function definitions in a plot's code.
 *
 * Functions are defined by LAPIS_BLOCK entry points in the physical world.
 * Each function is stored as a [CompiledScript] representing its code chain.
 *
 * Thread-safe via [ConcurrentHashMap] (Requirements 5.1, 5.2).
 *
 * Requirements: 5.1, 5.2
 */
class FunctionRegistry {

    /** functionName → CompiledScript for the function body */
    private val functions = ConcurrentHashMap<String, CompiledScript>()

    /**
     * Register a named function with its compiled script body.
     *
     * If a function with the same [name] already exists, it is overwritten.
     *
     * Requirements: 5.1, 5.2
     *
     * @param name     The function name (read from the first ParamChest slot of the LAPIS_BLOCK).
     * @param script   The compiled script representing the function's code chain.
     */
    fun register(name: String, script: CompiledScript) {
        functions[name] = script
    }

    /**
     * Look up a function by name.
     *
     * @return The [CompiledScript] for the function, or null if not registered.
     *
     * Requirements: 5.3, 5.4
     */
    fun get(name: String): CompiledScript? = functions[name]

    /**
     * Load all function entry points from a list of compiled scripts.
     *
     * Filters scripts where [CompiledScript.isFunctionEntry] is true and
     * [CompiledScript.functionName] is non-null, then registers each one.
     *
     * Requirements: 5.1, 5.2
     *
     * @param scripts The full list of compiled scripts for a plot.
     */
    fun loadFromAST(scripts: List<CompiledScript>) {
        scripts
            .filter { it.isFunctionEntry && it.functionName != null }
            .forEach { register(it.functionName!!, it) }
    }

    /**
     * Remove all registered functions.
     * Called when a plot is unloaded or transitions out of PLAY mode.
     */
    fun clear() {
        functions.clear()
    }

    /**
     * Returns the number of registered functions.
     */
    fun size(): Int = functions.size
}
