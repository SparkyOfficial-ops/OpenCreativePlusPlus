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
 * Requirements: 6.1, 6.2
 */
data class CompiledScript(
    val event: IEvent,
    val actions: List<IAction>,
    /** Human-readable location string, e.g. "world@x,y,z" */
    val sourceLocation: String
)
