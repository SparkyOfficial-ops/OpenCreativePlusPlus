package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.model.ItemVariableRef
import com.opencreativeplus.api.model.ItemVariableType
import com.opencreativeplus.api.registry.NodeRegistry
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.block.Chest
import org.bukkit.block.TileState
import org.bukkit.persistence.PersistentDataType

/**
 * Scans the Coding_Zone world for physical block arrangements and converts them to CodeLines.
 *
 * Scanning strategy (s 4.1, 4.2, 20.1, 20.2, 20.3, 20.4):
 * - Iterates Y levels 0..255 step 5
 * - At each Y, scans Z coordinates -512..512 step 2 looking for BLUE_STAINED_GLASS
 * - When found, reads the strip left-to-right (increasing X) collecting blocks above glass
 * - Stops when the glass strip ends (non-glass block at floor level)
 *
 * Parameter extraction (s 4.5, 4.6, 19.1-19.5):
 * - Checks all adjacent faces for attached signs -> parses key=value pairs
 * - Checks block directly above for a chest -> reads chest contents
 */
class BlockScanner(
    private val world: World,
    private val nodeRegistry: NodeRegistry,
    private val pluginNamespace: String = "opencreativeplus"
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
     * Extract parameters from signs attached to [block], from a chest placed above it
     * (including Item_Variable items), and from the block's PersistentDataContainer.
     *
     * Priority order (Req 20.3): PDC values overwrite sign values.
     4.2, 4.3, 4.5, 4.6, 19.1, 19.2, 19.3, 19.4, 20.2, 20.3
     */
    internal fun extractParameters(block: Block): Map<String, Any> {
        val params = mutableMapOf<String, Any>()

        // 1. Read signs (existing logic)
        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val adjacent = block.getRelative(face)
            val state = adjacent.state
            if (state is Sign) {
                params.putAll(parseSignText(state.lines))
            }
        }

        // 2. Read chest above (existing logic + Item Variable detection)
        val above = block.getRelative(BlockFace.UP)
        val aboveState = above.state
        if (aboveState is Chest) {
            val nonNullContents = aboveState.inventory.contents.filterNotNull()
            params["chest_contents"] = nonNullContents

            // Detect Item_Variable items by their PDC tag ocp:variable_type (Req 4.2, 4.3)
            nonNullContents.forEach { item ->
                val pdc = item.itemMeta?.persistentDataContainer ?: return@forEach
                val varTypeStr = pdc.get(
                    NamespacedKey(pluginNamespace, "variable_type"),
                    PersistentDataType.STRING
                ) ?: return@forEach
                val varName = pdc.get(
                    NamespacedKey(pluginNamespace, "variable_name"),
                    PersistentDataType.STRING
                ) ?: return@forEach
                val varType = runCatching {
                    ItemVariableType.valueOf(varTypeStr.uppercase())
                }.getOrNull() ?: return@forEach
                params["item_var_${varTypeStr.lowercase()}"] = ItemVariableRef(varName, varType)
            }
        }

        // 3. PDC has priority over sign data (Req 20.2, 20.3)
        params.putAll(readPDCParams(block))

        return params
    }

    /**
     * Read all parameters stored in the block's PersistentDataContainer under the "ocp" namespace.
     * Supports STRING, INTEGER, and DOUBLE types.
     * Returns an empty map if the block state is not a TileState.
     20.2, 20.3
     */
    internal fun readPDCParams(block: Block): Map<String, Any> {
        val pdc = (block.state as? TileState)?.persistentDataContainer ?: return emptyMap()
        val result = mutableMapOf<String, Any>()
        pdc.keys.filter { it.namespace == "ocp" }.forEach { key ->
            val paramName = key.key
            // Try each type; last non-null wins (STRING is tried last so it doesn't shadow numeric types)
            pdc.get(key, PersistentDataType.INTEGER)?.let { result[paramName] = it }
            pdc.get(key, PersistentDataType.DOUBLE)?.let { result[paramName] = it }
            pdc.get(key, PersistentDataType.STRING)?.let { result[paramName] = it }
        }
        return result
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
