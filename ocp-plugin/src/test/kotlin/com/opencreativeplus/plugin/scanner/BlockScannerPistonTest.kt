package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.registry.NodeRegistry
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for BlockScanner — Piston System (Requirements 2.1, 2.2, 2.6).
 *
 * Covers:
 * - STICKY_PISTON above a glass block is recognised as an opening bracket (Req 2.1)
 * - PISTON above a glass block is recognised as a closing bracket (Req 2.2)
 * - A STICKY_PISTON without a matching closing PISTON produces a ParseError
 *   that includes position information (Req 2.6)
 */
class BlockScannerPistonTest {

    // -----------------------------------------------------------------------
    // Test doubles
    // -----------------------------------------------------------------------

    private val world = mockk<World>(relaxed = true).also {
        every { it.name } returns "test_world"
        every { it.hashCode() } returns 1
        // scanStrip uses getRelativeSafe which checks isChunkLoaded
        every { it.isChunkLoaded(any<Int>(), any<Int>()) } returns true
    }
    private val nodeRegistry = mockk<NodeRegistry>(relaxed = true)

    private val dummyActionFactory: (Map<String, Any>) -> IAction = { mockk(relaxed = true) }
    private val dummyConditionFactory: (Map<String, Any>) -> ICondition = { mockk(relaxed = true) }

    private val scanner = BlockScanner(world, nodeRegistry)

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Default AIR block returned for any unspecified position. */
    private val defaultAir: Block = mockk<Block>(relaxed = true).also {
        every { it.type } returns Material.AIR
        every { it.state } returns mockk(relaxed = true)
        every { it.location } returns Location(world, -999.0, -999.0, -999.0)
    }

    /** Create a plain glass block (no PDC, no node above by default). */
    private fun mockGlass(x: Int, y: Int, z: Int, material: Material = Material.WHITE_STAINED_GLASS): Block {
        val block = mockk<Block>(relaxed = true)
        every { block.type } returns material
        every { block.location } returns Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        every { block.state } returns mockk(relaxed = true)
        // Default: all neighbours are air
        for (face in BlockFace.values()) {
            every { block.getRelative(face) } returns defaultAir
        }
        return block
    }

    /** Create a block with the given material placed ABOVE a glass block. */
    private fun mockAboveBlock(x: Int, y: Int, z: Int, material: Material): Block {
        val block = mockk<Block>(relaxed = true)
        every { block.type } returns material
        every { block.location } returns Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        every { block.state } returns mockk(relaxed = true)
        for (face in BlockFace.values()) {
            every { block.getRelative(face) } returns defaultAir
        }
        return block
    }

    /**
     * Create a conditional node block (OAK_PLANKS) with `ocp:action_id = "if_player"` in PDC.
     * This is the block placed ABOVE a glass block that triggers piston scope scanning.
     */
    private fun mockConditionalNode(x: Int, y: Int, z: Int): Block {
        val block = mockk<Block>(relaxed = true)
        every { block.type } returns Material.OAK_PLANKS
        every { block.location } returns Location(world, x.toDouble(), y.toDouble(), z.toDouble())

        val tileState = mockk<TileState>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { block.state } returns tileState
        every { tileState.persistentDataContainer } returns pdc

        val actionIdKey = NamespacedKey("ocp", "action_id")
        every { pdc.get(actionIdKey, PersistentDataType.STRING) } returns "if_player"
        every { nodeRegistry.getConditionFactoryById("if_player") } returns dummyConditionFactory

        for (face in BlockFace.values()) {
            every { block.getRelative(face) } returns defaultAir
        }
        return block
    }

    /**
     * Wire a linear strip of glass blocks going EAST.
     * Returns the list of glass blocks in order (index 0 = start).
     * Each block's EAST neighbour is the next block; the last block's EAST is defaultAir.
     */
    private fun wireLinearStrip(blocks: List<Block>): List<Block> {
        for (i in blocks.indices) {
            every { blocks[i].getRelative(BlockFace.EAST) } returns
                if (i < blocks.size - 1) blocks[i + 1] else defaultAir
            every { blocks[i].getRelative(BlockFace.WEST) } returns
                if (i > 0) blocks[i - 1] else defaultAir
            every { blocks[i].getRelative(BlockFace.NORTH) } returns defaultAir
            every { blocks[i].getRelative(BlockFace.SOUTH) } returns defaultAir
        }
        return blocks
    }

    // -----------------------------------------------------------------------
    // Requirement 2.1 — STICKY_PISTON is recognised as an opening bracket
    // -----------------------------------------------------------------------

