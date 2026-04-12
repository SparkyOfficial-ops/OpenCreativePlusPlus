package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.VariableScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe implementation of VariableScope using ConcurrentHashMap.
 * Supports concurrent access from multiple coroutines.
 */
class VariableScopeImpl : VariableScope {
    private val variables = ConcurrentHashMap<String, Any>()
    
    override fun get(name: String): Any? = variables[name]
    
    override fun set(name: String, value: Any) {
        variables[name] = value
    }
    
    override fun has(name: String): Boolean = variables.containsKey(name)
    
    override fun clear() {
        variables.clear()
    }
    
    /**
     * Convert the scope to a map for serialization.
     * Returns a snapshot of the current variables.
     */
    fun toMap(): Map<String, Any> = variables.toMap()
}
