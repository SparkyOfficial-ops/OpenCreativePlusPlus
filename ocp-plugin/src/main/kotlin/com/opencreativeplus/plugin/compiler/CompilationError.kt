package com.opencreativeplus.plugin.compiler

import org.bukkit.Location

/
 * Represents a single compilation error with its source location and message.
 23.1, 23.2, 23.3, 23.4, 23.5
 */
data class CompilationError(
    val location: Location,
    val message: String
)
