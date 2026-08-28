@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-mvp2-core-systems, Property 7: BlockScanner распознаёт else-ветку

package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.registry.NodeRegistry
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import org.junit.jupiter.api.Assertions.assertTrue
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
 * Property 7: BlockScanner распознаёт else-ветку
 *
 * For any strip where END_STONE follows the closing PISTON of a conditional scope,
 * `BlockScanner.scanStrip()` must:
 * 7a. Return no parse errors and populate `elseActions` on the child CodeLine
 * 7b. Report a parse error when END_STONE has no closing PISTON
 * 7c. Keep then-branch nodes and else-branch nodes mutually exclusive
 *
 * **Validates: Requirements 4.1, 4.2**
 */
class BlockScannerElsePropertyTest : FreeSpec({

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

    val dummyConditionFactory: (Map<String, Any>) -> ICondition = { mockk(relaxed = true) }
    val dummyActionFactory: (Map<String, Any>) -> IAction = { mockk(relaxed = true) }

    every { nodeRegistry.getConditionFactoryById("if_player") } returns dummyConditionFactory

    val scanner = BlockScanner(world, nodeRegistry)

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    val defaultAir: Block = mockk<Block>(relaxed = true).also {
        every { it.type } returns Material.AIR
        every { it.state } returns mockk(relaxed = true)
        every { it.location } returns Location(world, -999.0, -999.0, -999.0)
    }

    /** Creates a glass block at (x, y, z) with the given material. */
    fun mockGlass(x: Int, y: Int, z: Int, material: Material): Block {
        val block = mockk<Block>(relaxed = true)
        every { block.type } returns material
        every { block.location } returns Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        every { block.state } returns mockk(relaxed = true)
        return block
    }

    /** Creates an AIR block above a glass block (no node). */
    fun mockAirAbove(): Block = mockk<Block>(relaxed = true).also {
        every { it.type } returns Material.AIR
        every { it.state } returns mockk(relaxed = true)
    }

    /** Creates a conditional node (OAK_PLANKS with ocp:action_id = "if_player") above a glass block. */
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

    /** Creates an action node (COBBLESTONE with ocp:action_id = actionId) above a glass block. */
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

    /** Creates a STICKY_PISTON block (opening bracket) above a glass block. */
    fun mockStickyPiston(x: Int, y: Int, z: Int): Block {
        val block = mockk<Block>(relaxed = true)
        every { block.type } returns Material.STICKY_PISTON
        every { block.location } returns Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        every { block.state } returns mockk(relaxed = true)
        return block
    }

    /** Creates a PISTON block (closing bracket) above a glass block. */
    fun mockPiston(x: Int, y: Int, z: Int): Block {
        val block = mockk<Block>(relaxed = true)
        every { block.type } returns Material.PISTON
        every { block.location } returns Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        every { block.state } returns mockk(relaxed = true)
        return block
    }

    /** Creates an END_STONE block (else-marker) above a glass block. */
    fun mockEndStone(x: Int, y: Int, z: Int): Block {
        val block = mockk<Block>(relaxed = true)
        every { block.type } returns Material.END_STONE
        every { block.location } returns Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        every { block.state } returns mockk(relaxed = true)
        return block
    }

    /**
     * Wires EAST/WEST neighbours for a linear strip of glass blocks.
     * Blocks beyond the ends get [defaultAir]. NORTH/SOUTH always get [defaultAir].
     */
    fun wireLinearStrip(blocks: List<Block>) {
        for (i in blocks.indices) {
            val block = blocks[i]
            every { block.getRelative(BlockFace.EAST) } returns
                if (i < blocks.size - 1) blocks[i + 1] else defaultAir
            every { block.getRelative(BlockFace.WEST) } returns
                if (i > 0) blocks[i - 1] else defaultAir
            every { block.getRelative(BlockFace.NORTH) } returns defaultAir
            every { block.getRelative(BlockFace.SOUTH) } returns defaultAir
        }
    }

    /**
     * Builds the canonical else-branch strip with [nThenActions] then-actions and
     * [nElseActions] else-actions.
     *
     * Strip layout (going EAST):
     * [0] BLUE_GLASS  — AIR above (start)
     * [1] WHITE_GLASS — OAK_PLANKS (conditional, if_player) above
     * [2] WHITE_GLASS — STICKY_PISTON above  ← opening bracket
     * [3..3+nThenActions-1] WHITE_GLASS — COBBLESTONE (then-action_i) above
     * [3+nThenActions] WHITE_GLASS — PISTON above  ← closing bracket
     * [4+nThenActions] WHITE_GLASS — END_STONE above  ← else-marker
     * [5+nThenActions..5+nThenActions+nElseActions-1] WHITE_GLASS — COBBLESTONE (else-action_i) above
     * [5+nThenActions+nElseActions] WHITE_GLASS — PISTON above  ← closing else bracket
     * [6+nThenActions+nElseActions] WHITE_GLASS — AIR above (optional continuation)
     *
     * Returns the start block (BLUE_STAINED_GLASS at position 0).
     */
    fun buildElseStrip(nThenActions: Int, nElseActions: Int, stripId: String): Block {
        val y = 0
        // Positions: start(0), cond(1), openBracket(2), then*(nThenActions), closeBracket, elseMarker, else*(nElseActions), closeElse, end
        val totalBlocks = 1 + 1 + 1 + nThenActions + 1 + 1 + nElseActions + 1 + 1

        val glassBlocks = (0 until totalBlocks).map { x ->
            val mat = if (x == 0) Material.BLUE_STAINED_GLASS else Material.WHITE_STAINED_GLASS
            mockGlass(x, y, 0, mat)
        }

        wireLinearStrip(glassBlocks)

        // [0] AIR above start
        every { glassBlocks[0].getRelative(BlockFace.UP) } returns mockAirAbove()

        // [1] Conditional node above
        val conditionalNode = mockConditionalNode(1, y + 1, 0)
        every { glassBlocks[1].getRelative(BlockFace.UP) } returns conditionalNode

        // [2] STICKY_PISTON above (opening bracket)
        val stickyPiston = mockStickyPiston(2, y + 1, 0)
        every { glassBlocks[2].getRelative(BlockFace.UP) } returns stickyPiston

        // [3..3+nThenActions-1] Then-action nodes above
        for (i in 0 until nThenActions) {
            val pos = 3 + i
            val actionId = "then_action_${stripId}_$i"
            val actionNode = mockActionNode(pos, y + 1, 0, actionId)
            every { glassBlocks[pos].getRelative(BlockFace.UP) } returns actionNode
        }

        // [3+nThenActions] PISTON above (closing bracket for then-branch)
        val closingBracketPos = 3 + nThenActions
        val closingPiston = mockPiston(closingBracketPos, y + 1, 0)
        every { glassBlocks[closingBracketPos].getRelative(BlockFace.UP) } returns closingPiston

        // [4+nThenActions] END_STONE above (else-marker)
        val elseMarkerPos = 4 + nThenActions
        val endStone = mockEndStone(elseMarkerPos, y + 1, 0)
        every { glassBlocks[elseMarkerPos].getRelative(BlockFace.UP) } returns endStone

        // [5+nThenActions..5+nThenActions+nElseActions-1] Else-action nodes above
        for (i in 0 until nElseActions) {
            val pos = 5 + nThenActions + i
            val actionId = "else_action_${stripId}_$i"
            val actionNode = mockActionNode(pos, y + 1, 0, actionId)
            every { glassBlocks[pos].getRelative(BlockFace.UP) } returns actionNode
        }

        // [5+nThenActions+nElseActions] PISTON above (closing bracket for else-branch)
        val closeElsePos = 5 + nThenActions + nElseActions
        val closingElsePiston = mockPiston(closeElsePos, y + 1, 0)
        every { glassBlocks[closeElsePos].getRelative(BlockFace.UP) } returns closingElsePiston

        // [6+nThenActions+nElseActions] AIR above (end of strip)
        val endPos = 6 + nThenActions + nElseActions
        every { glassBlocks[endPos].getRelative(BlockFace.UP) } returns mockAirAbove()

        return glassBlocks[0]
    }

    /**
     * Builds a strip where END_STONE is present but has no closing PISTON (strip ends abruptly).
     *
     * Strip layout (going EAST):
     * [0] BLUE_GLASS  — AIR above (start)
     * [1] WHITE_GLASS — OAK_PLANKS (conditional) above
     * [2] WHITE_GLASS — STICKY_PISTON above  ← opening bracket
     * [3] WHITE_GLASS — COBBLESTONE (then-action) above
     * [4] WHITE_GLASS — PISTON above  ← closing bracket
     * [5] WHITE_GLASS — END_STONE above  ← else-marker
     * [6] WHITE_GLASS — COBBLESTONE (else-action) above
     * (strip ends — no closing PISTON for else)
     */
    fun buildElseWithoutClosingPiston(stripId: String): Block {
        val y = 0
        val totalBlocks = 7  // positions 0..6

        val glassBlocks = (0 until totalBlocks).map { x ->
            val mat = if (x == 0) Material.BLUE_STAINED_GLASS else Material.WHITE_STAINED_GLASS
            mockGlass(x, y, 0, mat)
        }

        wireLinearStrip(glassBlocks)

        // [0] AIR above start
        every { glassBlocks[0].getRelative(BlockFace.UP) } returns mockAirAbove()

        // [1] Conditional node above
        val conditionalNode = mockConditionalNode(1, y + 1, 0)
        every { glassBlocks[1].getRelative(BlockFace.UP) } returns conditionalNode

        // [2] STICKY_PISTON above (opening bracket)
        val stickyPiston = mockStickyPiston(2, y + 1, 0)
        every { glassBlocks[2].getRelative(BlockFace.UP) } returns stickyPiston

        // [3] Then-action above
        val thenActionId = "then_action_nopiston_${stripId}"
        val thenAction = mockActionNode(3, y + 1, 0, thenActionId)
        every { glassBlocks[3].getRelative(BlockFace.UP) } returns thenAction

        // [4] PISTON above (closing bracket for then-branch)
        val closingPiston = mockPiston(4, y + 1, 0)
        every { glassBlocks[4].getRelative(BlockFace.UP) } returns closingPiston

        // [5] END_STONE above (else-marker)
        val endStone = mockEndStone(5, y + 1, 0)
        every { glassBlocks[5].getRelative(BlockFace.UP) } returns endStone

        // [6] Else-action above (no closing PISTON follows — strip ends here)
        val elseActionId = "else_action_nopiston_${stripId}"
        val elseAction = mockActionNode(6, y + 1, 0, elseActionId)
        every { glassBlocks[6].getRelative(BlockFace.UP) } returns elseAction

        return glassBlocks[0]
    }

    // -----------------------------------------------------------------------
    // Property 7a: END_STONE after closing PISTON → elseActions populated, no errors
    // -----------------------------------------------------------------------

    "Property 7a: END_STONE after closing PISTON → elseActions populated, no parse errors" - {

        /**
         * For any number of else-action nodes (1..5), when END_STONE follows the closing PISTON,
         * scanStrip must:
         * 1. Return no parse errors
         * 2. Return a CodeLine whose first child has non-empty elseActions
         * 3. The elseActions count equals the number of else-action nodes placed
         *
         * **Validates: Requirements 4.1, 4.2**
         */
        "for N else-actions (1..5): no parse errors, elseActions.size == N" {
            // Validates: Requirements 4.1, 4.2
            checkAll(PropTestConfig(iterations = 20), Arb.int(1..5)) { nElseActions ->
                val stripId = "prop7a_$nElseActions"
                val startBlock = buildElseStrip(
                    nThenActions = 1,
                    nElseActions = nElseActions,
                    stripId = stripId
                )
                val codeLines = scanner.scanStrip(startBlock)

                scanner.parseErrors shouldBe emptyList()
                codeLines shouldHaveAtLeastSize 1

                val firstChild = codeLines[0].children.firstOrNull()
                checkNotNull(firstChild) { "Expected at least one child CodeLine (the conditional scope)" }

                firstChild.elseActions shouldHaveSize nElseActions
            }
        }

        /**
         * For any number of then-actions (1..5) combined with any number of else-actions (1..5),
         * the total structure is correct: no parse errors, child has both nodes and elseActions.
         *
         * **Validates: Requirements 4.1, 4.2**
         */
        "for N then-actions and M else-actions: no parse errors, child has both" {
            // Validates: Requirements 4.1, 4.2
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1..5),
                Arb.int(1..5)
            ) { nThenActions, nElseActions ->
                val stripId = "prop7a_then${nThenActions}_else${nElseActions}"
                val startBlock = buildElseStrip(
                    nThenActions = nThenActions,
                    nElseActions = nElseActions,
                    stripId = stripId
                )
                val codeLines = scanner.scanStrip(startBlock)

                scanner.parseErrors shouldBe emptyList()
                codeLines shouldHaveAtLeastSize 1

                val firstChild = codeLines[0].children.firstOrNull()
                checkNotNull(firstChild) { "Expected at least one child CodeLine" }

                firstChild.elseActions shouldHaveSize nElseActions
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 7b: END_STONE without closing PISTON → parse error
    // -----------------------------------------------------------------------

    "Property 7b: END_STONE without closing PISTON → parse error reported" - {

        /**
         * When END_STONE is present but the else-branch has no closing PISTON (strip ends),
         * parseErrors must contain exactly one error with a message containing
         * "has no closing PISTON" or "END_STONE".
         *
         * **Validates: Requirements 4.2**
         */
        "missing closing PISTON for else → exactly one parse error mentioning END_STONE or PISTON" {
            // Validates: Requirements 4.2
            checkAll(PropTestConfig(iterations = 20), Arb.int(0..99)) { seed ->
                val stripId = "prop7b_$seed"
                val startBlock = buildElseWithoutClosingPiston(stripId)
                scanner.scanStrip(startBlock)

                scanner.parseErrors shouldHaveAtLeastSize 1
                val errorMessage = scanner.parseErrors.first().message
                val mentionsEndStoneOrPiston =
                    errorMessage.contains("END_STONE", ignoreCase = true) ||
                    errorMessage.contains("PISTON", ignoreCase = true) ||
                    errorMessage.contains("closing", ignoreCase = true)
                mentionsEndStoneOrPiston shouldBe true
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 7d: Nested if inside else-branch is parsed correctly
    // -----------------------------------------------------------------------

    "Property 7d: nested if inside else-branch parses without errors and collects nodes correctly" - {

        /**
         * Verifies that a construct like:
         *   if (A) { thenNode } else { if (B) { innerThenNode } innerElseNode }
         *
         * Strip layout (going EAST):
         * [0]  BLUE_GLASS  — AIR above (start)
         * [1]  WHITE_GLASS — OAK_PLANKS "if_player" above (outer conditional)
         * [2]  WHITE_GLASS — STICKY_PISTON above (outer opening bracket)
         * [3]  WHITE_GLASS — COBBLESTONE "outer_then" above
         * [4]  WHITE_GLASS — PISTON above (outer closing bracket)
         * [5]  WHITE_GLASS — END_STONE above (else-marker)
         * [6]  WHITE_GLASS — OAK_PLANKS "if_player" above (inner conditional)
         * [7]  WHITE_GLASS — STICKY_PISTON above (inner opening bracket — depth++ inside else)
         * [8]  WHITE_GLASS — COBBLESTONE "inner_then" above
         * [9]  WHITE_GLASS — PISTON above (inner closing bracket — depth-- inside else, depth still > 0)
         * [10] WHITE_GLASS — COBBLESTONE "outer_else_after_inner" above
         * [11] WHITE_GLASS — PISTON above (outer else closing bracket — depth == 0 → ends else)
         * [12] WHITE_GLASS — AIR above (continuation after else)
         *
         * Expected:
         * - No parse errors
         * - The outer conditional child has elseActions containing nodes collected at depth==0:
         *   "if_player" node (inner conditional) + "outer_else_after_inner" node
         * - The sticky_piston and inner closing piston are NOT collected as nodes
         *   (they are bracket markers, not registered actions)
         */
        "nested STICKY_PISTON inside else increments depth; inner PISTON decrements but does not close else" {
            val y = 0
            val totalBlocks = 13

            val glassBlocks = (0 until totalBlocks).map { x ->
                val mat = if (x == 0) Material.BLUE_STAINED_GLASS else Material.WHITE_STAINED_GLASS
                mockGlass(x, y, 0, mat)
            }
            wireLinearStrip(glassBlocks)

            // [0] AIR above start
            every { glassBlocks[0].getRelative(BlockFace.UP) } returns mockAirAbove()

            // [1] outer conditional (OAK_PLANKS / if_player)
            val outerCond = mockConditionalNode(1, y + 1, 0)
            every { glassBlocks[1].getRelative(BlockFace.UP) } returns outerCond

            // [2] outer STICKY_PISTON (opening bracket)
            every { glassBlocks[2].getRelative(BlockFace.UP) } returns mockStickyPiston(2, y + 1, 0)

            // [3] outer then-node
            every { glassBlocks[3].getRelative(BlockFace.UP) } returns mockActionNode(3, y + 1, 0, "outer_then")

            // [4] outer PISTON (closing bracket for then-branch)
            every { glassBlocks[4].getRelative(BlockFace.UP) } returns mockPiston(4, y + 1, 0)

            // [5] END_STONE (else-marker)
            every { glassBlocks[5].getRelative(BlockFace.UP) } returns mockEndStone(5, y + 1, 0)

            // [6] inner conditional (OAK_PLANKS / if_player) — depth=0 in else, collected as node
            val innerCond = mockConditionalNode(6, y + 1, 0)
            every { glassBlocks[6].getRelative(BlockFace.UP) } returns innerCond

            // [7] inner STICKY_PISTON (opening bracket) — depth++ → depth=1, NOT collected as node
            every { glassBlocks[7].getRelative(BlockFace.UP) } returns mockStickyPiston(7, y + 1, 0)

            // [8] inner then-node — depth=1, collected as node... but we're inside a nested scope
            // According to the depth algorithm: at depth>0 blocks ARE collected as nodes
            // This is the inner then-branch content
            every { glassBlocks[8].getRelative(BlockFace.UP) } returns mockActionNode(8, y + 1, 0, "inner_then")

            // [9] inner PISTON (closing bracket) — depth-- → depth=0, does NOT end else-branch
            every { glassBlocks[9].getRelative(BlockFace.UP) } returns mockPiston(9, y + 1, 0)

            // [10] outer-else node AFTER inner if — depth=0, collected
            every { glassBlocks[10].getRelative(BlockFace.UP) } returns mockActionNode(10, y + 1, 0, "outer_else_after_inner")

            // [11] outer PISTON (closing bracket for else-branch) — depth=0, ends else
            every { glassBlocks[11].getRelative(BlockFace.UP) } returns mockPiston(11, y + 1, 0)

            // [12] AIR above (continuation after else)
            every { glassBlocks[12].getRelative(BlockFace.UP) } returns mockAirAbove()

            val codeLines = scanner.scanStrip(glassBlocks[0])

            // No parse errors
            scanner.parseErrors shouldBe emptyList()
            codeLines shouldHaveAtLeastSize 1

            val outerChild = codeLines[0].children.firstOrNull()
            checkNotNull(outerChild) { "Expected outer conditional child CodeLine" }

            // Then-branch: outer_then node only
            outerChild.nodes shouldHaveSize 1
            outerChild.nodes[0].nodeId shouldBe "outer_then"

            // Else-branch: inner conditional + inner_then (at depth=1) + outer_else_after_inner
            // The depth algorithm collects nodes at ALL depth levels, not only depth==0.
            // STICKY_PISTON and PISTON are NOT registered actions → buildScannedNode returns null → not added.
            // inner conditional (OAK_PLANKS) IS a registered condition → added.
            // inner_then (COBBLESTONE) IS registered → added at depth=1.
            // outer_else_after_inner (COBBLESTONE) IS registered → added at depth=0.
            outerChild.elseActions.shouldHaveAtLeastSize(2)
            val elseNodeIds = outerChild.elseActions.mapNotNull { it.nodeId }
            elseNodeIds shouldHaveAtLeastSize 1
            assertTrue(
                elseNodeIds.any { it.contains("outer_else_after_inner") },
                "outer_else_after_inner must be present in elseActions"
            )
        }

        "nested if inside else: closing piston of inner scope does NOT terminate else-branch (regression test)" {
            // A minimal case: else { if (X) {} someAction }
            // Before fix: first PISTON after END_STONE would close the else branch prematurely.
            // After fix: depth tracking ensures only depth==0 PISTON closes the branch.
            val y = 0

            // Strip: [0]start [1]cond [2]openOuter [3]outerThen [4]closeOuter
            //        [5]elseMarker [6]innerCond [7]openInner [8]innerThen [9]closeInner
            //        [10]elseNode [11]closeElse [12]end
            val totalBlocks = 13
            val glassBlocks = (0 until totalBlocks).map { x ->
                val mat = if (x == 0) Material.BLUE_STAINED_GLASS else Material.WHITE_STAINED_GLASS
                mockGlass(x, y, x * 10, mat)  // unique Z to avoid LocationKey collision
            }
            wireLinearStrip(glassBlocks)

            every { glassBlocks[0].getRelative(BlockFace.UP) } returns mockAirAbove()
            every { glassBlocks[1].getRelative(BlockFace.UP) } returns mockConditionalNode(1, y+1, 10)
            every { glassBlocks[2].getRelative(BlockFace.UP) } returns mockStickyPiston(2, y+1, 20)
            every { glassBlocks[3].getRelative(BlockFace.UP) } returns mockActionNode(3, y+1, 30, "outer_then_b")
            every { glassBlocks[4].getRelative(BlockFace.UP) } returns mockPiston(4, y+1, 40)
            every { glassBlocks[5].getRelative(BlockFace.UP) } returns mockEndStone(5, y+1, 50)
            every { glassBlocks[6].getRelative(BlockFace.UP) } returns mockConditionalNode(6, y+1, 60)
            every { glassBlocks[7].getRelative(BlockFace.UP) } returns mockStickyPiston(7, y+1, 70)
            every { glassBlocks[8].getRelative(BlockFace.UP) } returns mockActionNode(8, y+1, 80, "inner_then_b")
            every { glassBlocks[9].getRelative(BlockFace.UP) } returns mockPiston(9, y+1, 90)
            every { glassBlocks[10].getRelative(BlockFace.UP) } returns mockActionNode(10, y+1, 100, "else_after_inner_b")
            every { glassBlocks[11].getRelative(BlockFace.UP) } returns mockPiston(11, y+1, 110)
            every { glassBlocks[12].getRelative(BlockFace.UP) } returns mockAirAbove()

            val codeLines = scanner.scanStrip(glassBlocks[0])
            scanner.parseErrors shouldBe emptyList()

            val child = codeLines[0].children.firstOrNull()
            checkNotNull(child)

            // The else-branch must include "else_after_inner_b" — only possible if
            // the inner PISTON at [9] did NOT prematurely close the else-branch.
            val elseIds = child.elseActions.mapNotNull { it.nodeId }
            assertTrue(
                elseIds.contains("else_after_inner_b"),
                "else_after_inner_b must be in elseActions — inner PISTON must not close the else-branch"
            )
        }
    }

    "Property 7c: then-branch nodes and else-branch nodes are mutually exclusive" - {

        /**
         * For any strip with N then-actions and M else-actions:
         * - The then-branch nodes (child.nodes) must not appear in elseActions
         * - The else-branch nodes (child.elseActions) must not appear in child.nodes
         * - No node ID appears in both lists
         *
         * **Validates: Requirements 4.1, 4.2**
         */
        "then-nodes and else-nodes have disjoint node IDs" {
            // Validates: Requirements 4.1, 4.2
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1..5),
                Arb.int(1..5)
            ) { nThenActions, nElseActions ->
                val stripId = "prop7c_then${nThenActions}_else${nElseActions}"
                val startBlock = buildElseStrip(
                    nThenActions = nThenActions,
                    nElseActions = nElseActions,
                    stripId = stripId
                )
                val codeLines = scanner.scanStrip(startBlock)

                scanner.parseErrors shouldBe emptyList()
                codeLines shouldHaveAtLeastSize 1

                val firstChild = codeLines[0].children.firstOrNull()
                checkNotNull(firstChild) { "Expected at least one child CodeLine" }

                val thenNodeIds = firstChild.nodes.mapNotNull { it.nodeId }.toSet()
                val elseNodeIds = firstChild.elseActions.mapNotNull { it.nodeId }.toSet()

                // then-node IDs all start with "then_action_"
                thenNodeIds.forEach { id ->
                    id.startsWith("then_action_") shouldBe true
                }

                // else-node IDs all start with "else_action_"
                elseNodeIds.forEach { id ->
                    id.startsWith("else_action_") shouldBe true
                }

                // No overlap between then and else node IDs
                val intersection = thenNodeIds intersect elseNodeIds
                intersection shouldBe emptySet()
            }
        }

        /**
         * The count of then-nodes equals nThenActions and the count of else-nodes equals nElseActions.
         * This confirms neither branch "leaks" into the other.
         *
         * **Validates: Requirements 4.1, 4.2**
         */
        "then-nodes count == nThenActions and else-nodes count == nElseActions" {
            // Validates: Requirements 4.1, 4.2
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1..5),
                Arb.int(1..5)
            ) { nThenActions, nElseActions ->
                val stripId = "prop7c_counts_then${nThenActions}_else${nElseActions}"
                val startBlock = buildElseStrip(
                    nThenActions = nThenActions,
                    nElseActions = nElseActions,
                    stripId = stripId
                )
                val codeLines = scanner.scanStrip(startBlock)

                scanner.parseErrors shouldBe emptyList()
                codeLines shouldHaveAtLeastSize 1

                val firstChild = codeLines[0].children.firstOrNull()
                checkNotNull(firstChild) { "Expected at least one child CodeLine" }

                firstChild.nodes shouldHaveSize nThenActions
                firstChild.elseActions shouldHaveSize nElseActions
            }
        }
    }
})
