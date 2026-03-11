package com.opencreativeplus.api.node

import com.opencreativeplus.api.execution.ExecutionContext

/**
 * Interface for action nodes that perform operations during script execution.
 * Actions are executed sequentially in a code line.
 */
interface IAction : INode {
    /**
     * Execute this action within the given execution context.
     * This is a suspending function to support coroutine-based execution.
     *
     * @param context The execution context containing variables, player, and event data
     */
    suspend fun execute(context: ExecutionContext)
}
