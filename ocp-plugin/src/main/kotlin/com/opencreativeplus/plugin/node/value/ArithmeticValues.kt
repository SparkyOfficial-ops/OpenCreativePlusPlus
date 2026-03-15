package com.opencreativeplus.plugin.node.value

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IValue

/**
 * Arithmetic and comparison value nodes.
 * Requirements: 31.1, 31.2, 31.3
 */

class AddValue(private val left: Any?, private val right: Any?) : IValue<Double> {
    override val nodeId = "add"
    override val displayName = "Add"
    override suspend fun compute(context: ExecutionContext): Double =
        resolve(left, context) + resolve(right, context)
}

class SubtractValue(private val left: Any?, private val right: Any?) : IValue<Double> {
    override val nodeId = "subtract"
    override val displayName = "Subtract"
    override suspend fun compute(context: ExecutionContext): Double =
        resolve(left, context) - resolve(right, context)
}

class MultiplyValue(private val left: Any?, private val right: Any?) : IValue<Double> {
    override val nodeId = "multiply"
    override val displayName = "Multiply"
    override suspend fun compute(context: ExecutionContext): Double =
        resolve(left, context) * resolve(right, context)
}

class DivideValue(private val left: Any?, private val right: Any?) : IValue<Double> {
    override val nodeId = "divide"
    override val displayName = "Divide"
    override suspend fun compute(context: ExecutionContext): Double {
        val r = resolve(right, context)
        if (r == 0.0) return 0.0
        return resolve(left, context) / r
    }
}

class EqualsValue(private val left: Any?, private val right: Any?) : IValue<Boolean> {
    override val nodeId = "equals_value"
    override val displayName = "Equals"
    override suspend fun compute(context: ExecutionContext): Boolean =
        resolveRaw(left, context) == resolveRaw(right, context)
}

class GreaterThanValue(private val left: Any?, private val right: Any?) : IValue<Boolean> {
    override val nodeId = "greater_than_value"
    override val displayName = "Greater Than"
    override suspend fun compute(context: ExecutionContext): Boolean =
        resolve(left, context) > resolve(right, context)
}

class LessThanValue(private val left: Any?, private val right: Any?) : IValue<Boolean> {
    override val nodeId = "less_than_value"
    override val displayName = "Less Than"
    override suspend fun compute(context: ExecutionContext): Boolean =
        resolve(left, context) < resolve(right, context)
}

// -------------------------------------------------------------------------
// Helpers
// -------------------------------------------------------------------------

private fun resolveRaw(value: Any?, context: ExecutionContext): Any? {
    if (value is String && value.startsWith("$")) {
        val name = value.substring(1)
        return context.localScope.get(name)
            ?: context.plotScope.get(name)
            ?: context.savedScope.get(name)
    }
    return value
}

private fun resolve(value: Any?, context: ExecutionContext): Double {
    val raw = resolveRaw(value, context)
    return when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
}
