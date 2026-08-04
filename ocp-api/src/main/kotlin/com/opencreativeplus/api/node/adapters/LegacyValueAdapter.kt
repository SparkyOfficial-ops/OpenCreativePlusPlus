package com.opencreativeplus.api.node.adapters

import com.opencreativeplus.api.node.CommandNode
import com.opencreativeplus.api.node.IValue
import com.opencreativeplus.api.node.NodeType

/**
 * Wraps a legacy [IValue] implementation so it can participate in the new
 * [CommandNode]-based dispatch system while remaining fully backwards-compatible.
 *
 * All [IValue]/[INode] method calls are delegated to the wrapped [value] via
 * Kotlin's `by` delegation — no behavioural changes are introduced.
 *
 * Requirements: 8.4, 10.1
 *
 * @param T The type of value the wrapped [IValue] computes.
 * @param value The legacy [IValue] instance to wrap.
 */
class LegacyValueAdapter<T>(private val value: IValue<T>) : IValue<T> by value {

    /**
     * Creates a [CommandNode] snapshot of the wrapped value node's current state.
     * The node's [params] are populated from [IValue.getParams].
     *
     * @return A [CommandNode] with type [NodeType.VALUE], the wrapped value's
     *         [nodeId], and its current parameters.
     */
    fun toCommandNode(): CommandNode = CommandNode(
        type = NodeType.VALUE,
        nodeId = value.nodeId,
        params = value.getParams()
    )
}
