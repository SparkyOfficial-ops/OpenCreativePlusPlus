package com.opencreativeplus.plugin

import com.opencreativeplus.api.registry.NodeRegistry
import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.core.serialization.ParamSerializer
import com.opencreativeplus.plugin.gui.ParamType
import com.opencreativeplus.plugin.gui.SmartGUI
import com.opencreativeplus.plugin.input.SignInputManager
import com.opencreativeplus.plugin.scanner.BlockScanner
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.TileState
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end integration test: SmartGUI → SignInput → ParamSerializer → BlockScanner PDC round-trip.
 *
 * Validates the full pipeline:
 *   1. SmartGUI opens and renders parameters (s 1.1, 1.2)
 *   2. Player clicks Edit → SignInputManager intercepts sign input → value saved via ParamSerializer (s 1.3–1.8)
 *   3. ParamSerializer writes to block PDC (s 1.9, 20.1)
 *   4. BlockScanner reads PDC and returns the saved value (s 20.2, 20.3, 20.4, 20.5)
 *
 * All Bukkit classes are mocked — no running server required.
 *
 * s: 1.1–1.9, 20.1–20.5
 */
class SmartGUIIntegrationTest {

    // -------------------------------------------------------------------------
    // Shared mocks
    // -------------------------------------------------------------------------

    private lateinit var player: Player
    private lateinit var block: Block
    private lateinit var tileState: TileState
    private lateinit var pdc: PersistentDataContainer
    private lateinit var inventory: Inventory
    private lateinit var mockItem: ItemStack
    private lateinit var world: World
    private lateinit var nodeRegistry: NodeRegistry
    private lateinit var variableManager: VariableManager

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val plotId: UUID = UUID.randomUUID()
    private val pluginNamespace = "opencreativeplus"

