package com.opencreativeplus.plugin.scanner

import org.bukkit.Location
import org.bukkit.Material

/**
 * Represents a single code line — a sequence of scanned nodes starting at a blue glass block.
 * Supports nested child CodeLines for the Piston System (Requirement 8.2).
 * 4.2, 40.1
 */
data class CodeLine(
    val startLocation: Location,
    val nodes: List<ScannedNode> = emptyList(),
    val children: List<CodeLine> = emptyList()
)

/**
 * Reads the cycle interval (in ticks) from the first node's parameters.
 * Falls back to 20 ticks if not present or not parseable.
 * Requirements: 4.2
 */
fun CodeLine.cycleIntervalTicks(): Long =
    nodes.firstOrNull()?.parameters?.get("interval")
        ?.toString()?.toLongOrNull() ?: 20L

/**
 * Returns true if this CodeLine starts with a LAPIS_BLOCK (function definition entry point).
 * Requirements: 5.1
 */
fun CodeLine.isFunctionEntry(): Boolean =
    nodes.firstOrNull()?.blockType == Material.LAPIS_BLOCK

/**
 * Returns the function name stored in the first node's parameters under the key "function_name".
 * Returns null if this is not a function entry or the name is not set.
 * Requirements: 5.2
 */
fun CodeLine.functionName(): String? =
    if (isFunctionEntry()) nodes.firstOrNull()?.parameters?.get("function_name")?.toString()
    else null
