package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.model.ItemVariableRef
import com.opencreativeplus.api.model.ItemVariableType
import com.opencreativeplus.api.registry.NodeRegistry
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Chest
import org.bukkit.block.Sign
import org.bukkit.block.TileState
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for BlockScanner PDC priority and Item_Variable detection.
 * Req 20.3: PDC values override sign values for the same parameter key.
 * Req 4.2: Item_Variable items detected by ocp:variable_type PDC tag.
 */
class BlockScannerPDCTest {

    private val world = mockk<World>(relaxed = true)
    private val nodeRegistry = mockk<NodeRegistry>(relaxed = true)
    private val scanner = BlockScanner(world, nodeRegistry, pluginNamespace = "opencreativeplus")

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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

    private fun Block.withRelative(face: BlockFace, relative: Block): Block {
        every { this@withRelative.getRelative(face) } returns relative
        return this
    }

    /** Attach a sign with given lines to [face] of [nodeBlock]. */
    private fun attachSign(nodeBlock: Block, face: BlockFace, vararg lines: String) {
        val signBlock = mockk<Block>(relaxed = true)
        val signState = mockk<Sign>(relaxed = true)
        every { signBlock.state } returns signState
        every { signState.lines } returns Array(4) { lines.getOrElse(it) { "" } }
        nodeBlock.withRelative(face, signBlock)
    }

    /** Make [face] of [nodeBlock] return an air block (no sign, no chest). */
    private fun attachAir(nodeBlock: Block, face: BlockFace) {
        val airBlock = mockk<Block>(relaxed = true)
        every { airBlock.state } returns mockk(relaxed = true)
        nodeBlock.withRelative(face, airBlock)
    }