    /**
     * Strip layout (going EAST):
     *   [0] BLUE_GLASS  — AIR above (start, no node)
     *   [1] WHITE_GLASS — OAK_PLANKS (conditional node, if_player) above
     *   [2] WHITE_GLASS — STICKY_PISTON above  ← opening bracket
     *   [3] WHITE_GLASS — COBBLESTONE (inner action) above
     *   [4] WHITE_GLASS — PISTON above          ← closing bracket
     *   [5] WHITE_GLASS — AIR above (end)
     *
     * Expected: scanStrip returns a CodeLine whose `children` list is non-empty,
     * confirming that STICKY_PISTON was recognised as an opening bracket and
     * scanChildBranch was invoked.
     *
     * Requirements: 2.1
     */
    @Test
    fun `STICKY_PISTON above glass is recognised as opening bracket and triggers child scope`() {
        every { nodeRegistry.getActionNodeId(any()) } returns null
        every { nodeRegistry.getConditionNodeId(any()) } returns null
        every { nodeRegistry.getValueNodeId(any()) } returns null
        every { nodeRegistry.getActionFactoryById("inner_action") } returns dummyActionFactory

        val glass = listOf(
            mockGlass(0, 0, 0, Material.BLUE_STAINED_GLASS),  // [0] start
            mockGlass(1, 0, 0),                                // [1] conditional
            mockGlass(2, 0, 0),                                // [2] STICKY_PISTON above
            mockGlass(3, 0, 0),                                // [3] inner action
            mockGlass(4, 0, 0),                                // [4] PISTON above
            mockGlass(5, 0, 0)                                 // [5] end (AIR above)
        )
        wireLinearStrip(glass)

        // Blocks above each glass position
        every { glass[0].getRelative(BlockFace.UP) } returns defaultAir
        every { glass[1].getRelative(BlockFace.UP) } returns mockConditionalNode(1, 1, 0)
        every { glass[2].getRelative(BlockFace.UP) } returns mockAboveBlock(2, 1, 0, Material.STICKY_PISTON)
        every { glass[3].getRelative(BlockFace.UP) } returns mockAboveBlock(3, 1, 0, Material.COBBLESTONE).also { node ->
            val tileState = mockk<TileState>(relaxed = true)
            val pdc = mockk<PersistentDataContainer>(relaxed = true)
            every { node.state } returns tileState
            every { tileState.persistentDataContainer } returns pdc
            val actionIdKey = NamespacedKey("ocp", "action_id")
            every { pdc.get(actionIdKey, PersistentDataType.STRING) } returns "inner_action"
        }
        every { glass[4].getRelative(BlockFace.UP) } returns mockAboveBlock(4, 1, 0, Material.PISTON)
        every { glass[5].getRelative(BlockFace.UP) } returns defaultAir

        val codeLines = scanner.scanStrip(glass[0])

        assertTrue(scanner.parseErrors.isEmpty(), "No parse errors expected for a well-formed piston scope")
        assertTrue(codeLines.isNotEmpty(), "Expected at least one CodeLine")
        assertTrue(
            codeLines[0].children.isNotEmpty(),
            "STICKY_PISTON should have been recognised as an opening bracket, producing a child CodeLine"
        )
    }

    // -----------------------------------------------------------------------
    // Requirement 2.2 — PISTON is recognised as a closing bracket
    // -----------------------------------------------------------------------

