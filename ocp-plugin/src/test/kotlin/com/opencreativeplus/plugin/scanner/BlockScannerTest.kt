package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.registry.NodeRegistry
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Chest
import org.bukkit.block.Sign
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for BlockScanner.
 4.3, 19.5, 40.3
 */
class BlockScannerTest {

    private val world = mockk<World>(relaxed = true)
    private val nodeRegistry = mockk<NodeRegistry>(relaxed = true)
    private val scanner = BlockScanner(world, nodeRegistry)

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Create a mock Block with the given material and a real Location. */
    private fun mockBlock(x: Int, y: Int, z: Int, material: Material): Block {
        val block = mockk<Block>(relaxed = true)
        val location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        every { block.type } returns material
        every { block.x } returns x
        every { block.y } returns y
        every { block.z } returns z
        every { block.location } returns location
        every { world.getBlockAt(x, y, z) } returns block
        return block
    }

    /** Wire block.getRelative(face) to return another mock block. */
    private fun Block.withRelative(face: BlockFace, relative: Block): Block {
        every { this@withRelative.getRelative(face) } returns relative
        return this
    }

    // -----------------------------------------------------------------------
    // parseSignText – no Bukkit mocking needed
    // -----------------------------------------------------------------------

    @Test
    fun `parseSignText extracts single key=value pair`() {
        val result = scanner.parseSignText(arrayOf("message=Hello", "", "", ""))
        assertEquals("Hello", result["message"])
    }

    @Test
    fun `parseSignText extracts multiple key=value pairs from different lines`() {
        val result = scanner.parseSignText(arrayOf("key1=foo", "key2=bar", "", ""))
        assertEquals("foo", result["key1"])
        assertEquals("bar", result["key2"])
    }

    @Test
    fun `parseSignText ignores lines without equals sign`() {
        val result = scanner.parseSignText(arrayOf("no equals here", "key=val", "", ""))
        assertEquals(1, result.size)
        assertEquals("val", result["key"])
    }

