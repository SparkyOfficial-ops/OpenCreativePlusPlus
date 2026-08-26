package com.opencreativeplus.core.execution

/**
 * Thrown by action nodes when they cannot execute due to a missing or invalid parameter.
 *
 * Unlike a generic Exception, this is treated specially by [ExecutionEngine]:
 * - Stops the current script execution
 * - Sends the error message to the plot owner in chat
 * - Spawns a red hologram above the offending block via HologramReporter
 *
 * @param message Human-readable error description shown to the player.
 * @param sourceLocation Optional "world@x,y,z" string identifying the offending block.
 */
class ScriptExecutionException(
    message: String,
    val sourceLocation: String? = null
) : Exception(message)
