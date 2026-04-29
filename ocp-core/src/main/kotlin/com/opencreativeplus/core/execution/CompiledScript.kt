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
 * [isFunctionEntry] marks this script as a named function definition (Req 5.1, 5.2).
 * [functionName] holds the function name read from the first ParamChest slot of the LAPIS_BLOCK.
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
    val conditionalBranches: Map<Int, List<IAction>> = emptyMap(),
    /**
     * Statically-resolved placeholder strings at compile time (Req 13.2).
     * Maps placeholder key → resolved value.
     */
    val resolvedPlaceholders: Map<String, String> = emptyMap(),
    /**
     * Whether this script is a named function definition entry point (Req 5.1, 5.2).
     * Set to true when the first block of the code line is a LAPIS_BLOCK.
     */
    val isFunctionEntry: Boolean = false,
    /**
     * The name of the function, read from the first slot of the ParamChest above the LAPIS_BLOCK.
     * Non-null only when [isFunctionEntry] is true (Req 5.2).
     */
    val functionName: String? = null
)