    /** In-memory PDC store: NamespacedKey.toString() → value */
    private val pdcStore = mutableMapOf<String, Any>()

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    @BeforeEach
    fun setup() {
        player = mockk(relaxed = true)
        block = mockk(relaxed = true)
        tileState = mockk(relaxed = true)
        pdc = mockk(relaxed = true)
        inventory = mockk(relaxed = true)
        mockItem = mockk(relaxed = true)
        world = mockk(relaxed = true)
        nodeRegistry = mockk(relaxed = true)
        variableManager = mockk(relaxed = true)

        every { player.uniqueId } returns UUID.randomUUID()
        every { inventory.setItem(any(), any()) } just Runs
        every { inventory.getItem(any()) } returns null

        // Wire block → TileState → PDC
        every { block.state } returns tileState
        every { tileState.persistentDataContainer } returns pdc
        every { tileState.update() } returns true

        // PDC write: capture into pdcStore using slots
        val stringKeySlot = slot<NamespacedKey>()
        val stringValSlot = slot<String>()
        every { pdc.set(capture(stringKeySlot), PersistentDataType.STRING, capture(stringValSlot)) } answers {
            pdcStore[stringKeySlot.captured.toString()] = stringValSlot.captured
        }

        val intKeySlot = slot<NamespacedKey>()
        val intValSlot = slot<Int>()
        every { pdc.set(capture(intKeySlot), PersistentDataType.INTEGER, capture(intValSlot)) } answers {
            pdcStore[intKeySlot.captured.toString()] = intValSlot.captured
        }

        val doubleKeySlot = slot<NamespacedKey>()
        val doubleValSlot = slot<Double>()
        every { pdc.set(capture(doubleKeySlot), PersistentDataType.DOUBLE, capture(doubleValSlot)) } answers {
            pdcStore[doubleKeySlot.captured.toString()] = doubleValSlot.captured
        }

        val byteKeySlot = slot<NamespacedKey>()
        val byteValSlot = slot<Byte>()
        every { pdc.set(capture(byteKeySlot), PersistentDataType.BYTE, capture(byteValSlot)) } answers {
            pdcStore[byteKeySlot.captured.toString()] = byteValSlot.captured
        }

        // PDC read: serve from pdcStore
        val readStringKeySlot = slot<NamespacedKey>()
        every { pdc.get(capture(readStringKeySlot), PersistentDataType.STRING) } answers {
            pdcStore[readStringKeySlot.captured.toString()] as? String
        }

        val readIntKeySlot = slot<NamespacedKey>()
        every { pdc.get(capture(readIntKeySlot), PersistentDataType.INTEGER) } answers {
            pdcStore[readIntKeySlot.captured.toString()] as? Int
        }

        val readDoubleKeySlot = slot<NamespacedKey>()
        every { pdc.get(capture(readDoubleKeySlot), PersistentDataType.DOUBLE) } answers {
            pdcStore[readDoubleKeySlot.captured.toString()] as? Double
        }

        val readByteKeySlot = slot<NamespacedKey>()
        every { pdc.get(capture(readByteKeySlot), PersistentDataType.BYTE) } answers {
            pdcStore[readByteKeySlot.captured.toString()] as? Byte
        }

        // PDC keys: derive from pdcStore keys
        every { pdc.keys } answers {
            pdcStore.keys.map { k ->
                val parts = k.split(":")
                NamespacedKey(parts[0], parts[1])
            }.toSet()
        }

        // Block faces for BlockScanner (no signs, no chest above)
        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP)) {
            val airBlock = mockk<Block>(relaxed = true)
            every { airBlock.state } returns mockk(relaxed = true)
            every { block.getRelative(face) } returns airBlock
        }
    }

    @AfterEach
    fun teardown() {
        pdcStore.clear()
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeParamSerializer(): ParamSerializer =
        ParamSerializer { name -> NamespacedKey("ocp", name) }

    private fun makeGui(
        signInputManager: SignInputManager,
        paramSerializer: ParamSerializer
    ): SmartGUI = SmartGUI(
        player = player,
        block = block,
        nodeRegistry = nodeRegistry,
        signInputManager = signInputManager,
        paramSerializer = paramSerializer,
        scope = scope,
        plotId = plotId,
        variableManager = variableManager,
        inventoryFactory = { inventory },
        itemFactory = { _, _, _ -> mockItem },
        menuInventoryFactory = { inventory }
    )

    private fun makeBlockScanner(): BlockScanner =
        BlockScanner(world, nodeRegistry, pluginNamespace)

    // -------------------------------------------------------------------------
    // Test 1: Full round-trip — SmartGUI edit → PDC → BlockScanner reads back
    // -------------------------------------------------------------------------

    /**
     * The core round-trip:
     *   SmartGUI.editParam saves a String value via ParamSerializer →
     *   BlockScanner.readPDCParams reads it back from the same PDC.
     *
     * s: 1.3–1.6, 20.1, 20.2, 20.4, 20.5
     */
    @Test
    fun `editParam saves value via ParamSerializer and BlockScanner reads it back`() = runTest {
        val signInputManager = mockk<SignInputManager>()
        coEvery { signInputManager.awaitSignInput(player, any()) } returns "HelloWorld"

        val paramSerializer = makeParamSerializer()
        val gui = makeGui(signInputManager, paramSerializer)
        gui.registerParam("message", ParamType.STRING, "")

        // Act: simulate player editing the "message" param
        gui.editParam("message", ParamType.STRING)

        // Assert: ParamSerializer wrote to PDC
        assertEquals("HelloWorld", pdcStore["ocp:message"], "PDC should contain the saved value")

        // Assert: BlockScanner reads the same value back
        val scanner = makeBlockScanner()
        val params = scanner.readPDCParams(block)
        assertEquals("HelloWorld", params["message"], "BlockScanner must read back the value written by ParamSerializer")
    }

    /**
     * Round-trip for an Int parameter.
     * s: 20.4, 20.5
     */
    @Test
    fun `save and load Int parameter round-trip`() {
        val paramSerializer = makeParamSerializer()
        paramSerializer.save(block, "count", 42)

        val scanner = makeBlockScanner()
        val params = scanner.readPDCParams(block)
        assertEquals(42, params["count"])
    }

    /**
     * Round-trip for a Double parameter.
     * s: 20.4, 20.5
     */
    @Test
    fun `save and load Double parameter round-trip`() {
        val paramSerializer = makeParamSerializer()
        paramSerializer.save(block, "speed", 3.14)

        val scanner = makeBlockScanner()
        val params = scanner.readPDCParams(block)
        assertEquals(3.14, params["speed"])
    }

    // -------------------------------------------------------------------------
    // Test 2: SmartGUI.save() persists all params → BlockScanner reads all back
    // -------------------------------------------------------------------------

    /**
     * SmartGUI.save() writes all currentParams to PDC.
     * BlockScanner.readPDCParams returns all of them.
     *
     * s: 1.9, 20.1, 20.2, 20.3
     */
    @Test
    fun `SmartGUI save persists all params and BlockScanner reads them all back`() {
        val signInputManager = mockk<SignInputManager>(relaxed = true)
        val paramSerializer = makeParamSerializer()
        val gui = makeGui(signInputManager, paramSerializer)

        // Populate params directly (bypasses Bukkit inventory rendering)
        gui.currentParams["name"] = "Alice" to ParamType.STRING
        gui.currentParams["level"] = 5 to ParamType.INT

        gui.save()

        val scanner = makeBlockScanner()
        val params = scanner.readPDCParams(block)

        assertEquals("Alice", params["name"])
        assertEquals(5, params["level"])
    }

    // -------------------------------------------------------------------------
    // Test 3: PDC priority over sign data (s 20.3)
    // -------------------------------------------------------------------------

    /**
     * When both sign data and PDC data exist for the same key,
     * BlockScanner.extractParameters must prefer the PDC value.
     *
     * s: 20.3
     */
    @Test
    fun `PDC value overrides sign value in extractParameters`() {
        val paramSerializer = makeParamSerializer()
        // Write "fast" to PDC for key "speed"
        paramSerializer.save(block, "speed", "fast")

        // Attach a sign on NORTH face with "speed=slow"
        val signBlock = mockk<Block>(relaxed = true)
        val signState = mockk<org.bukkit.block.Sign>(relaxed = true)
        every { signBlock.state } returns signState
        every { signState.lines } returns arrayOf("speed=slow", "", "", "")
        every { block.getRelative(BlockFace.NORTH) } returns signBlock

        val scanner = makeBlockScanner()
        val params = scanner.extractParameters(block)

        assertEquals("fast", params["speed"], "PDC value must override sign value (s 20.3)")
    }

    // -------------------------------------------------------------------------
    // Test 4: Sign input cancel — no save, GUI reopens (s 1.5)
    // -------------------------------------------------------------------------

    /**
     * When the player cancels the sign input (awaitSignInput returns null),
     * nothing is saved, and the GUI reopens.
     *
     * s: 1.5
     */
    @Test
    fun `editParam with cancel does not save and reopens GUI`() = runTest {
        val signInputManager = mockk<SignInputManager>()
        coEvery { signInputManager.awaitSignInput(player, any()) } returns null

        val paramSerializer = makeParamSerializer()
        val gui = makeGui(signInputManager, paramSerializer)
        gui.currentParams["msg"] = "original" to ParamType.STRING

        gui.editParam("msg", ParamType.STRING)

        // PDC should not have been written
        assertTrue(pdcStore.isEmpty(), "PDC must not be written when player cancels")

        // GUI still reopens (save slot is set)
        verify { inventory.setItem(53, any()) }
    }

    // -------------------------------------------------------------------------
    // Test 5: Multiple params — full pipeline (s 1.1–1.9, 20.1–20.5)
    // -------------------------------------------------------------------------

    /**
     * Full pipeline with multiple parameters of different types:
     *   registerParam → save → BlockScanner reads all back.
     *
     * s: 1.1, 1.2, 1.9, 20.1, 20.4, 20.5
     */
    @Test
    fun `multiple params of different types survive full round-trip`() {
        val signInputManager = mockk<SignInputManager>(relaxed = true)
        val paramSerializer = makeParamSerializer()
        val gui = makeGui(signInputManager, paramSerializer)

        gui.currentParams["title"] = "Boss Fight" to ParamType.STRING
        gui.currentParams["radius"] = 15 to ParamType.INT
        gui.currentParams["damage"] = 2.5 to ParamType.DOUBLE

        gui.save()

        val scanner = makeBlockScanner()
        val params = scanner.readPDCParams(block)

        assertEquals("Boss Fight", params["title"])
        assertEquals(15, params["radius"])
        assertEquals(2.5, params["damage"])
    }

    // -------------------------------------------------------------------------
    // Test 6: ParamSerializer.load reads back what save wrote (s 20.5)
    // -------------------------------------------------------------------------

    /**
     * ParamSerializer.load must return the same value that was saved (round-trip property).
     * s: 20.5
     */
    @Test
    fun `ParamSerializer load returns same value as save for String`() {
        val paramSerializer = makeParamSerializer()
        paramSerializer.save(block, "greeting", "Hello")
        val loaded = paramSerializer.load(block, "greeting")
        assertEquals("Hello", loaded)
    }

    @Test
    fun `ParamSerializer load returns null for absent key`() {
        val paramSerializer = makeParamSerializer()
        val loaded = paramSerializer.load(block, "nonexistent")
        assertNull(loaded)
    }

    // -------------------------------------------------------------------------
    // Test 7: registerParam loads existing PDC value (s 1.2, 20.2)
    // -------------------------------------------------------------------------

    /**
     * When SmartGUI.registerParam is called and the block already has a PDC value,
     * the existing value is loaded rather than the initialValue default.
     *
     * s: 1.2, 20.2
     */
    @Test
    fun `registerParam loads existing PDC value over default`() {
        val paramSerializer = makeParamSerializer()
        // Pre-populate PDC with a saved value
        paramSerializer.save(block, "weapon", "sword")

        val signInputManager = mockk<SignInputManager>(relaxed = true)
        val gui = makeGui(signInputManager, paramSerializer)

        // Register with a different default — should load "sword" from PDC
        gui.registerParam("weapon", ParamType.STRING, "default_weapon")

        val (value, _) = gui.currentParams["weapon"]!!
        assertEquals("sword", value, "registerParam must load existing PDC value, not the default")
    }
}
