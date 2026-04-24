package com.opencreativeplus.plugin.scanner

import org.bukkit.Location

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