    /** Attach no-sign air to all four horizontal faces. */
    private fun noSigns(nodeBlock: Block) {
        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            attachAir(nodeBlock, face)
        }
    }

    /** Attach a TileState block above [nodeBlock] whose PDC returns the given STRING entries. */
    private fun attachPDCBlock(
        nodeBlock: Block,
        entries: Map<String, String>
    ) {
        val tileBlock = mockk<Block>(relaxed = true)
        val tileState = mockk<TileState>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)

        every { tileBlock.state } returns tileState
        every { tileState.persistentDataContainer } returns pdc

        // Build the set of keys
        val keys = entries.keys.map { NamespacedKey("ocp", it) }.toSet()
        every { pdc.keys } returns keys

        // For each key, return the string value; return null for INTEGER and DOUBLE
        for ((k, v) in entries) {
            val nsKey = NamespacedKey("ocp", k)
            every { pdc.get(nsKey, PersistentDataType.STRING) } returns v
            every { pdc.get(nsKey, PersistentDataType.INTEGER) } returns null
            every { pdc.get(nsKey, PersistentDataType.DOUBLE) } returns null
        }

        nodeBlock.withRelative(BlockFace.UP, tileBlock)
    }

    /**
     * Build a mock block whose [Block.state] is a [TileState] with a PDC
     * containing the given STRING entries. Used for [readPDCParams] tests.
     */
    private fun mockTileBlock(entries: Map<String, String>): Block {
        val block = mockk<Block>(relaxed = true)
        val tileState = mockk<TileState>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)

        every { block.state } returns tileState
        every { tileState.persistentDataContainer } returns pdc

        val keys = entries.keys.map { NamespacedKey("ocp", it) }.toSet()
        every { pdc.keys } returns keys

        for ((k, v) in entries) {
            val nsKey = NamespacedKey("ocp", k)
            every { pdc.get(nsKey, PersistentDataType.STRING) } returns v
            every { pdc.get(nsKey, PersistentDataType.INTEGER) } returns null
            every { pdc.get(nsKey, PersistentDataType.DOUBLE) } returns null
        }

        return block
    }

    /**
     * Build a mock ItemStack whose ItemMeta PDC contains the given entries.
     * Pass null for [varName] to omit the ocp:variable_name key.
     */
    private fun mockItemVariableStack(varType: String, varName: String?): ItemStack {
        val item = mockk<ItemStack>(relaxed = true)
        val meta = mockk<ItemMeta>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)

        every { item.itemMeta } returns meta
        every { meta.persistentDataContainer } returns pdc

        val typeKey = NamespacedKey("opencreativeplus", "variable_type")
        val nameKey = NamespacedKey("opencreativeplus", "variable_name")

        every { pdc.get(typeKey, PersistentDataType.STRING) } returns varType
        every { pdc.get(nameKey, PersistentDataType.STRING) } returns varName

        return item
    }

    /** Attach a chest above [nodeBlock] containing the given items. */
    private fun attachChest(nodeBlock: Block, vararg items: ItemStack?) {
        val chestBlock = mockk<Block>(relaxed = true)
        val chestState = mockk<Chest>(relaxed = true)
        val inventory = mockk<Inventory>(relaxed = true)

        every { chestBlock.state } returns chestState
        every { chestState.inventory } returns inventory
        every { inventory.contents } returns arrayOf(*items)

        nodeBlock.withRelative(BlockFace.UP, chestBlock)
    }

    // -----------------------------------------------------------------------
    // readPDCParams — direct unit tests
    // -----------------------------------------------------------------------

    @Test
    fun `readPDCParams returns empty map for non-TileState block`() {
        val block = mockk<Block>(relaxed = true)
        every { block.state } returns mockk(relaxed = true) // not a TileState
        val result = scanner.readPDCParams(block)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `readPDCParams returns string value for ocp-namespaced key`() {
        val block = mockTileBlock(mapOf("speed" to "fast"))
        val result = scanner.readPDCParams(block)
        assertEquals("fast", result["speed"])
    }

    @Test
    fun `readPDCParams returns multiple entries`() {
        val block = mockTileBlock(mapOf("a" to "1", "b" to "2"))
        val result = scanner.readPDCParams(block)
        assertEquals("1", result["a"])
        assertEquals("2", result["b"])
    }

    @Test
    fun `readPDCParams ignores keys from other namespaces`() {
        val block = mockk<Block>(relaxed = true)
        val tileState = mockk<TileState>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { block.state } returns tileState
        every { tileState.persistentDataContainer } returns pdc

        // One ocp key, one foreign key
        val ocpKey = NamespacedKey("ocp", "myParam")
        val foreignKey = NamespacedKey("other", "foreignParam")
        every { pdc.keys } returns setOf(ocpKey, foreignKey)
        every { pdc.get(ocpKey, PersistentDataType.STRING) } returns "ocpValue"
        every { pdc.get(ocpKey, PersistentDataType.INTEGER) } returns null
        every { pdc.get(ocpKey, PersistentDataType.DOUBLE) } returns null

        val result = scanner.readPDCParams(block)
        assertEquals(1, result.size)
        assertEquals("ocpValue", result["myParam"])
        assertFalse(result.containsKey("foreignParam"))
    }

    // -----------------------------------------------------------------------
    // Req 20.3 — PDC priority over sign data
    // -----------------------------------------------------------------------

    @Test
    fun `extractParameters PDC value overrides sign value for same key (Req 20_3)`() {
        val nodeBlock = mockBlock(1, 6, 0, Material.PAPER)

        // Sign on NORTH face sets "speed=slow"
        attachSign(nodeBlock, BlockFace.NORTH, "speed=slow")
        attachAir(nodeBlock, BlockFace.SOUTH)
        attachAir(nodeBlock, BlockFace.EAST)
        attachAir(nodeBlock, BlockFace.WEST)

        // Block itself is a TileState with PDC key "speed=fast"
        val tileState = mockk<TileState>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { nodeBlock.state } returns tileState
        every { tileState.persistentDataContainer } returns pdc

        val speedKey = NamespacedKey("ocp", "speed")
        every { pdc.keys } returns setOf(speedKey)
        every { pdc.get(speedKey, PersistentDataType.STRING) } returns "fast"
        every { pdc.get(speedKey, PersistentDataType.INTEGER) } returns null
        every { pdc.get(speedKey, PersistentDataType.DOUBLE) } returns null

        // No chest above
        attachAir(nodeBlock, BlockFace.UP)

        val params = scanner.extractParameters(nodeBlock)
        assertEquals("fast", params["speed"], "PDC value must override sign value for the same key")
    }

    @Test
    fun `extractParameters sign value is kept when PDC has no overlapping key`() {
        val nodeBlock = mockBlock(1, 6, 0, Material.PAPER)

        // Sign sets "message=hello"
        attachSign(nodeBlock, BlockFace.NORTH, "message=hello")
        attachAir(nodeBlock, BlockFace.SOUTH)
        attachAir(nodeBlock, BlockFace.EAST)
        attachAir(nodeBlock, BlockFace.WEST)

        // PDC has a different key "count=5"
        val tileState = mockk<TileState>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { nodeBlock.state } returns tileState
        every { tileState.persistentDataContainer } returns pdc

        val countKey = NamespacedKey("ocp", "count")
        every { pdc.keys } returns setOf(countKey)
        every { pdc.get(countKey, PersistentDataType.STRING) } returns "5"
        every { pdc.get(countKey, PersistentDataType.INTEGER) } returns null
        every { pdc.get(countKey, PersistentDataType.DOUBLE) } returns null

        attachAir(nodeBlock, BlockFace.UP)

        val params = scanner.extractParameters(nodeBlock)
        assertEquals("hello", params["message"], "Sign value must be preserved when PDC has no overlap")
        assertEquals("5", params["count"], "PDC-only value must be present")
    }

    @Test
    fun `extractParameters PDC-only params returned when no sign data exists`() {
        val nodeBlock = mockBlock(1, 6, 0, Material.PAPER)
        noSigns(nodeBlock)

        val tileState = mockk<TileState>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { nodeBlock.state } returns tileState
        every { tileState.persistentDataContainer } returns pdc

        val key = NamespacedKey("ocp", "mode")
        every { pdc.keys } returns setOf(key)
        every { pdc.get(key, PersistentDataType.STRING) } returns "auto"
        every { pdc.get(key, PersistentDataType.INTEGER) } returns null
        every { pdc.get(key, PersistentDataType.DOUBLE) } returns null

        attachAir(nodeBlock, BlockFace.UP)

        val params = scanner.extractParameters(nodeBlock)
        assertEquals("auto", params["mode"])
    }

    // -----------------------------------------------------------------------
    // Req 4.2 — Item_Variable detection via ocp:variable_type PDC tag
    // -----------------------------------------------------------------------

    @Test
    fun `extractParameters detects Item_Variable item in chest above (Req 4_2)`() {
        val nodeBlock = mockBlock(1, 6, 0, Material.PAPER)
        noSigns(nodeBlock)
        every { nodeBlock.state } returns mockk(relaxed = true) // no PDC

        val item = mockItemVariableStack("PLAYER_REFERENCE", "targetPlayer")
        attachChest(nodeBlock, item)

        val params = scanner.extractParameters(nodeBlock)
        val ref = params["item_var_player_reference"]
        assertIs<ItemVariableRef>(ref)
        assertEquals("targetPlayer", ref.name)
        assertEquals(ItemVariableType.PLAYER_REFERENCE, ref.type)
    }

    @Test
    fun `extractParameters detects multiple Item_Variable items of different types`() {
        val nodeBlock = mockBlock(1, 6, 0, Material.PAPER)
        noSigns(nodeBlock)
        every { nodeBlock.state } returns mockk(relaxed = true)

        val playerItem = mockItemVariableStack("PLAYER_REFERENCE", "p1")
        val locationItem = mockItemVariableStack("LOCATION_REFERENCE", "loc1")
        attachChest(nodeBlock, playerItem, locationItem)

        val params = scanner.extractParameters(nodeBlock)

        val playerRef = params["item_var_player_reference"]
        assertIs<ItemVariableRef>(playerRef)
        assertEquals("p1", playerRef.name)
        assertEquals(ItemVariableType.PLAYER_REFERENCE, playerRef.type)

        val locRef = params["item_var_location_reference"]
        assertIs<ItemVariableRef>(locRef)
        assertEquals("loc1", locRef.name)
        assertEquals(ItemVariableType.LOCATION_REFERENCE, locRef.type)
    }

    @Test
    fun `extractParameters ignores Item_Variable item missing variable_name`() {
        val nodeBlock = mockBlock(1, 6, 0, Material.PAPER)
        noSigns(nodeBlock)
        every { nodeBlock.state } returns mockk(relaxed = true)

        // varName = null → should be ignored
        val item = mockItemVariableStack("PLAYER_REFERENCE", null)
        attachChest(nodeBlock, item)

        val params = scanner.extractParameters(nodeBlock)
        assertFalse(params.containsKey("item_var_player_reference"), "Item without variable_name must be ignored")
    }

    @Test
    fun `extractParameters ignores Item_Variable item with unknown variable_type`() {
        val nodeBlock = mockBlock(1, 6, 0, Material.PAPER)
        noSigns(nodeBlock)
        every { nodeBlock.state } returns mockk(relaxed = true)

        val item = mockItemVariableStack("UNKNOWN_TYPE", "someVar")
        attachChest(nodeBlock, item)

        val params = scanner.extractParameters(nodeBlock)
        assertFalse(params.containsKey("item_var_unknown_type"), "Item with unknown variable_type must be ignored")
    }

    @Test
    fun `extractParameters returns no Item_Variable entries when no chest above block`() {
        val nodeBlock = mockBlock(1, 6, 0, Material.PAPER)
        noSigns(nodeBlock)
        every { nodeBlock.state } returns mockk(relaxed = true)

        // No chest — just air above
        attachAir(nodeBlock, BlockFace.UP)

        val params = scanner.extractParameters(nodeBlock)
        val itemVarKeys = params.keys.filter { it.startsWith("item_var_") }
        assertTrue(itemVarKeys.isEmpty(), "No item_var_ entries expected when there is no chest above")
    }

    @Test
    fun `extractParameters ignores null slots in chest inventory`() {
        val nodeBlock = mockBlock(1, 6, 0, Material.PAPER)
        noSigns(nodeBlock)
        every { nodeBlock.state } returns mockk(relaxed = true)

        val item = mockItemVariableStack("NUMBER_REFERENCE", "myNum")
        // Mix of null and non-null slots
        attachChest(nodeBlock, null, item, null)

        val params = scanner.extractParameters(nodeBlock)
        val ref = params["item_var_number_reference"]
        assertIs<ItemVariableRef>(ref)
        assertEquals("myNum", ref.name)
    }
}
