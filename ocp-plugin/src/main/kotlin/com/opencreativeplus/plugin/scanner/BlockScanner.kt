package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.model.ItemVariableRef
import com.opencreativeplus.api.model.ItemVariableType
import com.opencreativeplus.api.registry.NodeRegistry
import com.opencreativeplus.plugin.registry.ActionDescriptor
import com.opencreativeplus.plugin.registry.CategoryRegistry
import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Chest
import org.bukkit.block.Sign
import org.bukkit.block.TileState
import org.bukkit.persistence.PersistentDataType
import java.util.logging.Logger

/**
 * Scans the Coding_Zone world for physical block arrangements and converts them to CodeLines.
 *
 * Scanning strategy (s 4.1, 4.2, 10.1–10.7):
 * - Iterates Y levels 0..255 step 5
 * - At each Y, scans Z coordinates -512..512 step 2 looking for BLUE_STAINED_GLASS
 * - When found, runs BFS pathfinding from the start block, following glass strips
 *   in all directions (straight, turns, branches), collecting blocks above glass
 *
 * PDC priority (s 4.1, 4.2, 4.3):
 * - Reads `ocp:action_id` from block PDC; if present, uses it as nodeId
 * - Falls back to Material-based NodeRegistry lookup if PDC key absent
 * - Logs WARNING and skips block if action_id not registered in NodeRegistry
 *
 * Parameter extraction (s 9.5–9.9):
 * - If block above has a chest with `ocp:param_chest`, reads chest inventory
 * - Maps items to expectedParams by slot index
 */
