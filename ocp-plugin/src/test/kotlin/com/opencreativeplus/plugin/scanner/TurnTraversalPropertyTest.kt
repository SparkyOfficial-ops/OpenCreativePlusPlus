// Feature: category-based-coding-ui, Property 15: Pathfinding scanner turn following
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
 * Property 15: Pathfinding scanner turn following
 *
 * For an L-shaped glass strip (segA blocks going EAST, then segB blocks going SOUTH),
 * `scanStrip` must return a single CodeLine containing exactly segA + segB nodes
 * in traversal order.
 *
 * **Validates: Requirements 10.3**
 */
class TurnTraversalPropertyTest : FreeSpec({

    val world = mockk<World>(relaxed = true)
    val nodeRegistry = mockk<NodeRegistry>(relaxed = true)

    // All material-based lookups return null (no registered nodes by material)
    every { nodeRegistry.getActionNodeId(any()) } returns null
    every { nodeRegistry.getConditionNodeId(any()) } returns null
    every { nodeRegistry.getValueNodeId(any()) } returns null

    val scanner = BlockScanner(world, nodeRegistry)

    // Arbitrary: segment lengths 1..5 (small to keep mock wiring manageable)
    val arbSegment = Arb.int(1..5)

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
     * The block's state is NOT a TileState (no PDC action_id).
     */
    fun mockBlock(x: Int, y: Int, z: Int, material: Material): Block {
        val block = mockk<Block>(relaxed = true)
        val location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        every { block.type } returns material
        every { block.location } returns location
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
     * Build an L-shaped strip:
     *   - Start block at (0, 0, 0): BLUE_STAINED_GLASS
     *   - Straight segment: x=1..segA, z=0 going EAST ([glassMaterial])
     *   - Turn segment: x=segA, z=1..segB going SOUTH ([glassMaterial])
     *   - AIR at (segA+1, 0, 0) blocks EAST path at the corner, forcing the turn
     *   - AIR at (segA, 0, segB+1) terminates the SOUTH path
     *
     * Category blocks sit at y=1 above each glass block in both segments.
     *
     * Returns: (startBlock, straightCategoryBlocks, turnCategoryBlocks)
     */
    fun buildLShapedStrip(
        segA: Int,
        segB: Int,
        glassMaterial: Material,
        categoryMaterial: Material
    ): Triple<Block, List<Block>, List<Block>> {
        val y = 0

        val defaultAir = mockk<Block>(relaxed = true)
        every { defaultAir.type } returns Material.AIR
        every { defaultAir.state } returns mockk(relaxed = true)

        // Glass blocks: index 0 = BLUE start at (0,0,0), 1..segA = straight segment
        val straightGlass = (0..segA).map { x ->
            val mat = if (x == 0) Material.BLUE_STAINED_GLASS else glassMaterial
            mockBlock(x, y, 0, mat)
        }

        // Turn segment glass blocks: z=1..segB at x=segA
        val turnGlass = (1..segB).map { z ->
            mockBlock(segA, y, z, glassMaterial)
        }

        // Category blocks above straight segment (x=1..segA, y=1, z=0)
        val straightCategory = (1..segA).map { x ->
            mockCategoryBlock(x, y + 1, 0, categoryMaterial, "straight_${x}_${glassMaterial.name}")
        }

        // Category blocks above turn segment (x=segA, y=1, z=1..segB)
        val turnCategory = (1..segB).map { z ->
            mockCategoryBlock(segA, y + 1, z, categoryMaterial, "turn_${z}_${glassMaterial.name}")
        }

        // Wire straight segment neighbours (x=0..segA)
        for (x in 0..segA) {
            val block = straightGlass[x]

            // EAST: next straight glass, or AIR at corner (x=segA) to force turn
            val eastNeighbour = if (x < segA) straightGlass[x + 1] else defaultAir
            every { block.getRelative(BlockFace.EAST) } returns eastNeighbour

            // WEST: previous straight glass or AIR
            val westNeighbour = if (x > 0) straightGlass[x - 1] else defaultAir
            every { block.getRelative(BlockFace.WEST) } returns westNeighbour

            // NORTH: always AIR
            every { block.getRelative(BlockFace.NORTH) } returns defaultAir

            // SOUTH: AIR except at x=segA where it connects to first turn block
            val southNeighbour = if (x == segA) turnGlass[0] else defaultAir
            every { block.getRelative(BlockFace.SOUTH) } returns southNeighbour

            // UP: category block for strip blocks (x=1..segA), AIR for start (x=0)
            val aboveBlock = if (x in 1..segA) straightCategory[x - 1] else {
                val air = mockk<Block>(relaxed = true)
                every { air.type } returns Material.AIR
                air
            }
            every { block.getRelative(BlockFace.UP) } returns aboveBlock
        }

        // Wire turn segment neighbours (z=1..segB at x=segA)
        for (zIdx in 0 until segB) {
            val block = turnGlass[zIdx]

            // SOUTH: next turn glass or AIR (terminator)
            val southNeighbour = if (zIdx < segB - 1) turnGlass[zIdx + 1] else defaultAir
            every { block.getRelative(BlockFace.SOUTH) } returns southNeighbour

            // NORTH: previous turn glass or the corner straight block
            val northNeighbour = if (zIdx > 0) turnGlass[zIdx - 1] else straightGlass[segA]
            every { block.getRelative(BlockFace.NORTH) } returns northNeighbour

            // EAST and WEST: always AIR
            every { block.getRelative(BlockFace.EAST) } returns defaultAir
            every { block.getRelative(BlockFace.WEST) } returns defaultAir

            // UP: category block above
            every { block.getRelative(BlockFace.UP) } returns turnCategory[zIdx]
        }

        return Triple(straightGlass[0], straightCategory, turnCategory)
    }

    // -----------------------------------------------------------------------
    // Property 15a: L-shaped strip returns exactly one CodeLine
    // -----------------------------------------------------------------------

    "Property 15a: an L-shaped glass strip returns exactly one CodeLine" - {
        "for any segA in 1..5 and segB in 1..5 and any glass material, scanStrip returns a single CodeLine" {
            // Validates: Requirements 10.3
            checkAll(PropTestConfig(iterations = 20), arbSegment, arbSegment, arbGlassMaterial, arbCategoryMaterial) {
                segA, segB, glassMaterial, categoryMaterial ->

                val (startBlock, _, _) = buildLShapedStrip(segA, segB, glassMaterial, categoryMaterial)
                val codeLines = scanner.scanStrip(startBlock)

                codeLines.size shouldBe 1
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 15b: the single CodeLine contains exactly segA + segB nodes
    // -----------------------------------------------------------------------

    "Property 15b: the CodeLine contains exactly segA + segB nodes" - {
        "for any segA and segB, the CodeLine has exactly segA + segB ScannedNodes" {
            // Validates: Requirements 10.3
            checkAll(PropTestConfig(iterations = 20), arbSegment, arbSegment, arbGlassMaterial, arbCategoryMaterial) {
                segA, segB, glassMaterial, categoryMaterial ->

                val (startBlock, _, _) = buildLShapedStrip(segA, segB, glassMaterial, categoryMaterial)
                val codeLines = scanner.scanStrip(startBlock)

                codeLines[0].nodes.size shouldBe segA + segB
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 15c: nodes are in traversal order
    // -----------------------------------------------------------------------

    "Property 15c: nodes are in traversal order — straight segment first, then turn segment" - {
        "first segA nodes have z=0 and x=1..segA; next segB nodes have x=segA and z=1..segB" {
            // Validates: Requirements 10.3
            checkAll(PropTestConfig(iterations = 20), arbSegment, arbSegment, arbGlassMaterial, arbCategoryMaterial) {
                segA, segB, glassMaterial, categoryMaterial ->

                val (startBlock, straightCategory, turnCategory) =
                    buildLShapedStrip(segA, segB, glassMaterial, categoryMaterial)
                val codeLines = scanner.scanStrip(startBlock)
                val nodes = codeLines[0].nodes

                nodes.size shouldBe segA + segB

                // First segA nodes: straight segment (z=0, x=1..segA)
                for (i in 0 until segA) {
                    nodes[i].location.blockX shouldBe straightCategory[i].location.blockX
                    nodes[i].location.blockZ shouldBe 0
                }

                // Next segB nodes: turn segment (x=segA, z=1..segB)
                for (j in 0 until segB) {
                    nodes[segA + j].location.blockX shouldBe segA
                    nodes[segA + j].location.blockZ shouldBe turnCategory[j].location.blockZ
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 15d: mixed WHITE and GRAY glass in both segments
    // -----------------------------------------------------------------------

    "Property 15d: mixed WHITE and GRAY glass in both segments still produces 1 CodeLine with segA+segB nodes" - {
        "alternating glass materials across both segments are traversed fully" {
            // Validates: Requirements 10.3
            checkAll(PropTestConfig(iterations = 20), arbSegment, arbSegment, arbCategoryMaterial) {
                segA, segB, categoryMaterial ->

                val y = 0
                val defaultAir = mockk<Block>(relaxed = true)
                every { defaultAir.type } returns Material.AIR
                every { defaultAir.state } returns mockk(relaxed = true)

                // Straight glass with alternating materials
                val straightGlass = (0..segA).map { x ->
                    val mat = when {
                        x == 0 -> Material.BLUE_STAINED_GLASS
                        x % 2 == 1 -> Material.WHITE_STAINED_GLASS
                        else -> Material.GRAY_STAINED_GLASS
                    }
                    mockBlock(x, y, 0, mat)
                }

                // Turn glass with alternating materials
                val turnGlass = (1..segB).map { z ->
                    val mat = if (z % 2 == 1) Material.GRAY_STAINED_GLASS else Material.WHITE_STAINED_GLASS
                    mockBlock(segA, y, z, mat)
                }

                val straightCategory = (1..segA).map { x ->
                    mockCategoryBlock(x, y + 1, 0, categoryMaterial, "mixed_straight_$x")
                }
                val turnCategory = (1..segB).map { z ->
                    mockCategoryBlock(segA, y + 1, z, categoryMaterial, "mixed_turn_$z")
                }

                // Wire straight segment
                for (x in 0..segA) {
                    val block = straightGlass[x]
                    every { block.getRelative(BlockFace.EAST) } returns
                        if (x < segA) straightGlass[x + 1] else defaultAir
                    every { block.getRelative(BlockFace.WEST) } returns
                        if (x > 0) straightGlass[x - 1] else defaultAir
                    every { block.getRelative(BlockFace.NORTH) } returns defaultAir
                    every { block.getRelative(BlockFace.SOUTH) } returns
                        if (x == segA) turnGlass[0] else defaultAir
                    val aboveBlock = if (x in 1..segA) straightCategory[x - 1] else {
                        val air = mockk<Block>(relaxed = true)
                        every { air.type } returns Material.AIR
                        air
                    }
                    every { block.getRelative(BlockFace.UP) } returns aboveBlock
                }

                // Wire turn segment
                for (zIdx in 0 until segB) {
                    val block = turnGlass[zIdx]
                    every { block.getRelative(BlockFace.SOUTH) } returns
                        if (zIdx < segB - 1) turnGlass[zIdx + 1] else defaultAir
                    every { block.getRelative(BlockFace.NORTH) } returns
                        if (zIdx > 0) turnGlass[zIdx - 1] else straightGlass[segA]
                    every { block.getRelative(BlockFace.EAST) } returns defaultAir
                    every { block.getRelative(BlockFace.WEST) } returns defaultAir
                    every { block.getRelative(BlockFace.UP) } returns turnCategory[zIdx]
                }

                val codeLines = scanner.scanStrip(straightGlass[0])

                codeLines.size shouldBe 1
                codeLines[0].nodes.size shouldBe segA + segB
            }
        }
    }
})
