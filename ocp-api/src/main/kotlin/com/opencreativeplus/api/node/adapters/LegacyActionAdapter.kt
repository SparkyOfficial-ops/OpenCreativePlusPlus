package com.opencreativeplus.api.node.adapters

import com.opencreativeplus.api.node.CommandNode
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.NodeType

/**
 * Wraps a legacy [IAction] implementation so it can participate in the new
 * [CommandNode]-based dispatch system while remaining fully backwards-compatible.
 *
 * All [IAction]/[INode] method calls are delegated to the wrapped [action] via
 * Kotlin's `by` delegation — no behavioural changes are introduced.
 *
 * Requirements: 8.4, 10.1
 *
 * @param action The legacy [IAction] instance to wrap.
 */
class LegacyActionAdapter(private val action: IAction) : IAction by action {

    /**
     * Creates a [CommandNode] snapshot of the wrapped action's current state.
     * The node's [params] are populated from [IAction.getParams].
     *
     * @return A [CommandNode] with type [NodeType.ACTION], the wrapped action's
     *         [nodeId], and its current parameters.
     */
    fun toCommandNode(): CommandNode = CommandNode(
        type = NodeType.ACTION,
        nodeId = action.nodeId,
        params = action.getParams()
    )
}
