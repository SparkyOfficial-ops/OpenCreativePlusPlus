package com.opencreativeplus.core.execution

import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IEvent

/**
 * Represents a compiled script ready for execution.
 *
 * A compiled script is the result of parsing a physical code line in the world —
 * it contains the triggering event, the ordered list of actions to execute, and
 * a human-readable location string for error reporting.
 *
 * [conditionalBranches] maps action index → child actions for the Piston System (Req 8.3).
 * When a condition at index i evaluates to false, the child branch at key i is skipped.
 *
 6.1, 6.2
 */
data class CompiledScript(
    val event: IEvent,
    val actions: List<IAction>,
    /** Human-readable location string, e.g. "world@x,y,z" */
    val sourceLocation: String,
    /**
     * Maps action index → child actions for conditional branches (Piston System, Req 8.3).
     * If a condition at index i evaluates to false, the child branch is skipped.
     */
    val conditionalBranches: Map<Int, List<IAction>> = emptyMap()
)
