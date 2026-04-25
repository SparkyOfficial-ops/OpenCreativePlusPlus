package com.opencreativeplus.plugin.node.condition

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.ICondition

/**
 * Evaluates a list of child conditions with AND semantics (short-circuit).
 * Empty list → true (vacuous truth).
 *
 * Requirements: 6.1, 6.2, 6.3, 6.4, 6.5
 */
class AndConditionNode(
    private val children: List<ICondition>
) : ICondition {
    override val nodeId = "and_condition"
    override val displayName = "And Condition"

    override suspend fun evaluate(context: ExecutionContext): Boolean =
        children.all { it.evaluate(context) }
}
