package com.opencreativeplus.plugin.node.condition

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.ICondition

/**
 * Evaluates two values for equality.
 30.1, 30.2
 */
class EqualsCondition(
    private val left: Any?,
    private val right: Any?
) : ICondition {
    override val nodeId = "equals"
    override val displayName = "Equals"
    override suspend fun evaluate(context: ExecutionContext): Boolean =
        resolveValue(left, context) == resolveValue(right, context)
}

/**
 * Evaluates whether left > right (numeric comparison).
 30.1, 30.2
 */
class GreaterThanCondition(
    private val left: Any?,
    private val right: Any?
) : ICondition {
    override val nodeId = "greater_than"
    override val displayName = "Greater Than"
    override suspend fun evaluate(context: ExecutionContext): Boolean {
        val l = toDouble(resolveValue(left, context)) ?: return false
        val r = toDouble(resolveValue(right, context)) ?: return false
        return l > r
    }
}

/**
 * Evaluates whether left < right (numeric comparison).
 30.1, 30.2
 */
class LessThanCondition(
    private val left: Any?,
    private val right: Any?
) : ICondition {
    override val nodeId = "less_than"
    override val displayName = "Less Than"
    override suspend fun evaluate(context: ExecutionContext): Boolean {
        val l = toDouble(resolveValue(left, context)) ?: return false
        val r = toDouble(resolveValue(right, context)) ?: return false
        return l < r
    }
}

private fun resolveValue(value: Any?, context: ExecutionContext): Any? {
    if (value is String && value.startsWith("$")) {
        val varName = value.substring(1)
        return context.localScope.get(varName)
            ?: context.plotScope.get(varName)
            ?: context.savedScope.get(varName)
    }
    return value
}

private fun toDouble(value: Any?): Double? = when (value) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull()
    else -> null
}
