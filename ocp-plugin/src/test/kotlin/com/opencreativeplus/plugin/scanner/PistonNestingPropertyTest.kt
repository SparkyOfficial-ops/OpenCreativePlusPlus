@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-visual-programming-platform, Property 5: Вложенные скобки до глубины 16

package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.registry.NodeRegistry
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.TileState
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

/**
 * Property 5: Вложенные скобки до глубины 16
 *
 * For any valid program with nested piston scopes at depths 0..16,
 * `BlockScanner.scanStrip()` must:
 * 1. Return a non-empty list of CodeLines without any parse errors
 * 2. The CodeLine's `children` list must be non-empty when piston scopes are present
 * 3. Nesting at exactly depth 16 must succeed (no parse errors)
 * 4. Nesting at depth > 16 must produce a parse error
 *
 * **Validates: Requirements 2.7**
 */
class PistonNestingPropertyTest : FreeSpec({

    val world = mockk<World>(relaxed = true).also {
        every { it.name } returns "test_world"
        every { it.hashCode() } returns 42
        // scanStrip uses getRelativeSafe which checks isChunkLoaded
        every { it.isChunkLoaded(any<Int>(), any<Int>()) } returns true
    }
    val nodeRegistry = mockk<NodeRegistry>(relaxed = true)

    every { nodeRegistry.getActionNodeId(any()) } returns null
    every { nodeRegistry.getConditionNodeId(any()) } returns null
    every { nodeRegistry.getValueNodeId(any()) } returns null

    // Dummy factories for condition and action nodes
    val dummyConditionFactory: (Map<String, Any>) -> ICondition = { mockk(relaxed = true) }
    val dummyActionFactory: (Map<String, Any>) -> IAction = { mockk(relaxed = true) }

    every { nodeRegistry.getConditionFactoryById("if_player") } returns dummyConditionFactory
    every { nodeRegistry.getActionFactoryById("inner_action") } returns dummyActionFactory
    every { nodeRegistry.getActionFactoryById("outer_action") } returns dummyActionFactory

    val scanner = BlockScanner(world, nodeRegistry)

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    val defaultAir: Block = mockk<Block>(relaxed = true).also {
        every { it.type } returns Material.AIR
        every { it.state } returns mockk(relaxed = true)
        every { it.location } returns Location(world, -999.0, -999.0, -999.0)
    }

    /**
     * Build a plain glass block at (x, y, z) with the given material.
     * State is NOT a TileState (no PDC).
     */
    fun mockGlass(x: Int, y: Int, z: Int, material: Material): Block {
        val block = mockk<Block>(relaxed = true)
        every { block.type } returns material
        every { block.location } returns Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        every { block.state } returns mockk(relaxed = true)
        return block
    }

    /**
     * Build a conditional node block (OAK_PLANKS) with `ocp:action_id = "if_player"` in PDC.
     * This is the block placed ABOVE a glass block to trigger piston scope scanning.
     */
    fun mockConditionalNode(x: Int, y: Int, z: Int): Block {
        val block = mockk<Block>(relaxed = true)
        every { block.type } returns Material.OAK_PLANKS
        every { block.location } returns Location(world, x.toDouble(), y.toDouble(), z.toDouble())

        val tileState = mockk<TileState>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { block.state } returns tileState
        every { tileState.persistentDataContainer } returns pdc

        val actionIdKey = NamespacedKey("ocp", "action_id")
        every { pdc.get(actionIdKey, PersistentDataType.STRING) } returns "if_player"

        // No horizontal neighbours (not a glass block, so no getRelative needed for traversal)
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
     * Build an action node block (COBBLESTONE) with `ocp:action_id = actionId` in PDC.
     * Used for nodes inside or after piston scopes.
     */
    fun mockActionNode(x: Int, y: Int, z: Int, actionId: String): Block {
        val block = mockk<Block>(relaxed = true)
        every { block.type } returns Material.COBBLESTONE
        every { block.location } returns Location(world, x.toDouble(), y.toDouble(), z.toDouble())

        val tileState = mockk<TileState>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { block.state } returns tileState
        every { tileState.persistentDataContainer } returns pdc

        val actionIdKey = NamespacedKey("ocp", "action_id")
        every { pdc.get(actionIdKey, PersistentDataType.STRING) } returns actionId

        every { nodeRegistry.getActionFactoryById(actionId) } returns dummyActionFactory

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
     * Build a STICKY_PISTON block (opening bracket) above a glass block.
     */
    fun mockStickyPiston(x: Int, y: Int, z: Int): Block {
        val block = mockk<Block>(relaxed = true)
        every { block.type } returns Material.STICKY_PISTON
        every { block.location } returns Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        every { block.state } returns mockk(relaxed = true)
        return block
    }

    /**
     * Build a PISTON block (closing bracket) above a glass block.
     */
    fun mockPiston(x: Int, y: Int, z: Int): Block {
        val block = mockk<Block>(relaxed = true)
        every { block.type } returns Material.PISTON
        every { block.location } returns Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        every { block.state } returns mockk(relaxed = true)
        return block
    }

    /**
     * Build a strip with [depth] levels of nested piston scopes.
     *
     * The layout is a flat strip going EAST where:
     * - Position 0: BLUE_STAINED_GLASS (start)
     * - Position 1: GLASS + conditional node above (OAK_PLANKS with if_player)
     *   → triggers scanChildBranch when next block has STICKY_PISTON above
     * - Position 2: GLASS + STICKY_PISTON above (depth=1 opening)
     * - Position 3: GLASS + STICKY_PISTON above (depth=2 opening, if depth >= 2)
     * - ...
     * - Position 2+depth-1: GLASS + STICKY_PISTON above (depth=depth opening)
     * - Position 2+depth: GLASS + inner action above
     * - Position 2+depth+1: GLASS + PISTON above (depth=depth→depth-1 closing)
     * - ...
     * - Position 2+depth+depth: GLASS + PISTON above (depth=1→0 closing)
     * - Position 2+depth+depth+1: GLASS + outer action above (after scope)
     *
     * Total glass blocks: 2 + depth + 1 + depth + 1 = 2*depth + 4
     *
     * Returns the start block (BLUE_STAINED_GLASS at position 0).
     */
    fun buildNestedPistonStrip(depth: Int): Block {
        val y = 0
        val totalBlocks = 2 * depth + 4  // start + conditional + depth*open + inner + depth*close + outer

        // Create all glass blocks
        val glassBlocks = (0 until totalBlocks).map { x ->
            val mat = if (x == 0) Material.BLUE_STAINED_GLASS else Material.WHITE_STAINED_GLASS
            mockGlass(x, y, 0, mat)
        }

        // Wire horizontal neighbours for all glass blocks
        for (i in glassBlocks.indices) {
            val block = glassBlocks[i]
            every { block.getRelative(BlockFace.EAST) } returns
                if (i < totalBlocks - 1) glassBlocks[i + 1] else defaultAir
            every { block.getRelative(BlockFace.WEST) } returns
                if (i > 0) glassBlocks[i - 1] else defaultAir
            every { block.getRelative(BlockFace.NORTH) } returns defaultAir
            every { block.getRelative(BlockFace.SOUTH) } returns defaultAir
        }

        // Position 0: BLUE_STAINED_GLASS — AIR above (no node collected at start)
        val airAboveStart = mockk<Block>(relaxed = true)
        every { airAboveStart.type } returns Material.AIR
        every { airAboveStart.state } returns mockk(relaxed = true)
        every { glassBlocks[0].getRelative(BlockFace.UP) } returns airAboveStart

        // Position 1: conditional node above (OAK_PLANKS with if_player)
        val conditionalNode = mockConditionalNode(1, y + 1, 0)
        every { glassBlocks[1].getRelative(BlockFace.UP) } returns conditionalNode

        // Positions 2..2+depth-1: STICKY_PISTON above each (opening brackets)
        for (i in 0 until depth) {
            val pos = 2 + i
            val stickyPiston = mockStickyPiston(pos, y + 1, 0)
            every { glassBlocks[pos].getRelative(BlockFace.UP) } returns stickyPiston
        }

        // Position 2+depth: inner action node above
        val innerPos = 2 + depth
        val innerAction = mockActionNode(innerPos, y + 1, 0, "inner_action_$depth")
        every { nodeRegistry.getActionFactoryById("inner_action_$depth") } returns dummyActionFactory
        every { glassBlocks[innerPos].getRelative(BlockFace.UP) } returns innerAction

        // Positions 2+depth+1..2+depth+depth: PISTON above each (closing brackets)
        for (i in 0 until depth) {
            val pos = 2 + depth + 1 + i
            val piston = mockPiston(pos, y + 1, 0)
            every { glassBlocks[pos].getRelative(BlockFace.UP) } returns piston
        }

        // Position 2+depth+depth+1: outer action node above (after scope)
        val outerPos = 2 + depth + depth + 1
        val outerAction = mockActionNode(outerPos, y + 1, 0, "outer_action_$depth")
        every { nodeRegistry.getActionFactoryById("outer_action_$depth") } returns dummyActionFactory
        every { glassBlocks[outerPos].getRelative(BlockFace.UP) } returns outerAction

        return glassBlocks[0]
    }

    /**
     * Build a plain strip with no piston scopes (depth=0).
     * Layout: BLUE_GLASS → GLASS+action → GLASS+action → end
     */
    fun buildPlainStrip(length: Int): Block {
        val y = 0
        val totalBlocks = length + 1  // start + length action blocks

        val glassBlocks = (0 until totalBlocks).map { x ->
            val mat = if (x == 0) Material.BLUE_STAINED_GLASS else Material.WHITE_STAINED_GLASS
            mockGlass(x, y, 0, mat)
        }

        for (i in glassBlocks.indices) {
            val block = glassBlocks[i]
            every { block.getRelative(BlockFace.EAST) } returns
                if (i < totalBlocks - 1) glassBlocks[i + 1] else defaultAir
            every { block.getRelative(BlockFace.WEST) } returns
                if (i > 0) glassBlocks[i - 1] else defaultAir
            every { block.getRelative(BlockFace.NORTH) } returns defaultAir
            every { block.getRelative(BlockFace.SOUTH) } returns defaultAir
        }

        // Start block: AIR above
        val airAboveStart = mockk<Block>(relaxed = true)
        every { airAboveStart.type } returns Material.AIR
        every { airAboveStart.state } returns mockk(relaxed = true)
        every { glassBlocks[0].getRelative(BlockFace.UP) } returns airAboveStart

        // Each subsequent block: action node above
        for (i in 1 until totalBlocks) {
            val actionId = "plain_action_${i}_$length"
            val actionNode = mockActionNode(i, y + 1, 0, actionId)
            every { nodeRegistry.getActionFactoryById(actionId) } returns dummyActionFactory
            every { glassBlocks[i].getRelative(BlockFace.UP) } returns actionNode
        }

        return glassBlocks[0]
    }

    // -----------------------------------------------------------------------
    // Property 5: Вложенные скобки до глубины 16
    // -----------------------------------------------------------------------

    "Property 5: Вложенные скобки до глубины 16" - {

        /**
         * For any depth 1..16, scanStrip must return at least one CodeLine
         * with no parse errors, and the CodeLine must have at least one child
         * (the piston scope).
         *
         * **Validates: Requirements 2.7**
         */
        "depth 1..16 → no parse errors and CodeLine has children" {
            // Validates: Requirements 2.7
            checkAll(PropTestConfig(iterations = 100), Arb.int(1..16)) { depth ->
                val startBlock = buildNestedPistonStrip(depth)
                val codeLines = scanner.scanStrip(startBlock)
                scanner.parseErrors shouldBe emptyList()
                codeLines shouldHaveAtLeastSize 1
                codeLines[0].children shouldHaveAtLeastSize 1
            }
        }

        /**
         * A plain strip with no piston scopes (depth=0) must always succeed
         * with no parse errors.
         *
         * **Validates: Requirements 2.7**
         */
        "depth 0 (no piston scope) → no parse errors" {
            // Validates: Requirements 2.7
            checkAll(PropTestConfig(iterations = 100), Arb.int(1..8)) { length ->
                val startBlock = buildPlainStrip(length)
                val codeLines = scanner.scanStrip(startBlock)
                scanner.parseErrors shouldBe emptyList()
                codeLines shouldHaveAtLeastSize 1
            }
        }

        /**
         * Nesting at exactly depth 16 must succeed (boundary condition).
         *
         * **Validates: Requirements 2.7**
         */
        "exactly depth 16 → no parse errors (boundary)" {
            // Validates: Requirements 2.7
            val startBlock = buildNestedPistonStrip(16)
            val codeLines = scanner.scanStrip(startBlock)
            scanner.parseErrors shouldBe emptyList()
            codeLines shouldHaveAtLeastSize 1
            codeLines[0].children shouldHaveAtLeastSize 1
        }

        /**
         * Nesting at depth > 16 must produce at least one parse error.
         *
         * **Validates: Requirements 2.7**
         */
        "depth > 16 → parse error is reported" {
            // Validates: Requirements 2.7
            val startBlock = buildNestedPistonStrip(17)
            scanner.scanStrip(startBlock)
            scanner.parseErrors shouldHaveAtLeastSize 1
        }
    }
})
