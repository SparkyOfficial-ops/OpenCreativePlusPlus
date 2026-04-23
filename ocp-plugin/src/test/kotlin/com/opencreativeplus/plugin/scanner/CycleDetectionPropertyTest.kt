// Feature: category-based-coding-ui, Property 17: Pathfinding scanner cycle detection
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.registry.NodeRegistry
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
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
 * Property 17: Pathfinding scanner cycle detection
 *
 * For any glass layout that contains a cycle (loop), `scanStrip` must terminate
 * and must not visit any glass block more than once per traversal.
 *
 * **Validates: Requirements 10.6**
 */
class CycleDetectionPropertyTest : FreeSpec({

    val world = mockk<World>(relaxed = true)
    val nodeRegistry = mockk<NodeRegistry>(relaxed = true)

    every { nodeRegistry.getActionNodeId(any()) } returns null
    every { nodeRegistry.getConditionNodeId(any()) } returns null
    every { nodeRegistry.getValueNodeId(any()) } returns null

    val scanner = BlockScanner(world, nodeRegistry)

    // Ring sizes 3..6 — small enough to wire quickly, large enough to be meaningful
    val arbRingSize = Arb.int(3..6)

    val arbGlassMaterial = Arb.element(
        listOf(Material.WHITE_STAINED_GLASS, Material.GRAY_STAINED_GLASS)
    )

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

    fun mockBlock(x: Int, y: Int, z: Int, material: Material): Block {
        val block = mockk<Block>(relaxed = true)
        val location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        every { block.type } returns material
        every { block.location } returns location
        every { block.state } returns mockk(relaxed = true)
        return block
    }

    fun mockCategoryBlock(x: Int, y: Int, z: Int, material: Material, actionId: String): Block {
        val block = mockk<Block>(relaxed = true)
        val location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        every { block.type } returns material
        every { block.location } returns location

        val tileState = mockk<TileState>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { block.state } returns tileState
        every { tileState.persistentDataContainer } returns pdc

        val actionIdKey = org.bukkit.NamespacedKey("ocp", "action_id")
        every { pdc.get(actionIdKey, PersistentDataType.STRING) } returns actionId

        val dummyFactory: (Map<String, Any>) -> com.opencreativeplus.api.node.IAction = {
            mockk(relaxed = true)
        }
        every { nodeRegistry.getActionFactoryById(actionId) } returns dummyFactory

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
     * Build a linear ring of [ringSize] glass blocks laid out along the X axis,
     * with the last block connected back to the first to form a cycle.
     *
     * Layout (y=0):
     *   x=0: BLUE_STAINED_GLASS (start)
     *   x=1..ringSize-1: glassMaterial
     *   x=ringSize-1 EAST → x=0 (closing the loop)
     *
     * Each glass block has a category block above it at y=1.
     */
    fun buildRing(
        ringSize: Int,
        glassMaterial: Material,
        categoryMaterial: Material
    ): Block {
        val y = 0
        val defaultAir = mockk<Block>(relaxed = true)
        every { defaultAir.type } returns Material.AIR
        every { defaultAir.state } returns mockk(relaxed = true)

        val glassBlocks = (0 until ringSize).map { x ->
            val mat = if (x == 0) Material.BLUE_STAINED_GLASS else glassMaterial
            mockBlock(x, y, 0, mat)
        }

        val categoryBlocks = (0 until ringSize).map { x ->
            mockCategoryBlock(x, y + 1, 0, categoryMaterial, "ring_action_${x}_${glassMaterial.name}")
        }

        for (i in glassBlocks.indices) {
            val block = glassBlocks[i]
            val prev = glassBlocks[(i - 1 + ringSize) % ringSize]
            val next = glassBlocks[(i + 1) % ringSize]

            // EAST → next in ring (wraps around)
            every { block.getRelative(BlockFace.EAST) } returns next
            // WEST → previous in ring (wraps around)
            every { block.getRelative(BlockFace.WEST) } returns prev
            // NORTH and SOUTH → AIR
            every { block.getRelative(BlockFace.NORTH) } returns defaultAir
            every { block.getRelative(BlockFace.SOUTH) } returns defaultAir
            // UP → category block
            every { block.getRelative(BlockFace.UP) } returns categoryBlocks[i]
        }

        return glassBlocks[0]
    }

    // -----------------------------------------------------------------------
    // Property 17a: scanStrip terminates on a cyclic layout
    // -----------------------------------------------------------------------

    "Property 17a: scanStrip terminates on a ring layout (does not loop forever)" - {
        "for any ring size, scanStrip returns a result without hanging" {
            // Validates: Requirements 10.6
            checkAll(PropTestConfig(iterations = 20), arbRingSize, arbGlassMaterial, arbCategoryMaterial) {
                ringSize, glassMaterial, categoryMaterial ->

                val startBlock = buildRing(ringSize, glassMaterial, categoryMaterial)
                // If scanStrip loops forever this test will time out — termination is the property
                val codeLines = scanner.scanStrip(startBlock)

                // Must return at least one CodeLine (even if empty)
                codeLines.size shouldBe 1
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 17b: no glass block is visited more than once
    // -----------------------------------------------------------------------

    "Property 17b: no node location appears more than once across all CodeLines" - {
        "for any ring layout, collected node locations are all distinct" {
            // Validates: Requirements 10.6
            checkAll(PropTestConfig(iterations = 20), arbRingSize, arbGlassMaterial, arbCategoryMaterial) {
                ringSize, glassMaterial, categoryMaterial ->

                val startBlock = buildRing(ringSize, glassMaterial, categoryMaterial)
                val codeLines = scanner.scanStrip(startBlock)

                val allNodeLocations = codeLines.flatMap { it.nodes }.map { it.location }
                val distinctLocations = allNodeLocations.distinctBy { "${it.blockX},${it.blockY},${it.blockZ}" }

                // No duplicates: distinct count must equal total count
                distinctLocations.size shouldBe allNodeLocations.size
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 17c: total nodes collected <= ringSize (cycle does not inflate count)
    // -----------------------------------------------------------------------

    "Property 17c: total nodes collected across all CodeLines is at most ringSize" - {
        "the cycle does not cause the same block to be collected multiple times" {
            // Validates: Requirements 10.6
            checkAll(PropTestConfig(iterations = 20), arbRingSize, arbGlassMaterial, arbCategoryMaterial) {
                ringSize, glassMaterial, categoryMaterial ->

                val startBlock = buildRing(ringSize, glassMaterial, categoryMaterial)
                val codeLines = scanner.scanStrip(startBlock)

                val totalNodes = codeLines.sumOf { it.nodes.size }
                // At most ringSize nodes (one per glass block above which a category block sits)
                (totalNodes <= ringSize) shouldBe true
            }
        }
    }
})
