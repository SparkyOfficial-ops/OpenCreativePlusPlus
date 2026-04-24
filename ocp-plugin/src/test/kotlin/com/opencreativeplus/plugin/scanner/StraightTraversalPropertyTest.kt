// Feature: category-based-coding-ui, Property 14: Pathfinding scanner straight traversal
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.registry.NodeRegistry
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.TileState
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

/**
 * Property 14: Pathfinding scanner straight traversal
 *
 * For any straight glass strip of length N (all WHITE_STAINED_GLASS or GRAY_STAINED_GLASS),
 * `scanStrip` must return a single CodeLine containing exactly the N blocks above the strip
 * in traversal order.
 *
 * **Validates: Requirements 10.2, 10.7**
 */
class StraightTraversalPropertyTest : FreeSpec({

    val world = mockk<World>(relaxed = true)
    val nodeRegistry = mockk<NodeRegistry>(relaxed = true)

    // All material-based lookups return null (no registered nodes by material)
    every { nodeRegistry.getActionNodeId(any()) } returns null
    every { nodeRegistry.getConditionNodeId(any()) } returns null
    every { nodeRegistry.getValueNodeId(any()) } returns null

    val scanner = BlockScanner(world, nodeRegistry)

    // Arbitrary: strip length 1..10
    val arbLength = Arb.int(1..10)

    // Arbitrary: glass strip material (WHITE or GRAY)
    val arbGlassMaterial = Arb.element(
        listOf(Material.WHITE_STAINED_GLASS, Material.GRAY_STAINED_GLASS)
    )

    // Arbitrary: category block material placed above each glass block
    val arbCategoryMaterial = Arb.element(
        listOf(
            Material.COBBLESTONE,
            Material.STONE_BRICKS,
            Material.GOLD_BLOCK,
            Material.IRON_BLOCK,
            Material.OAK_PLANKS,
            Material.DIAMOND_BLOCK
        )
    )

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Build a mock block at (x, y, z) with the given material.
     * The block's state is NOT a TileState (no PDC action_id), so buildScannedNode
     * falls back to the Material-based registry path (which returns null nodeId).
     */
    fun mockBlock(x: Int, y: Int, z: Int, material: Material): Block {
        val block = mockk<Block>(relaxed = true)
        val location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        every { block.type } returns material
        every { block.location } returns location
        // Not a TileState → readActionId returns null (Material fallback path)
        every { block.state } returns mockk(relaxed = true)
        return block
    }

    /**
     * Build a mock block that has `ocp:action_id` in its PDC so that
     * buildScannedNode recognises it as a category block and includes it in the CodeLine.
     * The action_id is registered in nodeRegistry so the block is NOT skipped.
     */
    fun mockCategoryBlock(x: Int, y: Int, z: Int, material: Material, actionId: String): Block {
        val block = mockk<Block>(relaxed = true)
        val location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        every { block.type } returns material
        every { block.location } returns location

        // PDC with action_id
        val tileState = mockk<TileState>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { block.state } returns tileState
        every { tileState.persistentDataContainer } returns pdc

        val actionIdKey = org.bukkit.NamespacedKey("ocp", "action_id")
        every { pdc.get(actionIdKey, PersistentDataType.STRING) } returns actionId

        // Register the action_id in nodeRegistry
        val dummyFactory: (Map<String, Any>) -> com.opencreativeplus.api.node.IAction = {
            mockk(relaxed = true)
        }
        every { nodeRegistry.getActionFactoryById(actionId) } returns dummyFactory

        // No signs on horizontal faces, no chest above (for extractParameters)
        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val air = mockk<Block>(relaxed = true)
            every { air.state } returns mockk(relaxed = true)
            every { block.getRelative(face) } returns air
        }
        val airAbove = mockk<Block>(relaxed = true)
        every { airAbove.type } returns Material.AIR
        every { airAbove.state } returns mockk(relaxed = true)
        every { block.getRelative(BlockFace.UP) } returns airAbove

        return block
    }

    /**
     * Build a straight glass strip of length [n] starting at x=0, y=0, z=0 going EAST.
     *
     * Layout (all at y=0):
     *   x=0: BLUE_STAINED_GLASS (start block)
     *   x=1..n: [glassMaterial] (strip blocks)
     *   x=n+1: AIR (terminates the strip)
     *
     * Above each strip block at x=1..n there is a category block at y=1.
     * The start block (x=0) has AIR above it (no node collected there).
     *
     * Returns: (startBlock, list of category blocks in traversal order)
     */
    fun buildStraightStrip(
        n: Int,
        glassMaterial: Material,
        categoryMaterial: Material
    ): Pair<Block, List<Block>> {
        val y = 0

        // Air block used as a default for unspecified neighbours
        val defaultAir = mockk<Block>(relaxed = true)
        every { defaultAir.type } returns Material.AIR
        every { defaultAir.state } returns mockk(relaxed = true)

        // Build all glass blocks: index 0 = BLUE start, 1..n = strip glass
        val glassBlocks = (0..n).map { x ->
            val mat = if (x == 0) Material.BLUE_STAINED_GLASS else glassMaterial
            mockBlock(x, y, 0, mat)
        }

        // Build category blocks above each strip glass (x=1..n)
        val categoryBlocks = (1..n).map { x ->
            mockCategoryBlock(x, y + 1, 0, categoryMaterial, "action_${x}_${glassMaterial.name}")
        }

        // Wire up neighbours for each glass block
        for (x in 0..n) {
            val block = glassBlocks[x]

            // EAST neighbour: next glass block or AIR (terminator)
            val eastNeighbour = if (x < n) glassBlocks[x + 1] else defaultAir
            every { block.getRelative(BlockFace.EAST) } returns eastNeighbour

            // WEST neighbour: previous glass block or AIR
            val westNeighbour = if (x > 0) glassBlocks[x - 1] else defaultAir
            every { block.getRelative(BlockFace.WEST) } returns westNeighbour

            // NORTH and SOUTH: always AIR (straight strip, no turns)
            every { block.getRelative(BlockFace.NORTH) } returns defaultAir
            every { block.getRelative(BlockFace.SOUTH) } returns defaultAir

            // UP: category block for strip blocks (x=1..n), AIR for start (x=0)
            val aboveBlock = if (x in 1..n) categoryBlocks[x - 1] else {
                val air = mockk<Block>(relaxed = true)
                every { air.type } returns Material.AIR
                air
            }
            every { block.getRelative(BlockFace.UP) } returns aboveBlock
        }

        return glassBlocks[0] to categoryBlocks
    }

    // -----------------------------------------------------------------------
    // Property 14a: straight strip returns exactly one CodeLine
    // -----------------------------------------------------------------------

    "Property 14a: a straight glass strip returns exactly one CodeLine" - {
        "for any strip length N and any glass material, scanStrip returns a single CodeLine" {
            // Validates: Requirements 10.2
            checkAll(PropTestConfig(iterations = 20), arbLength, arbGlassMaterial, arbCategoryMaterial) {
                n, glassMaterial, categoryMaterial ->

                val (startBlock, _) = buildStraightStrip(n, glassMaterial, categoryMaterial)
                val codeLines = scanner.scanStrip(startBlock)

                codeLines.size shouldBe 1
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 14b: the single CodeLine contains exactly N nodes
    // -----------------------------------------------------------------------

    "Property 14b: the CodeLine contains exactly N nodes (one per strip glass block)" - {
        "for any strip length N, the CodeLine has exactly N ScannedNodes" {
            // Validates: Requirements 10.7
            checkAll(PropTestConfig(iterations = 20), arbLength, arbGlassMaterial, arbCategoryMaterial) {
                n, glassMaterial, categoryMaterial ->

                val (startBlock, _) = buildStraightStrip(n, glassMaterial, categoryMaterial)
                val codeLines = scanner.scanStrip(startBlock)

                codeLines[0].nodes.size shouldBe n
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 14c: nodes appear in traversal order (EAST direction)
    // -----------------------------------------------------------------------

    "Property 14c: nodes are collected in traversal order (left to right along the strip)" - {
        "for any strip length N, node at index i corresponds to glass block at x=i+1" {
            // Validates: Requirements 10.7
            checkAll(PropTestConfig(iterations = 20), arbLength, arbGlassMaterial, arbCategoryMaterial) {
                n, glassMaterial, categoryMaterial ->

                val (startBlock, categoryBlocks) = buildStraightStrip(n, glassMaterial, categoryMaterial)
                val codeLines = scanner.scanStrip(startBlock)
                val nodes = codeLines[0].nodes

                nodes.size shouldBe n
                nodes.forEachIndexed { idx, node ->
                    // Each node's location X should match the corresponding category block
                    node.location.blockX shouldBe categoryBlocks[idx].location.blockX
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 14d: mixed WHITE and GRAY glass in the same strip
    // -----------------------------------------------------------------------

    "Property 14d: a strip mixing WHITE_STAINED_GLASS and GRAY_STAINED_GLASS is traversed fully" - {
        "for any strip length N with alternating glass types, scanStrip returns one CodeLine with N nodes" {
            // Validates: Requirements 10.2, 10.7
            checkAll(PropTestConfig(iterations = 20), arbLength, arbCategoryMaterial) {
                n, categoryMaterial ->

                // Build strip manually with alternating glass materials
                val y = 0
                val defaultAir = mockk<Block>(relaxed = true)
                every { defaultAir.type } returns Material.AIR
                every { defaultAir.state } returns mockk(relaxed = true)

                val glassBlocks = (0..n).map { x ->
                    val mat = when {
                        x == 0 -> Material.BLUE_STAINED_GLASS
                        x % 2 == 1 -> Material.WHITE_STAINED_GLASS
                        else -> Material.GRAY_STAINED_GLASS
                    }
                    mockBlock(x, y, 0, mat)
                }

                val categoryBlocks = (1..n).map { x ->
                    mockCategoryBlock(x, y + 1, 0, categoryMaterial, "mixed_action_$x")
                }

                for (x in 0..n) {
                    val block = glassBlocks[x]
                    every { block.getRelative(BlockFace.EAST) } returns
                        if (x < n) glassBlocks[x + 1] else defaultAir
                    every { block.getRelative(BlockFace.WEST) } returns
                        if (x > 0) glassBlocks[x - 1] else defaultAir
                    every { block.getRelative(BlockFace.NORTH) } returns defaultAir
                    every { block.getRelative(BlockFace.SOUTH) } returns defaultAir
                    val aboveBlock = if (x in 1..n) categoryBlocks[x - 1] else {
                        val air = mockk<Block>(relaxed = true)
                        every { air.type } returns Material.AIR
                        air
                    }
                    every { block.getRelative(BlockFace.UP) } returns aboveBlock
                }

                val codeLines = scanner.scanStrip(glassBlocks[0])

                codeLines.size shouldBe 1
                codeLines[0].nodes.size shouldBe n
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 14e: AIR above glass blocks produces zero nodes in the CodeLine
    // -----------------------------------------------------------------------

    "Property 14e: glass blocks with AIR above contribute no nodes to the CodeLine" - {
        "for any strip length N where all blocks above are AIR, the CodeLine has 0 nodes" {
            // Validates: Requirements 10.7 (only non-AIR blocks above are collected)
            checkAll(PropTestConfig(iterations = 20), arbLength, arbGlassMaterial) {
                n, glassMaterial ->

                val y = 0
                val defaultAir = mockk<Block>(relaxed = true)
                every { defaultAir.type } returns Material.AIR
                every { defaultAir.state } returns mockk(relaxed = true)

                val glassBlocks = (0..n).map { x ->
                    val mat = if (x == 0) Material.BLUE_STAINED_GLASS else glassMaterial
                    mockBlock(x, y, 0, mat)
                }

                for (x in 0..n) {
                    val block = glassBlocks[x]
                    every { block.getRelative(BlockFace.EAST) } returns
                        if (x < n) glassBlocks[x + 1] else defaultAir
                    every { block.getRelative(BlockFace.WEST) } returns
                        if (x > 0) glassBlocks[x - 1] else defaultAir
                    every { block.getRelative(BlockFace.NORTH) } returns defaultAir
                    every { block.getRelative(BlockFace.SOUTH) } returns defaultAir
                    // All blocks above are AIR
                    val airAbove = mockk<Block>(relaxed = true)
                    every { airAbove.type } returns Material.AIR
                    every { block.getRelative(BlockFace.UP) } returns airAbove
                }

                val codeLines = scanner.scanStrip(glassBlocks[0])

                codeLines.size shouldBe 1
                codeLines[0].nodes.size shouldBe 0
            }
        }
    }
})
