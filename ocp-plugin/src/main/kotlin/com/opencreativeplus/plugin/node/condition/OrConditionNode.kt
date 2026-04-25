package com.opencreativeplus.plugin.node.condition

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.ICondition

/**
 * Evaluates a list of child conditions with OR semantics (short-circuit).
 * Empty list → false.
 *
 * Requirements: 7.1, 7.2, 7.3, 7.4, 7.5
 */
class OrConditionNode(
    private val children: List<ICondition>
) : ICondition {
    override val nodeId = "or_condition"
    override val displayName = "Or Condition"

    override suspend fun evaluate(context: ExecutionContext): Boolean =
        children.any { it.evaluate(context) }
}
