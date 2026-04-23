// Feature: category-based-coding-ui, Property 16: Pathfinding scanner branching
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.registry.NodeRegistry
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
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
 * Property 16: Pathfinding scanner branching
 *
 * For any T-junction in the glass strip (one path splits into two),
 * `scanStrip` must return at least two CodeLines, each containing the blocks
 * from its respective branch.
 *
 * Layout:
 *   - Start block at (0, 0, 0): BLUE_STAINED_GLASS, direction EAST
 *   - Stem: x=1..stemLen, z=0 (WHITE or GRAY glass)
 *   - Junction block at (stemLen, 0, 0): EAST is AIR, NORTH branch z=-1..-branchLen,
 *     SOUTH branch z=1..branchLen
 *   - Each branch block has a category block above it with a unique action_id
 *   - Stem blocks also have category blocks above them
 *
 * When branching occurs the scanner finalises the current nodeList (stem nodes) as one
 * CodeLine, then each branch starts fresh. So:
 *   - 1 CodeLine with stemLen nodes (stem)
 *   - 2 CodeLines each with branchLen nodes (north and south arms)
 *   - Total CodeLines = 3, total nodes = stemLen + 2 * branchLen
 *
 * **Validates: Requirements 10.5**
 */
class BranchingPropertyTest : FreeSpec({

    val world = mockk<World>(relaxed = true)
    val nodeRegistry = mockk<NodeRegistry>(relaxed = true)

    every { nodeRegistry.getActionNodeId(any()) } returns null
    every { nodeRegistry.getConditionNodeId(any()) } returns null
    every { nodeRegistry.getValueNodeId(any()) } returns null

    val scanner = BlockScanner(world, nodeRegistry)

    // Arbitrary: stem length 1..4 (keeps mock wiring manageable)
    val arbStem = Arb.int(1..4)
    // Arbitrary: branch length 1..4
    val arbBranch = Arb.int(1..4)

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
     * Build a T-junction layout:
     *
     *   Start (BLUE) at (0, 0, 0) going EAST
     *   Stem: x=1..stemLen at z=0 (glassMaterial)
     *   Junction block = last stem block at (stemLen, 0, 0):
     *     EAST → AIR (dead end ahead, forces split)
     *     NORTH → first north arm block at (stemLen, 0, -1)
     *     SOUTH → first south arm block at (stemLen, 0, +1)
     *   North arm: (stemLen, 0, -1)..(stemLen, 0, -branchLen)
     *   South arm: (stemLen, 0, +1)..(stemLen, 0, +branchLen)
     *
     * Category blocks sit at y=1 above each glass block in stem and both arms.
     *
     * Returns the start block.
     */
    fun buildTJunction(
        stemLen: Int,
        branchLen: Int,
        glassMaterial: Material,
        categoryMaterial: Material
    ): Block {
        val y = 0

        val defaultAir = mockk<Block>(relaxed = true)
        every { defaultAir.type } returns Material.AIR
        every { defaultAir.state } returns mockk(relaxed = true)

        // Start block at (0, 0, 0)
        val startBlock = mockBlock(0, y, 0, Material.BLUE_STAINED_GLASS)

        // Stem blocks: x=1..stemLen at z=0
        // The last stem block (x=stemLen) is the junction block
        val stemBlocks = (1..stemLen).map { x -> mockBlock(x, y, 0, glassMaterial) }
        val junctionBlock = stemBlocks.last()

        // North arm: (stemLen, 0, -1)..(stemLen, 0, -branchLen)
        val northArmBlocks = (1..branchLen).map { d -> mockBlock(stemLen, y, -d, glassMaterial) }

        // South arm: (stemLen, 0, +1)..(stemLen, 0, +branchLen)
        val southArmBlocks = (1..branchLen).map { d -> mockBlock(stemLen, y, d, glassMaterial) }

        // Category blocks above stem (x=1..stemLen, y=1, z=0)
        val stemCategory = (1..stemLen).map { x ->
            mockCategoryBlock(x, y + 1, 0, categoryMaterial, "stem_${x}_${glassMaterial.name}")
        }

        // Category blocks above north arm
        val northCategory = (1..branchLen).map { d ->
            mockCategoryBlock(stemLen, y + 1, -d, categoryMaterial, "north_${d}_${glassMaterial.name}")
        }

        // Category blocks above south arm
        val southCategory = (1..branchLen).map { d ->
            mockCategoryBlock(stemLen, y + 1, d, categoryMaterial, "south_${d}_${glassMaterial.name}")
        }

        // Wire start block
        val startAirAbove = mockk<Block>(relaxed = true)
        every { startAirAbove.type } returns Material.AIR
        every { startBlock.getRelative(BlockFace.UP) } returns startAirAbove
        every { startBlock.getRelative(BlockFace.EAST) } returns stemBlocks[0]
        every { startBlock.getRelative(BlockFace.WEST) } returns defaultAir
        every { startBlock.getRelative(BlockFace.NORTH) } returns defaultAir
        every { startBlock.getRelative(BlockFace.SOUTH) } returns defaultAir

        // Wire stem blocks (x=1..stemLen)
        for (i in stemBlocks.indices) {
            val block = stemBlocks[i]
            val isJunction = (i == stemBlocks.size - 1)

            // EAST: next stem block, or AIR at junction (forces split)
            every { block.getRelative(BlockFace.EAST) } returns
                if (!isJunction) stemBlocks[i + 1] else defaultAir

            // WEST: previous stem block or start block
            every { block.getRelative(BlockFace.WEST) } returns
                if (i > 0) stemBlocks[i - 1] else startBlock

            // NORTH: AIR for non-junction; first north arm block for junction
            every { block.getRelative(BlockFace.NORTH) } returns
                if (isJunction) northArmBlocks[0] else defaultAir

            // SOUTH: AIR for non-junction; first south arm block for junction
            every { block.getRelative(BlockFace.SOUTH) } returns
                if (isJunction) southArmBlocks[0] else defaultAir

            // UP: category block above
            every { block.getRelative(BlockFace.UP) } returns stemCategory[i]
        }

        // Wire north arm blocks (going NORTH: z decreasing)
        for (i in northArmBlocks.indices) {
            val block = northArmBlocks[i]
            every { block.getRelative(BlockFace.NORTH) } returns
                if (i < northArmBlocks.size - 1) northArmBlocks[i + 1] else defaultAir
            every { block.getRelative(BlockFace.SOUTH) } returns
                if (i > 0) northArmBlocks[i - 1] else junctionBlock
            every { block.getRelative(BlockFace.EAST) } returns defaultAir
            every { block.getRelative(BlockFace.WEST) } returns defaultAir
            every { block.getRelative(BlockFace.UP) } returns northCategory[i]
        }

        // Wire south arm blocks (going SOUTH: z increasing)
        for (i in southArmBlocks.indices) {
            val block = southArmBlocks[i]
            every { block.getRelative(BlockFace.SOUTH) } returns
                if (i < southArmBlocks.size - 1) southArmBlocks[i + 1] else defaultAir
            every { block.getRelative(BlockFace.NORTH) } returns
                if (i > 0) southArmBlocks[i - 1] else junctionBlock
            every { block.getRelative(BlockFace.EAST) } returns defaultAir
            every { block.getRelative(BlockFace.WEST) } returns defaultAir
            every { block.getRelative(BlockFace.UP) } returns southCategory[i]
        }

        return startBlock
    }

    // -----------------------------------------------------------------------
    // Property 16a: T-junction produces at least two CodeLines
    // -----------------------------------------------------------------------

    "Property 16a: a T-junction produces at least two CodeLines" - {
        "for any stem and branch lengths, scanStrip returns >= 2 CodeLines" {
            // Validates: Requirements 10.5
            checkAll(PropTestConfig(iterations = 100), arbStem, arbBranch, arbGlassMaterial, arbCategoryMaterial) {
                stemLen, branchLen, glassMaterial, categoryMaterial ->

                val startBlock = buildTJunction(stemLen, branchLen, glassMaterial, categoryMaterial)
                val codeLines = scanner.scanStrip(startBlock)

                codeLines shouldHaveAtLeastSize 2
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 16b: total nodes = stemLen + 2 * branchLen
    // -----------------------------------------------------------------------

    "Property 16b: total nodes across all CodeLines equals stemLen + 2 * branchLen" - {
        "the sum of all CodeLine node counts matches the expected total" {
            // Validates: Requirements 10.5
            checkAll(PropTestConfig(iterations = 100), arbStem, arbBranch, arbGlassMaterial, arbCategoryMaterial) {
                stemLen, branchLen, glassMaterial, categoryMaterial ->

                val startBlock = buildTJunction(stemLen, branchLen, glassMaterial, categoryMaterial)
                val codeLines = scanner.scanStrip(startBlock)

                val totalNodes = codeLines.sumOf { it.nodes.size }
                totalNodes shouldBe stemLen + 2 * branchLen
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 16c: each branch arm produces a CodeLine with exactly branchLen nodes
    // -----------------------------------------------------------------------

    "Property 16c: each branch arm is represented by a CodeLine with exactly branchLen nodes" - {
        "there exist at least two CodeLines each containing exactly branchLen nodes" {
            // Validates: Requirements 10.5
            checkAll(PropTestConfig(iterations = 100), arbStem, arbBranch, arbGlassMaterial, arbCategoryMaterial) {
                stemLen, branchLen, glassMaterial, categoryMaterial ->

                val startBlock = buildTJunction(stemLen, branchLen, glassMaterial, categoryMaterial)
                val codeLines = scanner.scanStrip(startBlock)

                // Both branch arms must each produce a CodeLine with exactly branchLen nodes
                val armLines = codeLines.filter { it.nodes.size == branchLen }
                armLines shouldHaveAtLeastSize 2
            }
        }
    }
})
