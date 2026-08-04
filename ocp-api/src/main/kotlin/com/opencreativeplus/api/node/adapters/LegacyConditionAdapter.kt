package com.opencreativeplus.api.node.adapters

import com.opencreativeplus.api.node.CommandNode
import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.node.NodeType

/**
 * Wraps a legacy [ICondition] implementation so it can participate in the new
 * [CommandNode]-based dispatch system while remaining fully backwards-compatible.
 *
 * All [ICondition]/[INode] method calls are delegated to the wrapped [condition] via
 * Kotlin's `by` delegation — no behavioural changes are introduced.
 *
 * Requirements: 8.4, 10.1
 *
 * @param condition The legacy [ICondition] instance to wrap.
 */
class LegacyConditionAdapter(private val condition: ICondition) : ICondition by condition {

    /**
     * Creates a [CommandNode] snapshot of the wrapped condition's current state.
     * The node's [params] are populated from [ICondition.getParams].
     *
     * @return A [CommandNode] with type [NodeType.CONDITION], the wrapped condition's
     *         [nodeId], and its current parameters.
     */
    fun toCommandNode(): CommandNode = CommandNode(
        type = NodeType.CONDITION,
        nodeId = condition.nodeId,
        params = condition.getParams()
    )
}
