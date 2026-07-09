package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.model.ItemVariableRef
import com.opencreativeplus.api.model.ItemVariableType
import com.opencreativeplus.api.registry.NodeRegistry
import com.opencreativeplus.plugin.registry.ActionDescriptor
import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.scanner.DataContainer.Companion.plainDisplayName
import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.block.TileState
import org.bukkit.inventory.ItemStack
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
 *
 * Piston System (Requirements 2.1–2.7):
 * - STICKY_PISTON above glass = opening bracket of a conditional scope
 * - PISTON above glass = closing bracket of a conditional scope
 * - [scanChildBranch] implements depth-tracking up to 16 levels
 * - Unclosed brackets accumulate as [ParseError] in [parseErrors]
 */
class BlockScanner(
    private val world: World,
    private val nodeRegistry: NodeRegistry,
    private val pluginNamespace: String = "opencreativeplus",
    private val categoryRegistry: CategoryRegistry? = null,
    private val logger: Logger = Logger.getLogger(BlockScanner::class.java.name)
) {

    /** Accumulated parse errors from the most recent scan. Cleared at the start of each [scanStrip]. */
    val parseErrors: MutableList<ParseError> = mutableListOf()

    companion object {
        private val GLASS_STRIP_MATERIALS = setOf(
            Material.BLUE_STAINED_GLASS,
            Material.WHITE_STAINED_GLASS,
            Material.GRAY_STAINED_GLASS
        )

        /** Conditional node materials that may be followed by a STICKY_PISTON scope (Piston System, Req 2.1–2.3). */
        private val CONDITIONAL_MATERIALS = setOf(
            Material.OAK_PLANKS,   // IF_PLAYER
            Material.OBSIDIAN,     // IF_VARIABLE
            Material.BRICK         // IF_ENTITY
        )

        /** Opening bracket of a conditional scope (Req 2.1). */
        private val OPENING_BRACKET = Material.STICKY_PISTON

        /** Closing bracket of a conditional scope (Req 2.2). */
        private val CLOSING_BRACKET = Material.PISTON

        /** Else-branch marker block (Req 4.1). Follows a closing PISTON to introduce an else scope. */
        private val ELSE_MARKER = Material.END_STONE

        /** Maximum nesting depth for piston scopes (Req 2.7). */
        const val MAX_PISTON_DEPTH = 16

        /** PDC key for the selected action id on a Category_Block. */
        private val KEY_ACTION_ID = NamespacedKey("ocp", "action_id")

        /** PDC key marking a chest as a parameter chest. */
        private val KEY_PARAM_CHEST = NamespacedKey("ocp", "param_chest")

        /** PDC keys for item variables (ItemVariableFactory). */
        private val KEY_ITEM_VAR_TYPE = NamespacedKey("ocp", "item_var_type")
        private val KEY_ITEM_VAR_NAME = NamespacedKey("ocp", "item_var_name")

        /** PDC key for DataContainer variable name (Iron Ingot). Requirements: 3.3 */
        private val KEY_DC_VAR_NAME   = NamespacedKey("ocp", "var_name")
        /** PDC key for DataContainer location x. Requirements: 3.4 */
        private val KEY_DC_LOC_X      = NamespacedKey("ocp", "loc_x")
        /** PDC key for DataContainer location y. Requirements: 3.4 */
        private val KEY_DC_LOC_Y      = NamespacedKey("ocp", "loc_y")
        /** PDC key for DataContainer location z. Requirements: 3.4 */
        private val KEY_DC_LOC_Z      = NamespacedKey("ocp", "loc_z")
        /** PDC key for DataContainer world name. Requirements: 3.4 */
        private val KEY_DC_LOC_WORLD  = NamespacedKey("ocp", "loc_world")
        /** PDC key for DataContainer yaw. Requirements: 3.4 */
        private val KEY_DC_LOC_YAW    = NamespacedKey("ocp", "loc_yaw")
        /** PDC key for DataContainer pitch. Requirements: 3.4 */
        private val KEY_DC_LOC_PITCH  = NamespacedKey("ocp", "loc_pitch")

        /** Cycle entry point block (Req 4.1). */
        val CYCLE_ENTRY_MATERIAL = Material.EMERALD_BLOCK

        /** Function definition entry point block (Req 5.1). */
        val FUNCTION_ENTRY_MATERIAL = Material.LAPIS_BLOCK

        /**
         * Half-width of the Z scan range in [scanLevel].
         * Kept within the plot coding-zone boundary so scanLevel never touches
         * unloaded chunks outside the dev world.
         */
        const val SCAN_Z_RADIUS = 200
    }

    /**
     * Returns all CodeLines whose first node is an EMERALD_BLOCK (cycle entry points).
     * Requirements: 4.1
     */
    fun findCycleEntries(codeLines: List<CodeLine>): List<CodeLine> =
        codeLines.filter { it.nodes.firstOrNull()?.blockType == CYCLE_ENTRY_MATERIAL }

    /**
     * Returns all CodeLines whose first node is a LAPIS_BLOCK (function definition entry points).
     * Requirements: 5.1
     */
    fun findFunctionEntries(codeLines: List<CodeLine>): List<CodeLine> =
        codeLines.filter { it.nodes.firstOrNull()?.blockType == FUNCTION_ENTRY_MATERIAL }

    /**
     * Reads the function name from the first slot of the ParamChest above [block].
     *
     * The function name is the plain display name of the item in slot 0 of the chest.
     * Returns null if no chest is present, the chest is empty, or slot 0 is empty.
     *
     * Requirements: 5.2
     *
     * @param block The LAPIS_BLOCK whose ParamChest contains the function name.
     * @return The function name string, or null if not found.
     */
    fun readFunctionName(block: org.bukkit.block.Block): String? {
        val above = block.getRelative(org.bukkit.block.BlockFace.UP)
        val barrel = above.state as? org.bukkit.block.Barrel ?: return null
        val firstItem = barrel.inventory.getItem(0) ?: return null
        return firstItem.plainDisplayName().takeIf { it.isNotBlank() }
    }

    // -----------------------------------------------------------------------
    // Traversal state for BFS pathfinding
    // -----------------------------------------------------------------------

    /** Cardinal directions supported by the pathfinding scanner. */
    internal enum class Direction {
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
        val codeLine: MutableList<ScannedNode>,
        val children: MutableList<CodeLine> = mutableListOf()
    )

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Scan the entire coding zone and return all discovered CodeLines.
     * Requirements: 4.1, 10.1
     *
     * Must be called from the Bukkit main thread (or via [scanCodingZoneAsync]) because
     * internal methods access [Block.state] / TileEntity data which PaperMC guards against
     * asynchronous access (throws IllegalStateException: Tile is null, asynchronous access?).
     */
    fun scanCodingZone(): List<CodeLine> {
        val codeLines = mutableListOf<CodeLine>()
        for (y in 0..255 step 5) {
            codeLines.addAll(scanLevel(y))
        }
        return codeLines
    }

    /**
     * Coroutine-safe variant of [scanCodingZone].
     *
     * Delegates the entire scan to the Bukkit main thread via [syncRunner] so that all
     * [Block.state] / TileEntity / PDC reads happen synchronously, avoiding the
     * "Tile is null, asynchronous access?" IllegalStateException thrown by PaperMC.
     *
     * Usage from a coroutine (e.g. ModeManagerImpl):
     * ```kotlin
     * val codeLines = scanner.scanCodingZoneAsync { block -> runOnMain(block) }
     * ```
     *
     * @param syncRunner A suspend function that schedules its lambda on the main thread
     *                   and suspends the coroutine until the result is ready.
     *                   Matches the signature of ModeManagerImpl.runOnMain.
     */
    suspend fun scanCodingZoneAsync(syncRunner: suspend (() -> List<CodeLine>) -> List<CodeLine>): List<CodeLine> =
        syncRunner { scanCodingZone() }

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
     * Safe variant of [Block.getRelative] that returns null instead of
     * synchronously loading/generating a chunk when the target coordinates
     * fall outside the currently loaded chunks.
     *
     * Using plain [Block.getRelative] across a chunk boundary on the main thread
     * causes Bukkit to synchronously load (and potentially generate) the target
     * chunk, freezing the server for several seconds — a guaranteed Watchdog crash
     * if the BFS walks a glass strip built by a player toward the world border.
     */
    private fun Block.getRelativeSafe(face: BlockFace): Block? {
        val targetX = this.x + face.modX
        val targetZ = this.z + face.modZ
        if (!world.isChunkLoaded(targetX shr 4, targetZ shr 4)) return null
        return this.getRelative(face)
    }

    /**
     * Scan a single Y level for blue glass strip starts.
     * Only scans Z in [-SCAN_Z_RADIUS..SCAN_Z_RADIUS] and skips unloaded chunks.
     * Requirements: 4.1, 10.1
     */
    private fun scanLevel(y: Int): List<CodeLine> {
        val lines = mutableListOf<CodeLine>()
        for (z in -SCAN_Z_RADIUS..SCAN_Z_RADIUS step 2) {
            val chunkX = 0 shr 4
            val chunkZ = z shr 4
            // Force-load the chunk synchronously if needed.
            // This is a one-shot operation that happens when the player switches to /play —
            // acceptable cost vs. silently ignoring code the player built away from spawn.
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                world.loadChunk(chunkX, chunkZ, /* generate = */ false)
            }
            // After load attempt: if still not loaded (chunk doesn't exist on disk), skip.
            if (!world.isChunkLoaded(chunkX, chunkZ)) continue

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
     * When a conditional node is found above a glass block and the next glass block has
     * STICKY_PISTON above it, [scanChildBranch] is called to collect the child scope
     * (Requirements 2.1, 2.2, 2.3).
     *
     * Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 2.1, 2.2, 2.3
     */
    internal fun scanStrip(startBlock: Block): List<CodeLine> {
        parseErrors.clear()
        val visited = mutableSetOf<LocationKey>()
        val queue = ArrayDeque<TraversalState>()
        val results = mutableListOf<CodeLine>()

        val startNodes = mutableListOf<ScannedNode>()
        queue.add(TraversalState(startBlock, Direction.EAST, startNodes))
        visited.add(LocationKey.of(startBlock.location))

        while (queue.isNotEmpty()) {
            val state = queue.removeFirst()
            val (block, dir, nodeList) = state

            // Collect block above current glass (UP is always loaded if the block itself is)
            val above = block.getRelative(BlockFace.UP)
            if (above.type != Material.AIR) {
                val node = buildScannedNode(above)
                if (node != null) {
                    nodeList.add(node)
                }
            }

            // Determine candidate next blocks — use getRelativeSafe to avoid loading unloaded chunks
            val ahead      = block.getRelativeSafe(dir.toBlockFace())
            val leftDir    = dir.turnLeft()
            val rightDir   = dir.turnRight()
            val leftBlock  = block.getRelativeSafe(leftDir.toBlockFace())
            val rightBlock = block.getRelativeSafe(rightDir.toBlockFace())

            val candidates = listOfNotNull(
                ahead?.let { it to dir },
                leftBlock?.let { it to leftDir },
                rightBlock?.let { it to rightDir }
            ).filter { (b, _) -> b.type in GLASS_STRIP_MATERIALS && LocationKey.of(b.location) !in visited }

            when (candidates.size) {
                0 -> {
                    results.add(CodeLine(startBlock.location, nodeList, state.children))
                }
                1 -> {
                    val (next, nextDir) = candidates[0]
                    val nextAbove = next.getRelative(BlockFace.UP)
                    val lastNode = nodeList.lastOrNull()
                    if (lastNode != null &&
                        lastNode.blockType in CONDITIONAL_MATERIALS &&
                        nextAbove.type == OPENING_BRACKET
                    ) {
                        visited.add(LocationKey.of(next.location))
                        val (childCodeLine, afterPiston) = scanChildBranch(next, nextDir, visited)
                        state.children.add(childCodeLine)

                        if (afterPiston != null) {
                            val (afterBlock, afterDir) = afterPiston
                            visited.add(LocationKey.of(afterBlock.location))
                            queue.add(TraversalState(afterBlock, afterDir, nodeList, state.children))
                        } else {
                            results.add(CodeLine(startBlock.location, nodeList, state.children))
                        }
                    } else {
                        visited.add(LocationKey.of(next.location))
                        queue.add(TraversalState(next, nextDir, nodeList, state.children))
                    }
                }
                else -> {
                    results.add(CodeLine(startBlock.location, nodeList, state.children))
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
     * Scans a child scope delimited by STICKY_PISTON (opening) and PISTON (closing) brackets.
     *
     * Called when the scanner is positioned at the glass block that has STICKY_PISTON above it
     * (the opening bracket). Implements depth tracking to support nesting up to [MAX_PISTON_DEPTH].
     *
     * Algorithm (Requirements 2.1, 2.2, 2.3, 2.6, 2.7):
     * ```
     * depth = 1  (we are already inside the opening bracket)
     * while not end of strip:
     *   advance to next glass block
     *   if STICKY_PISTON above: depth++; if depth > MAX_PISTON_DEPTH → ParseError
     *   if PISTON above: depth--; if depth == 0 → end of scope
     *   else: collect node
     * if depth > 0 at end → ParseError("Unclosed bracket at $location")
     * ```
     *
     * @param openingBracketGlass The glass block whose above block is STICKY_PISTON (depth=1 start).
     * @param direction           The current traversal direction.
     * @param visited             Shared visited set to prevent revisiting blocks.
     * @return A pair of (child CodeLine, next glass block + direction after closing PISTON).
     *         The second element is null if the strip ends without a matching closing PISTON.
     *
     * Requirements: 2.1, 2.2, 2.3, 2.6, 2.7
     */
    internal fun scanChildBranch(
        openingBracketGlass: Block,
        direction: Direction,
        visited: MutableSet<LocationKey>
    ): Pair<CodeLine, Pair<Block, Direction>?> {
        val childNodes = mutableListOf<ScannedNode>()
        var depth = 1  // We are already inside the opening STICKY_PISTON bracket
        var current = openingBracketGlass
        var dir = direction

        // The opening bracket glass block is already marked visited by the caller.
        // Now advance through the strip collecting nodes until depth reaches 0.
        while (true) {
            // Find the next glass block — use getRelativeSafe to prevent loading unloaded chunks
            val ahead      = current.getRelativeSafe(dir.toBlockFace())
            val leftDir    = dir.turnLeft()
            val rightDir   = dir.turnRight()
            val leftBlock  = current.getRelativeSafe(leftDir.toBlockFace())
            val rightBlock = current.getRelativeSafe(rightDir.toBlockFace())

            val next = listOfNotNull(
                ahead?.let { it to dir },
                leftBlock?.let { it to leftDir },
                rightBlock?.let { it to rightDir }
            ).firstOrNull { (b, _) ->
                b.type in GLASS_STRIP_MATERIALS && LocationKey.of(b.location) !in visited
            }

            if (next == null) {
                // End of strip without closing bracket (or hit unloaded chunk boundary)
                if (depth > 0) {
                    parseErrors.add(ParseError(
                        "Unclosed bracket at ${current.location.blockX},${current.location.blockY},${current.location.blockZ}",
                        current.location
                    ))
                }
                return CodeLine(openingBracketGlass.location, childNodes) to null
            }

            val (nextBlock, nextDir) = next
            visited.add(LocationKey.of(nextBlock.location))
            current = nextBlock
            dir = nextDir

            val above = current.getRelative(BlockFace.UP)
            when {
                above.type == OPENING_BRACKET -> {
                    depth++
                    if (depth > MAX_PISTON_DEPTH) {
                        parseErrors.add(ParseError(
                            "Piston nesting depth exceeded $MAX_PISTON_DEPTH at ${above.location.blockX},${above.location.blockY},${above.location.blockZ}",
                            above.location
                        ))
                    }
                }
                above.type == CLOSING_BRACKET -> {
                    depth--
                    if (depth == 0) {
                        val afterAhead      = current.getRelativeSafe(dir.toBlockFace())
                        val afterLeftDir    = dir.turnLeft()
                        val afterRightDir   = dir.turnRight()
                        val afterLeftBlock  = current.getRelativeSafe(afterLeftDir.toBlockFace())
                        val afterRightBlock = current.getRelativeSafe(afterRightDir.toBlockFace())

                        val afterNext = listOfNotNull(
                            afterAhead?.let { it to dir },
                            afterLeftBlock?.let { it to afterLeftDir },
                            afterRightBlock?.let { it to afterRightDir }
                        ).firstOrNull { (b, _) ->
                            b.type in GLASS_STRIP_MATERIALS && LocationKey.of(b.location) !in visited
                        }

                        if (afterNext != null) {
                            val (afterBlock, afterDir) = afterNext
                            val afterAbove = afterBlock.getRelative(BlockFace.UP)
                            if (afterAbove.type == ELSE_MARKER) {
                                visited.add(LocationKey.of(afterBlock.location))
                                val (elseNodes, continuationAfterElse) = scanElseBranch(afterBlock, afterDir, visited)
                                val childLineWithElse = CodeLine(
                                    openingBracketGlass.location,
                                    childNodes,
                                    elseActions = elseNodes
                                )
                                return childLineWithElse to continuationAfterElse
                            }
                        }

                        return CodeLine(openingBracketGlass.location, childNodes) to afterNext
                    }
                }
                above.type != Material.AIR -> {
                    val node = buildScannedNode(above)
                    if (node != null) childNodes.add(node)
                }
            }
        }
    }

    /**
     * Scans an else-branch scope that starts at [elseMarkerGlass] (the glass block whose
     * above block is END_STONE) and ends at the next PISTON (closing bracket).
     *
     * Algorithm (Requirements 4.1, 4.2):
     * - Advance through glass blocks collecting nodes
     * - If PISTON above is found → end of else scope; return collected nodes + next block
     * - If strip ends without PISTON → add ParseError and return collected nodes
     *
     * @param elseMarkerGlass The glass block directly under the END_STONE marker.
     * @param direction       Current traversal direction.
     * @param visited         Shared visited set.
     * @return Pair of (collected else nodes, next glass block + direction after closing PISTON or null).
     */
    internal fun scanElseBranch(
        elseMarkerGlass: Block,
        direction: Direction,
        visited: MutableSet<LocationKey>
    ): Pair<List<ScannedNode>, Pair<Block, Direction>?> {
        val elseNodes = mutableListOf<ScannedNode>()
        var current = elseMarkerGlass
        var dir = direction
        // depth tracks nested piston scopes INSIDE the else-branch.
        // depth=0 means we are at the top level of the else-branch itself.
        // When depth reaches 0 and we see CLOSING_BRACKET, the else-branch ends.
        var depth = 0

        while (true) {
            val ahead      = current.getRelativeSafe(dir.toBlockFace())
            val leftDir    = dir.turnLeft()
            val rightDir   = dir.turnRight()
            val leftBlock  = current.getRelativeSafe(leftDir.toBlockFace())
            val rightBlock = current.getRelativeSafe(rightDir.toBlockFace())

            val next = listOfNotNull(
                ahead?.let { it to dir },
                leftBlock?.let { it to leftDir },
                rightBlock?.let { it to rightDir }
            ).firstOrNull { (b, _) ->
                b.type in GLASS_STRIP_MATERIALS && LocationKey.of(b.location) !in visited
            }

            if (next == null) {
                parseErrors.add(ParseError(
                    "Else block (END_STONE) at ${current.location.blockX},${current.location.blockY},${current.location.blockZ} has no closing PISTON",
                    current.location
                ))
                return elseNodes to null
            }

            val (nextBlock, nextDir) = next
            visited.add(LocationKey.of(nextBlock.location))
            current = nextBlock
            dir = nextDir

            val above = current.getRelative(BlockFace.UP)
            when {
                above.type == OPENING_BRACKET -> {
                    // Nested conditional scope inside the else-branch — track depth,
                    // do NOT collect as a regular node (piston is not a registered action).
                    depth++
                    if (depth > MAX_PISTON_DEPTH) {
                        parseErrors.add(ParseError(
                            "Piston nesting depth exceeded $MAX_PISTON_DEPTH inside else-branch at " +
                            "${above.location.blockX},${above.location.blockY},${above.location.blockZ}",
                            above.location
                        ))
                    }
                }
                above.type == CLOSING_BRACKET -> {
                    if (depth > 0) {
                        // Closing bracket of a nested scope inside the else-branch — decrement, skip.
                        depth--
                    } else {
                        // depth == 0: this is the closing bracket of the else-branch itself.
                        val afterAhead      = current.getRelativeSafe(dir.toBlockFace())
                        val afterLeftDir    = dir.turnLeft()
                        val afterRightDir   = dir.turnRight()
                        val afterLeftBlock  = current.getRelativeSafe(afterLeftDir.toBlockFace())
                        val afterRightBlock = current.getRelativeSafe(afterRightDir.toBlockFace())

                        val afterNext = listOfNotNull(
                            afterAhead?.let { it to dir },
                            afterLeftBlock?.let { it to afterLeftDir },
                            afterRightBlock?.let { it to afterRightDir }
                        ).firstOrNull { (b, _) ->
                            b.type in GLASS_STRIP_MATERIALS && LocationKey.of(b.location) !in visited
                        }

                        return elseNodes to afterNext
                    }
                }
                above.type != Material.AIR -> {
                    val node = buildScannedNode(above)
                    if (node != null) elseNodes.add(node)
                }
            }
        }
    }

    /**
     * Build a [ScannedNode] for [block] (a block above a glass strip).
     *
     * PDC priority (Requirements 4.1, 4.2, 4.3):
     * 1. Read `ocp:action_id` from block PDC.
     * 2. If present → use as nodeId; look up descriptor for param extraction.
     * 3. If absent → resolve nodeId via Material-based NodeRegistry lookup.
     * 4. If action_id present but not registered → log WARNING and return null (skip block).
     *
     * For `and_condition` / `or_condition` nodes, child condition nodeIds are read from
     * the chest placed directly above the node block (Requirements 6.6, 7.6).
     *
     * Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 6.6, 7.1, 7.6
     */
    internal fun buildScannedNode(block: Block): ScannedNode? {
        val pdcActionId = readActionId(block)

        return if (pdcActionId != null) {
            // PDC path: action_id present
            val factory = nodeRegistry.getActionFactoryById(pdcActionId)
                ?: nodeRegistry.getConditionFactoryById(pdcActionId)
                ?: nodeRegistry.getValueFactoryById(pdcActionId)

            if (factory == null) {
                logger.fine(
                    "BlockScanner: action_id '$pdcActionId' at ${block.location} " +
                    "is not registered in NodeRegistry — skipping block"
                )
                return null
            }

            val descriptor = categoryRegistry?.getDescriptorById(pdcActionId)
            val params = extractParameters(block, descriptor).toMutableMap()
            if (pdcActionId == "and_condition" || pdcActionId == "or_condition") {
                params["condition_children"] = readConditionChildren(block)
            }
            // Req 5.2: if this is a LAPIS_BLOCK (function entry), read function name from ParamChest slot 0
            if (block.type == FUNCTION_ENTRY_MATERIAL) {
                readFunctionName(block)?.let { params["function_name"] = it }
            }
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

            // If no nodeId found by material either, skip this block (Req 4.3)
            if (materialNodeId == null) {
                logger.fine(
                    "BlockScanner: block ${block.type} at ${block.location} " +
                    "has no registered nodeId — skipping block"
                )
                return null
            }

            val descriptor = categoryRegistry?.getDescriptorById(materialNodeId)
            val params = extractParameters(block, descriptor).toMutableMap()
            if (materialNodeId == "and_condition" || materialNodeId == "or_condition") {
                params["condition_children"] = readConditionChildren(block)
            }
            // Req 5.2: if this is a LAPIS_BLOCK (function entry), read function name from ParamChest slot 0
            if (block.type == FUNCTION_ENTRY_MATERIAL) {
                readFunctionName(block)?.let { params["function_name"] = it }
            }
            ScannedNode(
                blockType  = block.type,
                location   = block.location,
                parameters = params,
                nodeId     = materialNodeId
            )
        }
    }

    /**
     * Reads child condition nodeIds from the chest placed directly above [block].
     *
     * Each item in the chest must have `ocp:action_id` in its PDC to be recognised
     * as a child condition reference. Items without this key are skipped.
     *
     * Returns a list of nodeId strings in chest-slot order.
     *
     * Requirements: 6.6, 7.6
     */
    internal fun readConditionChildren(block: Block): List<String> {
        val above = block.getRelative(BlockFace.UP)
        val aboveState = above.state as? org.bukkit.block.Barrel ?: return emptyList()
        val children = mutableListOf<String>()
        for (item in aboveState.inventory.contents) {
            if (item == null) continue
            val nodeId = item.itemMeta?.persistentDataContainer
                ?.get(KEY_ACTION_ID, PersistentDataType.STRING)
                ?: continue
            children.add(nodeId)
        }
        return children
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
     * Reads a [DataContainer] from a single [ItemStack] in a ParamChest slot.
     *
     * Mapping:
     * - [Material.BOOK]        → [DataContainer.Text] (display name as string value)
     * - [Material.MAGMA_CREAM] → [DataContainer.Number] (display name parsed as Double; ParseError if not numeric)
     * - [Material.IRON_INGOT]  → [DataContainer.Variable] (PDC ocp:var_name as variable name)
     * - [Material.COMPASS]     → [DataContainer.Location] (PDC ocp:loc_x/y/z/world/yaw/pitch)
     * - null / other material  → null (argument absent or unrecognised)
     *
     * Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.7
     *
     * @param item  The item in the chest slot, or null for an empty slot.
     * @param slot  The slot index (used in ParseError messages). Requirements: 3.5
     * @param blockLocation The location of the code block (used in ParseError messages).
     * @return The parsed [DataContainer], or null if the slot is empty or the material is unrecognised.
     */
    fun readDataContainer(item: ItemStack?, slot: Int, blockLocation: org.bukkit.Location? = null): DataContainer? {
        if (item == null) return null  // Req 3.7: empty slot → null
        val pdc = item.itemMeta?.persistentDataContainer
        return when (item.type) {
            Material.BOOK -> {
                // Req 3.1: Book → Text (display name)
                DataContainer.Text(item.plainDisplayName())
            }
            Material.MAGMA_CREAM -> {
                // Req 3.2: Magma Cream → Number (display name parsed as Double)
                val raw = item.plainDisplayName()
                val number = raw.toDoubleOrNull()
                if (number == null) {
                    parseErrors.add(ParseError(
                        "slot $slot: '${raw}' is not a valid number",
                        blockLocation
                    ))
                    null
                } else {
                    DataContainer.Number(number)
                }
            }
            Material.IRON_INGOT -> {
                // Req 3.3: Iron Ingot → Variable (PDC ocp:var_name, fallback to display name)
                val varName = pdc?.get(KEY_DC_VAR_NAME, PersistentDataType.STRING) ?: item.plainDisplayName()
                DataContainer.Variable(varName)
            }
            Material.COMPASS -> {
                // Req 3.4: Compass → Location (PDC coordinates)
                val x     = pdc?.get(KEY_DC_LOC_X,     PersistentDataType.DOUBLE) ?: 0.0
                val y     = pdc?.get(KEY_DC_LOC_Y,     PersistentDataType.DOUBLE) ?: 0.0
                val z     = pdc?.get(KEY_DC_LOC_Z,     PersistentDataType.DOUBLE) ?: 0.0
                val world = pdc?.get(KEY_DC_LOC_WORLD, PersistentDataType.STRING) ?: ""
                val yaw   = (pdc?.get(KEY_DC_LOC_YAW,  PersistentDataType.DOUBLE) ?: 0.0).toFloat()
                val pitch = (pdc?.get(KEY_DC_LOC_PITCH, PersistentDataType.DOUBLE) ?: 0.0).toFloat()
                DataContainer.Location(x, y, z, world, yaw, pitch)
            }
            else -> null  // Req 3.7: unrecognised material → null
        }
    }

    /**
     * Reads all [DataContainer] arguments from the ParamChest located directly above [block].
     *
     * Slot index maps directly to argument index (Requirement 3.6).
     * Empty slots produce null entries (Requirement 3.7).
     *
     * @return List of [DataContainer?] in slot order (null = absent argument).
     * Requirements: 3.6, 3.7
     */
    fun readParamChestContainers(block: org.bukkit.block.Block): List<DataContainer?> {
        val above = block.getRelative(org.bukkit.block.BlockFace.UP)
        val barrel = above.state as? org.bukkit.block.Barrel ?: return emptyList()
        val contents = barrel.inventory.contents
        return contents.mapIndexed { slot, item -> readDataContainer(item, slot, block.location) }
    }

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
        if (aboveState is org.bukkit.block.Barrel) {
            val chestPdc = (aboveState as? TileState)?.persistentDataContainer
            val isParamChest = chestPdc?.get(KEY_PARAM_CHEST, PersistentDataType.STRING) == "true"
            if (isParamChest && descriptor != null) {
                return buildParamBarrelMap(aboveState, descriptor)
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
    private fun buildParamBarrelMap(barrel: org.bukkit.block.Barrel, descriptor: ActionDescriptor): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        val contents = barrel.inventory.contents
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

        // 2. Read barrel above (legacy Item_Variable detection)
        val above = block.getRelative(BlockFace.UP)
        val aboveState = above.state
        if (aboveState is org.bukkit.block.Barrel) {
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
        pdc.keys.filter { it.namespace == "ocp" && it.key != "action_id" }.forEach { key ->
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