class BlockScanner(
    private val world: World,
    private val nodeRegistry: NodeRegistry,
    private val pluginNamespace: String = "opencreativeplus",
    private val categoryRegistry: CategoryRegistry? = null,
    private val logger: Logger = Logger.getLogger(BlockScanner::class.java.name)
) {

    companion object {
        private val GLASS_STRIP_MATERIALS = setOf(
            Material.BLUE_STAINED_GLASS,
            Material.WHITE_STAINED_GLASS,
            Material.GRAY_STAINED_GLASS
        )

        /** PDC key for the selected action id on a Category_Block. */
        private val KEY_ACTION_ID = NamespacedKey("ocp", "action_id")

        /** PDC key marking a chest as a parameter chest. */
        private val KEY_PARAM_CHEST = NamespacedKey("ocp", "param_chest")

        /** PDC keys for item variables (ItemVariableFactory). */
        private val KEY_ITEM_VAR_TYPE = NamespacedKey("ocp", "item_var_type")
        private val KEY_ITEM_VAR_NAME = NamespacedKey("ocp", "item_var_name")
    }

    // -----------------------------------------------------------------------
    // Traversal state for BFS pathfinding
    // -----------------------------------------------------------------------

    /** Cardinal directions supported by the pathfinding scanner. */
    private enum class Direction {
        NORTH, SOUTH, EAST, WEST;

        fun toBlockFace(): BlockFace = when (this) {
            NORTH -> BlockFace.NORTH
            SOUTH -> BlockFace.SOUTH
            EAST  -> BlockFace.EAST
            WEST  -> BlockFace.WEST
        }

        fun turnLeft(): Direction = when (this) {
            NORTH -> WEST
            WEST  -> SOUTH
            SOUTH -> EAST
            EAST  -> NORTH
        }

        fun turnRight(): Direction = when (this) {
            NORTH -> EAST
            EAST  -> SOUTH
            SOUTH -> WEST
            WEST  -> NORTH
        }
    }

    private data class TraversalState(
        val block: Block,
        val direction: Direction,
        val codeLine: MutableList<ScannedNode>
    )

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Scan the entire coding zone and return all discovered CodeLines.
     * Requirements: 4.1, 10.1
     */
    fun scanCodingZone(): List<CodeLine> {
        val codeLines = mutableListOf<CodeLine>()
        for (y in 0..255 step 5) {
            codeLines.addAll(scanLevel(y))
        }
        return codeLines
    }

    /**
     * Scan a rectangular area of blocks using ChunkSnapshot batching.
     */
    fun scanArea(xs: IntRange, ys: IntRange, zs: IntRange): Map<Triple<Int, Int, Int>, Material> {
        val coords = mutableListOf<Triple<Int, Int, Int>>()
        for (x in xs) for (y in ys) for (z in zs) coords.add(Triple(x, y, z))

        val byChunk: Map<Pair<Int, Int>, List<Triple<Int, Int, Int>>> =
            coords.groupBy { (x, _, z) -> (x shr 4) to (z shr 4) }

        val result = mutableMapOf<Triple<Int, Int, Int>, Material>()

        for ((chunkKey, chunkCoords) in byChunk) {
            val (cx, cz) = chunkKey
            val snapshot: ChunkSnapshot = world.getChunkAt(cx, cz).getChunkSnapshot()
            for ((x, y, z) in chunkCoords) {
                val localX = x and 15
                val localZ = z and 15
                result[Triple(x, y, z)] = snapshot.getBlockType(localX, y, localZ)
            }
        }

        return result
    }

    // -----------------------------------------------------------------------
    // Internal scanning
    // -----------------------------------------------------------------------

    /**
     * Scan a single Y level for blue glass strip starts.
     * Requirements: 4.1, 10.1
     */
    private fun scanLevel(y: Int): List<CodeLine> {
        val lines = mutableListOf<CodeLine>()
        for (z in -512..512 step 2) {
            val startBlock = world.getBlockAt(0, y, z)
            if (startBlock.type == Material.BLUE_STAINED_GLASS) {
                lines.addAll(scanStrip(startBlock))
            }
        }
        return lines
    }

    /**
     * BFS/DFS pathfinding scanner starting at [startBlock] (a BLUE_STAINED_GLASS block).
     *
     * Supports straight movement, turns, branching, and cycle detection via [visited] set.
     * Returns one [CodeLine] per independent path found.
     *
     * Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7
     */
    internal fun scanStrip(startBlock: Block): List<CodeLine> {
        val visited = mutableSetOf<LocationKey>()
        val queue = ArrayDeque<TraversalState>()
        val results = mutableListOf<CodeLine>()

        val startNodes = mutableListOf<ScannedNode>()
        queue.add(TraversalState(startBlock, Direction.EAST, startNodes))
        visited.add(LocationKey.of(startBlock.location))

        while (queue.isNotEmpty()) {
            val state = queue.removeFirst()
            val (block, dir, nodeList) = state

            // Collect block above current glass
            val above = block.getRelative(BlockFace.UP)
            if (above.type != Material.AIR) {
                val node = buildScannedNode(above)
                if (node != null) {
                    nodeList.add(node)
                }
            }

            // Determine candidate next blocks
            val ahead      = block.getRelative(dir.toBlockFace())
            val leftDir    = dir.turnLeft()
            val rightDir   = dir.turnRight()
            val leftBlock  = block.getRelative(leftDir.toBlockFace())
            val rightBlock = block.getRelative(rightDir.toBlockFace())

            val candidates = listOf(ahead to dir, leftBlock to leftDir, rightBlock to rightDir)
                .filter { (b, _) -> b.type in GLASS_STRIP_MATERIALS && LocationKey.of(b.location) !in visited }

            when (candidates.size) {
                0 -> {
                    // Dead end — finalise this path
                    results.add(CodeLine(startBlock.location, nodeList))
                }
                1 -> {
                    // Continue along the single candidate
                    val (next, nextDir) = candidates[0]
                    visited.add(LocationKey.of(next.location))
                    queue.add(TraversalState(next, nextDir, nodeList))
                }
                else -> {
                    // Branch — current path is done; each candidate starts a new independent path
                    results.add(CodeLine(startBlock.location, nodeList))
                    for ((next, nextDir) in candidates) {
                        visited.add(LocationKey.of(next.location))
                        queue.add(TraversalState(next, nextDir, mutableListOf()))
                    }
                }
            }
        }

        return results
    }

    // -----------------------------------------------------------------------
    // Node building
    // -----------------------------------------------------------------------

    /**
     * Build a [ScannedNode] for [block] (a block above a glass strip).
     *
     * PDC priority (Requirements 4.1, 4.2, 4.3):
     * 1. Read `ocp:action_id` from block PDC.
     * 2. If present → use as nodeId; look up descriptor for param extraction.
     * 3. If absent → resolve nodeId via Material-based NodeRegistry lookup.
     * 4. If action_id present but not registered → log WARNING and return null (skip block).
     *
     * Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 7.1
     */
    internal fun buildScannedNode(block: Block): ScannedNode? {
        val pdcActionId = readActionId(block)

        return if (pdcActionId != null) {
            // PDC path: action_id present
            val factory = nodeRegistry.getActionFactoryById(pdcActionId)
                ?: nodeRegistry.getConditionFactoryById(pdcActionId)
                ?: nodeRegistry.getValueFactoryById(pdcActionId)

            if (factory == null) {
                logger.warning(
                    "BlockScanner: action_id '$pdcActionId' at ${block.location} " +
                    "is not registered in NodeRegistry — skipping block"
                )
                return null
            }

            val descriptor = categoryRegistry?.getDescriptorById(pdcActionId)
            val params = extractParameters(block, descriptor)
            ScannedNode(
                blockType = block.type,
                location  = block.location,
                parameters = params,
                nodeId    = pdcActionId
            )
        } else {
            // Material fallback path (Requirement 7.1)
            val materialNodeId = nodeRegistry.getActionNodeId(block.type)
                ?: nodeRegistry.getConditionNodeId(block.type)
                ?: nodeRegistry.getValueNodeId(block.type)

            val descriptor = materialNodeId?.let { categoryRegistry?.getDescriptorById(it) }
            val params = extractParameters(block, descriptor)
            ScannedNode(
                blockType  = block.type,
                location   = block.location,
                parameters = params,
                nodeId     = materialNodeId
            )
        }
    }

    /**
     * Read `ocp:action_id` from the block's PDC.
     * Returns null if the block is not a TileState or the key is absent.
     */
    private fun readActionId(block: Block): String? {
        val pdc = (block.state as? TileState)?.persistentDataContainer ?: return null
        return pdc.get(KEY_ACTION_ID, PersistentDataType.STRING)
    }

    // -----------------------------------------------------------------------
    // Parameter extraction
    // -----------------------------------------------------------------------

    /**
     * Extract parameters for [block].
     *
     * If [descriptor] is provided and the block above has a chest marked with
     * `ocp:param_chest`, reads chest inventory and maps items to [descriptor.expectedParams]
     * by ascending slot index (Requirements 9.5, 9.6, 9.7, 9.8, 9.9).
     *
     * Otherwise falls back to the legacy sign + PDC extraction path.
     *
     * Requirements: 4.2, 4.3, 4.5, 9.5, 9.6, 9.7, 9.8, 9.9
     */
    internal fun extractParameters(block: Block, descriptor: ActionDescriptor? = null): Map<String, Any> {
        // Try param-chest path first (new category-based system)
        val above = block.getRelative(BlockFace.UP)
        val aboveState = above.state
        if (aboveState is Chest) {
            val chestPdc = (aboveState as? TileState)?.persistentDataContainer
            val isParamChest = chestPdc?.get(KEY_PARAM_CHEST, PersistentDataType.STRING) == "true"
            if (isParamChest && descriptor != null) {
                return buildParamChestMap(aboveState, descriptor)
            }
        }

        // Legacy path: signs + PDC on the block itself
        return extractParametersLegacy(block)
    }

    /**
     * Build a params map from a parameter chest inventory.
     *
     * Items are iterated in ascending slot index order.
     * Slot N maps to [descriptor.expectedParams][N] as the key.
     * Values:
     *  - item with `ocp:item_var_type = "variable"` → varName (String)
     *  - item with `ocp:item_var_type = "location"` → "loc:<name>" (String)
     *  - plain item → material.name (String)
     *
     * Requirements: 9.5, 9.6, 9.7, 9.8, 9.9
     */
    private fun buildParamChestMap(chest: Chest, descriptor: ActionDescriptor): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        val contents = chest.inventory.contents
        var paramIndex = 0

        for (slot in contents.indices) {
            val item = contents[slot] ?: continue
            if (paramIndex >= descriptor.expectedParams.size) break

            val paramKey = descriptor.expectedParams[paramIndex]
            paramIndex++

            val varType = item.itemMeta?.persistentDataContainer?.get(KEY_ITEM_VAR_TYPE, PersistentDataType.STRING)
            val varName = item.itemMeta?.persistentDataContainer?.get(KEY_ITEM_VAR_NAME, PersistentDataType.STRING)

            val value: Any = when (varType) {
                "variable" -> varName ?: item.type.name
                "location" -> if (varName != null) "loc:$varName" else item.type.name
                else       -> item.type.name
            }

            params[paramKey] = value
        }

        return params
    }

    /**
     * Legacy parameter extraction: reads signs on adjacent faces and PDC on the block.
     * PDC values override sign values for the same key (Requirement 20.3).
     *
     * Requirements: 4.2, 4.3, 4.5, 4.6, 19.1, 19.2, 19.3, 19.4, 20.2, 20.3
     */
    internal fun extractParameters(block: Block): Map<String, Any> = extractParametersLegacy(block)

    private fun extractParametersLegacy(block: Block): Map<String, Any> {
        val params = mutableMapOf<String, Any>()

        // 1. Read signs
        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val adjacent = block.getRelative(face)
            val state = adjacent.state
            if (state is Sign) {
                params.putAll(parseSignText(state.lines))
            }
        }

        // 2. Read chest above (legacy Item_Variable detection)
        val above = block.getRelative(BlockFace.UP)
        val aboveState = above.state
        if (aboveState is Chest) {
            val nonNullContents = aboveState.inventory.contents.filterNotNull()
            params["chest_contents"] = nonNullContents

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
     * Requirements: 20.2, 20.3
     */
    internal fun readPDCParams(block: Block): Map<String, Any> {
        val pdc = (block.state as? TileState)?.persistentDataContainer ?: return emptyMap()
        val result = mutableMapOf<String, Any>()
        pdc.keys.filter { it.namespace == "ocp" }.forEach { key ->
            val paramName = key.key
            pdc.get(key, PersistentDataType.INTEGER)?.let { result[paramName] = it }
            pdc.get(key, PersistentDataType.DOUBLE)?.let { result[paramName] = it }
            pdc.get(key, PersistentDataType.STRING)?.let { result[paramName] = it }
        }
        return result
    }

    /**
     * Parse sign lines for key=value pairs.
     * Requirements: 19.2, 19.3, 19.4
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
     * Requirements: 19.3, 19.4
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
