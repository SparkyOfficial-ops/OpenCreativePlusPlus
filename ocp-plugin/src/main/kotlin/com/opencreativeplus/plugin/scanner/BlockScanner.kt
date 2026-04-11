package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.registry.NodeRegistry
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.block.Chest

/**
 * Scans the Coding_Zone world for physical block arrangements and converts them to CodeLines.
 *
 * Scanning strategy (Requirements 4.1, 4.2, 20.1, 20.2, 20.3, 20.4):
 * - Iterates Y levels 0..255 step 5
 * - At each Y, scans Z coordinates -512..512 step 2 looking for BLUE_STAINED_GLASS
 * - When found, reads the strip left-to-right (increasing X) collecting blocks above glass
 * - Stops when the glass strip ends (non-glass block at floor level)
 *
 * Parameter extraction (Requirements 4.5, 4.6, 19.1-19.5):
 * - Checks all adjacent faces for attached signs -> parses key=value pairs
 * - Checks block directly above for a chest -> reads chest contents
 */
class BlockScanner(
    private val world: World,
    private val nodeRegistry: NodeRegistry
) {

    companion object {
        private val GLASS_STRIP_MATERIALS = setOf(
            Material.BLUE_STAINED_GLASS,
            Material.WHITE_STAINED_GLASS,
            Material.GRAY_STAINED_GLASS
        )
    }

    /**
     * Scan the entire coding zone and return all discovered CodeLines.
     4.1, 20.1, 20.2, 20.3
     */
    fun scanCodingZone(): List<CodeLine> {
        val codeLines = mutableListOf<CodeLine>()
        for (y in 0..255 step 5) {
            codeLines.addAll(scanLevel(y))
        }
        return codeLines
    }

    /**
     * Scan a single Y level for blue glass strip starts.
     4.1, 20.2
     */
    private fun scanLevel(y: Int): List<CodeLine> {
        val lines = mutableListOf<CodeLine>()
        for (z in -512..512 step 2) {
            val startBlock = world.getBlockAt(0, y, z)
            if (startBlock.type == Material.BLUE_STAINED_GLASS) {
                lines.add(scanStrip(startBlock))
            }
        }
        return lines
    }

    /**
     * Read a single coding strip starting at [startBlock] (a blue glass block).
     * Moves in the +X direction, collecting blocks above each glass block.
     * Stops when the floor block is no longer a glass strip material.
     4.2, 4.3, 40.1, 40.2, 40.3
     */
    private fun scanStrip(startBlock: Block): CodeLine {
        val nodes = mutableListOf<ScannedNode>()
        var x = startBlock.x

        while (true) {
            val floorBlock = world.getBlockAt(x, startBlock.y, startBlock.z)
            if (floorBlock.type !in GLASS_STRIP_MATERIALS) break

            val nodeBlock = floorBlock.getRelative(BlockFace.UP)
            if (nodeBlock.type != Material.AIR) {
                val params = extractParameters(nodeBlock)
                nodes.add(ScannedNode(nodeBlock.type, nodeBlock.location, params))
            }
            x++
        }

        return CodeLine(startBlock.location, nodes)
    }

    /**
     * Extract parameters from signs attached to [block] and from a chest placed above it.
     4.5, 4.6, 19.1, 19.2, 19.3, 19.4
     */
    internal fun extractParameters(block: Block): Map<String, Any> {
        val params = mutableMapOf<String, Any>()

        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val adjacent = block.getRelative(face)
            val state = adjacent.state
            if (state is Sign) {
                params.putAll(parseSignText(state.lines))
            }
        }

        val above = block.getRelative(BlockFace.UP)
        val aboveState = above.state
        if (aboveState is Chest) {
            params["chest_contents"] = aboveState.inventory.contents.filterNotNull()
        }

        return params
    }

    /**
     * Parse sign lines for key=value pairs.
     19.2, 19.3, 19.4
     */
    internal fun parseSignText(lines: Array<String>): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.contains("=")) {
                val eqIdx = trimmed.indexOf('=')
                val key = trimmed.substring(0, eqIdx).trim()
                val rawValue = trimmed.substring(eqIdx + 1).trim()
                if (key.isNotEmpty()) {
                    params[key] = parseValue(rawValue)
                }
            }
        }
        return params
    }

    /**
     * Parse a raw string value into Int, Double, VariableReference, or String.
     19.3, 19.4
     */
    internal fun parseValue(value: String): Any {
        value.toIntOrNull()?.let { return it }
        value.toDoubleOrNull()?.let { return it }
        if (value.startsWith("$") && value.length > 1) {
            return VariableReference(value.substring(1))
        }
        return value
    }
}
