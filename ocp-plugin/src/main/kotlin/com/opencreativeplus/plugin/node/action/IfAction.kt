package com.opencreativeplus.plugin.node.action

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition

/**
 * Conditional branching action node.
 * Executes [thenActions] if [condition] evaluates to true, otherwise [elseActions].
 *
 * Requirements: 30.3
 */
class IfAction(
    private val condition: ICondition,
    private val thenActions: List<IAction>,
    private val elseActions: List<IAction> = emptyList()
) : IAction {
    override val nodeId = "if"
    override val displayName = "If"
    override suspend fun execute(context: ExecutionContext) {
        val branch = if (condition.evaluate(context)) thenActions else elseActions
        for (action in branch) {
            action.execute(context)
        }
    }
}