    /**
     * Same strip as above. After the PISTON closes the scope, the scanner
     * continues past it and collects the node at position [5] (if any).
     * The key assertion is that the PISTON itself does NOT appear as a node
     * in either the parent or child CodeLine — it is consumed as a bracket.
     *
     * Requirements: 2.2
     */
    @Test
    fun `PISTON above glass is recognised as closing bracket and is not collected as a node`() {
        every { nodeRegistry.getActionNodeId(any()) } returns null
        every { nodeRegistry.getConditionNodeId(any()) } returns null
        every { nodeRegistry.getValueNodeId(any()) } returns null
        every { nodeRegistry.getActionFactoryById("inner_action") } returns dummyActionFactory
        every { nodeRegistry.getActionFactoryById("outer_action") } returns dummyActionFactory

        val glass = listOf(
            mockGlass(0, 0, 0, Material.BLUE_STAINED_GLASS),  // [0] start
            mockGlass(1, 0, 0),                                // [1] conditional
            mockGlass(2, 0, 0),                                // [2] STICKY_PISTON above
            mockGlass(3, 0, 0),                                // [3] inner action
            mockGlass(4, 0, 0),                                // [4] PISTON above (closing)
            mockGlass(5, 0, 0)                                 // [5] outer action after scope
        )
        wireLinearStrip(glass)

        every { glass[0].getRelative(BlockFace.UP) } returns defaultAir
        every { glass[1].getRelative(BlockFace.UP) } returns mockConditionalNode(1, 1, 0)
        every { glass[2].getRelative(BlockFace.UP) } returns mockAboveBlock(2, 1, 0, Material.STICKY_PISTON)
        every { glass[3].getRelative(BlockFace.UP) } returns mockAboveBlock(3, 1, 0, Material.COBBLESTONE).also { node ->
            val tileState = mockk<TileState>(relaxed = true)
            val pdc = mockk<PersistentDataContainer>(relaxed = true)
            every { node.state } returns tileState
            every { tileState.persistentDataContainer } returns pdc
            val actionIdKey = NamespacedKey("ocp", "action_id")
            every { pdc.get(actionIdKey, PersistentDataType.STRING) } returns "inner_action"
        }
        every { glass[4].getRelative(BlockFace.UP) } returns mockAboveBlock(4, 1, 0, Material.PISTON)
        every { glass[5].getRelative(BlockFace.UP) } returns mockAboveBlock(5, 1, 0, Material.COBBLESTONE).also { node ->
            val tileState = mockk<TileState>(relaxed = true)
            val pdc = mockk<PersistentDataContainer>(relaxed = true)
            every { node.state } returns tileState
            every { tileState.persistentDataContainer } returns pdc
            val actionIdKey = NamespacedKey("ocp", "action_id")
            every { pdc.get(actionIdKey, PersistentDataType.STRING) } returns "outer_action"
        }

        val codeLines = scanner.scanStrip(glass[0])

        assertTrue(scanner.parseErrors.isEmpty(), "No parse errors expected")
        assertTrue(codeLines.isNotEmpty())

        val parentLine = codeLines[0]

        // PISTON must not appear as a node in the parent line
        val allParentMaterials = parentLine.nodes.map { it.blockType }
        assertTrue(
            Material.PISTON !in allParentMaterials,
            "PISTON should be consumed as a closing bracket, not collected as a node"
        )

        // PISTON must not appear as a node in the child scope either
        val childLine = parentLine.children.firstOrNull()
        if (childLine != null) {
            val allChildMaterials = childLine.nodes.map { it.blockType }
            assertTrue(
                Material.PISTON !in allChildMaterials,
                "PISTON should not appear as a node inside the child scope"
            )
        }
    }

    // -----------------------------------------------------------------------
    // Requirement 2.6 — Unclosed STICKY_PISTON produces a ParseError with position
    // -----------------------------------------------------------------------

    /**
     * Strip layout (going EAST):
     *   [0] BLUE_GLASS  — AIR above (start)
     *   [1] WHITE_GLASS — OAK_PLANKS (conditional node) above
     *   [2] WHITE_GLASS — STICKY_PISTON above  ← opening bracket (never closed)
     *   [3] WHITE_GLASS — COBBLESTONE (inner action) above
     *   (strip ends — no PISTON closing bracket)
     *
     * Expected: scanStrip reports exactly one ParseError whose message contains
     * position information (x, y, z coordinates).
     *
     * Requirements: 2.6
     */
    @Test
    fun `unclosed STICKY_PISTON produces a ParseError with position information`() {
        every { nodeRegistry.getActionNodeId(any()) } returns null
        every { nodeRegistry.getConditionNodeId(any()) } returns null
        every { nodeRegistry.getValueNodeId(any()) } returns null
        every { nodeRegistry.getActionFactoryById("inner_action") } returns dummyActionFactory

        val glass = listOf(
            mockGlass(0, 0, 0, Material.BLUE_STAINED_GLASS),  // [0] start
            mockGlass(1, 0, 0),                                // [1] conditional
            mockGlass(2, 0, 0),                                // [2] STICKY_PISTON above
            mockGlass(3, 0, 0)                                 // [3] inner action — strip ends here
        )
        wireLinearStrip(glass)

        every { glass[0].getRelative(BlockFace.UP) } returns defaultAir
        every { glass[1].getRelative(BlockFace.UP) } returns mockConditionalNode(1, 1, 0)
        every { glass[2].getRelative(BlockFace.UP) } returns mockAboveBlock(2, 1, 0, Material.STICKY_PISTON)
        every { glass[3].getRelative(BlockFace.UP) } returns mockAboveBlock(3, 1, 0, Material.COBBLESTONE).also { node ->
            val tileState = mockk<TileState>(relaxed = true)
            val pdc = mockk<PersistentDataContainer>(relaxed = true)
            every { node.state } returns tileState
            every { tileState.persistentDataContainer } returns pdc
            val actionIdKey = NamespacedKey("ocp", "action_id")
            every { pdc.get(actionIdKey, PersistentDataType.STRING) } returns "inner_action"
        }

        scanner.scanStrip(glass[0])

        assertEquals(1, scanner.parseErrors.size, "Expected exactly one ParseError for the unclosed bracket")

        val error = scanner.parseErrors[0]
        assertTrue(
            error.message.contains("Unclosed bracket", ignoreCase = true),
            "ParseError message should mention 'Unclosed bracket', was: '${error.message}'"
        )
        // The error must carry position information (Req 2.6)
        assertTrue(
            error.location != null,
            "ParseError must include a location for the unclosed bracket"
        )
    }

