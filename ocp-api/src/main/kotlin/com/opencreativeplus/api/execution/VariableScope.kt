package com.opencreativeplus.api.execution

/**
 * Interface for variable storage at different scope levels.
 * Supports local, plot, and saved scope variables.
 */
interface VariableScope {
    /**
     * Get the value of a variable by name.
     *
     * @param name The variable name
     * @return The variable value, or null if not found
     */
    fun get(name: String): Any?
    
    /**
     * Set the value of a variable.
     *
     * @param name The variable name
     * @param value The value to store
     */
    fun set(name: String, value: Any)
    
    /**
     * Check if a variable exists in this scope.
     *
     * @param name The variable name
     * @return true if the variable exists, false otherwise
     */
    fun has(name: String): Boolean
    
    /**
     * Clear all variables from this scope.
     */
    fun clear()
}
