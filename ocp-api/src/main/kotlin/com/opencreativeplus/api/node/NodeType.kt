package com.opencreativeplus.api.node

/**
 * Enumerates all node categories in the OpenCreativePlus scripting system.
 * Used by [CommandNode] to identify how to dispatch and execute a node.
 *
 * Requirements: 8.1
 */
enum class NodeType {
    /** A node that performs an action (side effect) during script execution. */
    ACTION,

    /** A node that evaluates to a boolean — used for branching. */
    CONDITION,

    /** A node that computes and returns a typed value. */
    VALUE,

    /** A node that represents a trigger event (placed on a blue-glass start block). */
    EVENT,

    /** A node that represents a call to a named function/sub-script. */
    FUNCTION_CALL
}