    /**
     * Verify that the ParseError message produced for an unclosed bracket
     * embeds the block coordinates, matching the format
     * "Unclosed bracket at x,y,z" defined in BlockScanner.scanChildBranch.
     *
     * Requirements: 2.6
     */
    @Test
    fun `unclosed STICKY_PISTON ParseError message contains block coordinates`() {
        every { nodeRegistry.getActionNodeId(any()) } returns null
        every { nodeRegistry.getConditionNodeId(any()) } returns null
        every { nodeRegistry.getValueNodeId(any()) } returns null

        // Minimal strip: start → conditional → STICKY_PISTON (no closing PISTON)
        val glass = listOf(
            mockGlass(10, 5, 20, Material.BLUE_STAINED_GLASS),  // [0] start at x=10,y=5,z=20
            mockGlass(11, 5, 20),                                // [1] conditional
            mockGlass(12, 5, 20)                                 // [2] STICKY_PISTON — strip ends
        )
        wireLinearStrip(glass)

        every { glass[0].getRelative(BlockFace.UP) } returns defaultAir
        every { glass[1].getRelative(BlockFace.UP) } returns mockConditionalNode(11, 6, 20)
        every { glass[2].getRelative(BlockFace.UP) } returns mockAboveBlock(12, 6, 20, Material.STICKY_PISTON)

        scanner.scanStrip(glass[0])

        assertEquals(1, scanner.parseErrors.size, "Expected exactly one ParseError")

        val error = scanner.parseErrors[0]
        // The message format from BlockScanner is: "Unclosed bracket at x,y,z"
        // The last glass block visited before the strip ends is glass[2] at x=12,y=5,z=20
        assertTrue(
            error.message.contains("12"),
            "ParseError message should contain the x-coordinate (12), was: '${error.message}'"
        )
        assertTrue(
            error.message.contains("Unclosed bracket", ignoreCase = true),
            "ParseError message should start with 'Unclosed bracket', was: '${error.message}'"
        )
    }

    // -----------------------------------------------------------------------
    // Requirement 2.1 + 2.2 — Well-formed scope produces no parse errors
    // -----------------------------------------------------------------------

    /**
     * A correctly matched STICKY_PISTON / PISTON pair must produce zero parse errors.
     *
     * Requirements: 2.1, 2.2
     */
    @Test
    fun `well-formed STICKY_PISTON and PISTON pair produces no parse errors`() {
        every { nodeRegistry.getActionNodeId(any()) } returns null
        every { nodeRegistry.getConditionNodeId(any()) } returns null
        every { nodeRegistry.getValueNodeId(any()) } returns null
        every { nodeRegistry.getActionFactoryById("inner_action") } returns dummyActionFactory

        val glass = listOf(
            mockGlass(0, 0, 0, Material.BLUE_STAINED_GLASS),
            mockGlass(1, 0, 0),
            mockGlass(2, 0, 0),
            mockGlass(3, 0, 0),
            mockGlass(4, 0, 0)
        )
        wireLinearStrip(glass)

        every { glass[0].getRelative(BlockFace.UP) } returns defaultAir
        every { glass[1].getRelative(BlockFace.UP) } returns mockConditionalNode(1, 1, 0)
        every { glass[2].getRelative(BlockFace.UP) } returns mockAboveBlock(2, 1, 0, Material.STICKY_PISTON)
        every { glass[3].getRelative(BlockFace.UP) } returns mockAboveBlock(3, 1, 0, Material.COBBLESTONE).also { node ->
            val tileState = mockk<TileState>(relaxed = true)
            val pdc = mockk<PersistentDataContainer>(relaxed = true)
            every { node.state } returns tileState
            every { tileState.persistentDataContainer } returns pdc
            val actionIdKey = NamespacedKey("ocp", "action_id")
            every { pdc.get(actionIdKey, PersistentDataType.STRING) } returns "inner_action"
        }
        every { glass[4].getRelative(BlockFace.UP) } returns mockAboveBlock(4, 1, 0, Material.PISTON)

        scanner.scanStrip(glass[0])

        assertTrue(
            scanner.parseErrors.isEmpty(),
            "A well-formed STICKY_PISTON/PISTON pair must produce no parse errors"
        )
    }
}