    @Test
    fun `parseSignText ignores lines with empty key`() {
        val result = scanner.parseSignText(arrayOf("=value", "", "", ""))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseSignText handles value containing equals sign`() {
        val result = scanner.parseSignText(arrayOf("expr=a=b", "", "", ""))
        assertEquals("a=b", result["expr"])
    }

    // -----------------------------------------------------------------------
    // parseValue – no Bukkit mocking needed
    // -----------------------------------------------------------------------

    @Test
    fun `parseValue returns Int for integer string`() {
        val result = scanner.parseValue("42")
        assertIs<Int>(result)
        assertEquals(42, result)
    }

    @Test
    fun `parseValue returns Double for decimal string`() {
        val result = scanner.parseValue("3.14")
        assertIs<Double>(result)
        assertEquals(3.14, result)
    }

    @Test
    fun `parseValue returns VariableReference for dollar-prefixed string`() {
        val result = scanner.parseValue("\$myVar")
        assertIs<VariableReference>(result)
        assertEquals("myVar", (result as VariableReference).name)
    }

    @Test
    fun `parseValue returns String for plain text`() {
        val result = scanner.parseValue("hello")
        assertIs<String>(result)
        assertEquals("hello", result)
    }

    @Test
    fun `parseValue treats lone dollar sign as plain string`() {
        val result = scanner.parseValue("$")
        assertIs<String>(result)
    }

    // -----------------------------------------------------------------------
    // extractParameters – sign attached to block (Req 19.5)
    // -----------------------------------------------------------------------

    @Test
    fun `extractParameters reads key=value from attached sign`() {
        val nodeBlock = mockBlock(1, 6, 0, Material.PAPER)

        // North face has a sign
        val signBlock = mockk<Block>(relaxed = true)
        val signState = mockk<Sign>(relaxed = true)
        every { signBlock.state } returns signState
        every { signState.lines } returns arrayOf("message=Hi", "", "", "")
        nodeBlock.withRelative(BlockFace.NORTH, signBlock)

        // Other faces are air (no sign)
        val airBlock = mockk<Block>(relaxed = true)
        every { airBlock.state } returns mockk(relaxed = true) // not a Sign
        nodeBlock.withRelative(BlockFace.SOUTH, airBlock)
        nodeBlock.withRelative(BlockFace.EAST, airBlock)
        nodeBlock.withRelative(BlockFace.WEST, airBlock)

        // No chest above
        val aboveBlock = mockk<Block>(relaxed = true)
        every { aboveBlock.state } returns mockk(relaxed = true) // not a Chest
        nodeBlock.withRelative(BlockFace.UP, aboveBlock)

        val params = scanner.extractParameters(nodeBlock)
        assertEquals("Hi", params["message"])
    }

    @Test
    fun `extractParameters merges params from multiple signs on different faces`() {
        val nodeBlock = mockBlock(1, 6, 0, Material.PAPER)

        fun signBlock(key: String, value: String): Block {
            val b = mockk<Block>(relaxed = true)
            val s = mockk<Sign>(relaxed = true)
            every { b.state } returns s
            every { s.lines } returns arrayOf("$key=$value", "", "", "")
            return b
        }

        nodeBlock.withRelative(BlockFace.NORTH, signBlock("a", "1"))
        nodeBlock.withRelative(BlockFace.SOUTH, signBlock("b", "2"))

        val airBlock = mockk<Block>(relaxed = true)
        every { airBlock.state } returns mockk(relaxed = true)
        nodeBlock.withRelative(BlockFace.EAST, airBlock)
        nodeBlock.withRelative(BlockFace.WEST, airBlock)

        val aboveBlock = mockk<Block>(relaxed = true)
        every { aboveBlock.state } returns mockk(relaxed = true)
        nodeBlock.withRelative(BlockFace.UP, aboveBlock)

        val params = scanner.extractParameters(nodeBlock)
        assertEquals(1, params["a"])
        assertEquals(2, params["b"])
    }

    // -----------------------------------------------------------------------
    // extractParameters – chest above block (Req 4.6, 19.1)
    // -----------------------------------------------------------------------

    @Test
    fun `extractParameters reads chest contents from block above`() {
        val nodeBlock = mockBlock(1, 6, 0, Material.PAPER)

        // No signs on sides
        val airBlock = mockk<Block>(relaxed = true)
        every { airBlock.state } returns mockk(relaxed = true)
        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            nodeBlock.withRelative(face, airBlock)
        }

        // Chest above
        val chestBlock = mockk<Block>(relaxed = true)
        val chestState = mockk<Chest>(relaxed = true)
        val inventory = mockk<Inventory>(relaxed = true)
        val item = mockk<ItemStack>(relaxed = true)
        // itemMeta returns null so Item_Variable detection is skipped for this plain item
        every { item.itemMeta } returns null
        every { chestBlock.state } returns chestState
        every { chestState.inventory } returns inventory
        every { inventory.contents } returns arrayOf(item, null)
        nodeBlock.withRelative(BlockFace.UP, chestBlock)

        val params = scanner.extractParameters(nodeBlock)
        @Suppress("UNCHECKED_CAST")
        val contents = params["chest_contents"] as List<ItemStack>
        assertEquals(1, contents.size)
        assertEquals(item, contents[0])
    }

    @Test
    fun `extractParameters returns empty map when no signs and no chest`() {
        val nodeBlock = mockBlock(1, 6, 0, Material.PAPER)

        val airBlock = mockk<Block>(relaxed = true)
        every { airBlock.state } returns mockk(relaxed = true)
        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP)) {
            nodeBlock.withRelative(face, airBlock)
        }

        val params = scanner.extractParameters(nodeBlock)
        assertTrue(params.isEmpty())
    }

    // -----------------------------------------------------------------------
    // scanStrip – gap stops reading (Req 4.3, 40.3)
    // -----------------------------------------------------------------------

    /**
     * Build a minimal strip scenario:
     *   x=0: BLUE_STAINED_GLASS (start), above = PAPER node
     *   x=1: WHITE_STAINED_GLASS, above = AIR
     *   x=2: AIR (gap) → strip stops here
     *
     * Nodes after the gap must NOT be included.
     */
    @Test
    fun `scanCodingZone stops strip at glass gap and excludes nodes after gap`() {
        // Default air block returned for all positions
        val defaultAir = mockk<Block>(relaxed = true)
        every { defaultAir.type } returns Material.AIR
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns defaultAir

        // y=0, z=0: blue glass start
        val blueGlass = mockBlock(0, 0, 0, Material.BLUE_STAINED_GLASS)
        // above blue glass: PAPER node
        val paperNode = mockBlock(0, 1, 0, Material.PAPER)
        every { blueGlass.getRelative(BlockFace.UP) } returns paperNode
        // paper node has no signs/chest
        val airSide = mockk<Block>(relaxed = true)
        every { airSide.state } returns mockk(relaxed = true)
        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP)) {
            every { paperNode.getRelative(face) } returns airSide
        }

        // x=1, y=0, z=0: white glass (strip continues)
        val whiteGlass = mockBlock(1, 0, 0, Material.WHITE_STAINED_GLASS)
        val airAboveWhite = mockk<Block>(relaxed = true)
        every { airAboveWhite.type } returns Material.AIR
        every { whiteGlass.getRelative(BlockFace.UP) } returns airAboveWhite

        // x=2, y=0, z=0: AIR → gap, strip stops (already covered by defaultAir)

        val codeLines = scanner.scanCodingZone()

        assertEquals(1, codeLines.size, "Expected exactly one code line")
        val line = codeLines[0]
        assertEquals(1, line.nodes.size, "Only the node before the gap should be included")
        assertEquals(Material.PAPER, line.nodes[0].blockType)
    }

    // -----------------------------------------------------------------------
    // scanCodingZone – multi-level scanning (Req 20.1, 20.2, 20.3)
    // -----------------------------------------------------------------------

    /**
     * Two blue glass strips at different Y levels (y=0 and y=5) each produce a CodeLine.
     * Verifies that scanCodingZone collects code lines from multiple Y levels.
     */
    @Test
    fun `scanCodingZone finds code lines at multiple Y levels`() {
        // Default: all blocks are AIR
        val defaultAir = mockk<Block>(relaxed = true)
        every { defaultAir.type } returns Material.AIR
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns defaultAir

        fun setupBlueGlassStrip(y: Int, z: Int) {
            // x=0: blue glass with a PAPER node above; x=1: AIR (strip ends immediately after)
            val blueGlass = mockBlock(0, y, z, Material.BLUE_STAINED_GLASS)
            val paperNode = mockBlock(0, y + 1, z, Material.PAPER)
            every { blueGlass.getRelative(BlockFace.UP) } returns paperNode

            val airSide = mockk<Block>(relaxed = true)
            every { airSide.state } returns mockk(relaxed = true)
            for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP)) {
                every { paperNode.getRelative(face) } returns airSide
            }
            // x=1 at this y/z is AIR → strip stops (covered by defaultAir)
        }

        setupBlueGlassStrip(y = 0, z = 0)
        setupBlueGlassStrip(y = 5, z = 0)

        val codeLines = scanner.scanCodingZone()

        assertEquals(2, codeLines.size, "Expected code lines from both Y levels")
        val yLevels = codeLines.map { it.startLocation.blockY }.toSet()
        assertTrue(0 in yLevels, "Expected a code line at Y=0")
        assertTrue(5 in yLevels, "Expected a code line at Y=5")
    }

    // -----------------------------------------------------------------------
    // scanCodingZone – empty result when no blue glass exists
    // -----------------------------------------------------------------------

    @Test
    fun `scanCodingZone returns empty list when no blue glass blocks exist`() {
        val defaultAir = mockk<Block>(relaxed = true)
        every { defaultAir.type } returns Material.AIR
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns defaultAir

        val codeLines = scanner.scanCodingZone()
        assertTrue(codeLines.isEmpty())
    }
}
