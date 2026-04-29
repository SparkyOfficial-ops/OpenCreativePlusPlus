package com.opencreativeplus.plugin.scanner

import org.bukkit.Location

/**
 * Represents a parse error encountered during block scanning.
 *
 * Errors are accumulated (not thrown) so all problems in a code line can be
 * reported at once via [HologramReporter].
 *
 * Requirements: 2.6
 */
data class ParseError(
    val message: String,
    val location: Location? = null
) {
    override fun toString(): String =
        if (location != null) "$message (at ${location.blockX},${location.blockY},${location.blockZ})"
        else message
}
