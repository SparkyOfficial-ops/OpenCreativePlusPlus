package com.opencreativeplus.plugin.gui

import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.plugin.input.SignInputManager
import com.opencreativeplus.core.serialization.ParamSerializer
import com.opencreativeplus.api.registry.NodeRegistry
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Unit tests for SmartGUI.
 * All Bukkit classes are mocked — no running server required.
 *
 * s: 1.1, 1.2, 1.9
 */
class SmartGUITest {

    private lateinit var player: Player
    private lateinit var block: Block
    private lateinit var nodeRegistry: NodeRegistry
    private lateinit var signInputManager: SignInputManager
    private lateinit var paramSerializer: ParamSerializer
    private lateinit var variableManager: VariableManager
    private lateinit var inventory: Inventory
    private lateinit var mockItem: org.bukkit.inventory.ItemStack
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val plotId: UUID = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        player = mockk(relaxed = true)
        block = mockk(relaxed = true)
        nodeRegistry = mockk(relaxed = true)
        signInputManager = mockk(relaxed = true)
        paramSerializer = mockk(relaxed = true)
        variableManager = mockk(relaxed = true)
        inventory = mockk(relaxed = true)
        mockItem = mockk(relaxed = true)

        every { player.uniqueId } returns UUID.randomUUID()
        every { inventory.setItem(any(), any()) } just Runs
        every { inventory.getItem(any()) } returns null
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // Test 1: open() renders all params as items
    // -------------------------------------------------------------------------

    @Test
    fun `open renders all params as items in inventory`() {
        // s: 1.2
        val gui = makeGui()
        gui.registerParam("speed", ParamType.INT, 5)
        gui.registerParam("message", ParamType.STRING, "hello")

        gui.open()

        // Each param gets at least one setItem call (edit slot) + save slot
        verify(atLeast = 3) { inventory.setItem(any(), any()) }
    }

    @Test
    fun `open places save item at slot 53`() {
        // s: 1.9
        val gui = makeGui()
        gui.registerParam("x", ParamType.STRING, "val")

        gui.open()

        verify { inventory.setItem(53, any()) }
    }

    @Test
    fun `open with no params still places save item`() {
        val gui = makeGui()
        gui.open()
        verify { inventory.setItem(53, any()) }
    }

    // -------------------------------------------------------------------------
    // Test 2: handleClick on Choose Variable slot opens VariableSuggestionMenu
    // -------------------------------------------------------------------------

    @Test
    fun `handleClick on variable slot closes inventory and launches variable menu`() {
        // s: 2.1, 2.2
        val gui = makeGui()
        gui.registerParam("target", ParamType.VARIABLE_REF, "")

        // Render so slots are populated
        gui.renderParams(mapOf("target" to ""))

        // The variable slot for the first VARIABLE_REF param is slot 1 (edit=0, var=1)
        gui.handleClick(1, player)

        // Player's inventory should be closed when opening variable menu
        verify { player.closeInventory() }
    }

    @Test
    fun `handleClick on edit slot launches editParam coroutine`() {
        // s: 1.3
        val gui = makeGui()
        gui.registerParam("msg", ParamType.STRING, "hello")
        gui.renderParams(mapOf("msg" to "hello"))

        // Edit slot for first STRING param is slot 0
        // We just verify no exception is thrown and player interaction is triggered
        gui.handleClick(0, player)
        // No exception = success; coroutine is launched asynchronously
    }

    // -------------------------------------------------------------------------
    // Test 3: save() persists all params via ParamSerializer
    // -------------------------------------------------------------------------

    @Test
    fun `save persists all current params via paramSerializer`() {
        // s: 1.9
        val gui = makeGui()
        // Directly populate currentParams to avoid Bukkit inventory rendering
        gui.currentParams["name"] = "Alice" to ParamType.STRING
        gui.currentParams["count"] = 42 to ParamType.INT

        gui.save()

        verify { paramSerializer.save(block, "name", "Alice") }
        verify { paramSerializer.save(block, "count", 42) }
        verify { player.sendMessage("§aParameters saved.") }
    }

    @Test
    fun `save with no params sends saved message without calling paramSerializer`() {
        val gui = makeGui()
        gui.save()
        verify(exactly = 0) { paramSerializer.save(any(), any(), any()) }
        verify { player.sendMessage("§aParameters saved.") }
    }

    // -------------------------------------------------------------------------
    // Test 4: editParam saves value and reopens GUI
    // -------------------------------------------------------------------------

    @Test
    fun `editParam saves non-null input and reopens GUI`() = runTest {
        val gui = makeGui()
        gui.currentParams["msg"] = "old" to ParamType.STRING

        coEvery { signInputManager.awaitSignInput(player, any()) } returns "newValue"

        gui.editParam("msg", ParamType.STRING)

        verify { paramSerializer.save(block, "msg", "newValue") }
        // open() is called → setItem(53, ...) for save button
        verify { inventory.setItem(53, any()) }
    }

    @Test
    fun `editParam with null input does not save and still reopens GUI`() = runTest {
        val gui = makeGui()
        gui.currentParams["msg"] = "old" to ParamType.STRING

        coEvery { signInputManager.awaitSignInput(player, any()) } returns null

        gui.editParam("msg", ParamType.STRING)

        verify(exactly = 0) { paramSerializer.save(any(), any(), any()) }
        // GUI still reopens
        verify { inventory.setItem(53, any()) }
    }

    // -------------------------------------------------------------------------
    // Test 5: collectCurrentParams returns all registered params
    // -------------------------------------------------------------------------

    @Test
    fun `collectCurrentParams returns all registered param values`() {
        val gui = makeGui()
        gui.currentParams["alpha"] = "foo" to ParamType.STRING
        gui.currentParams["beta"] = 99 to ParamType.INT

        val result = gui.collectCurrentParams()

        assertEquals("foo", result["alpha"])
        assertEquals(99, result["beta"])
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeGui(): SmartGUI = SmartGUI(
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
}
