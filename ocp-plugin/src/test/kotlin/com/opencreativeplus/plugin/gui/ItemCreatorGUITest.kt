package com.opencreativeplus.plugin.gui

import com.opencreativeplus.plugin.input.SignInputManager
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for ItemCreatorGUI.
 * Requirements: 3.1–3.11
 *
 * Uses injectable seams (inventoryFactory, listenerUnregister) to avoid
 * static mocking of Bukkit and HandlerList.
 * Uses Dispatchers.Unconfined so coroutines launched in onInventoryClick
 * run synchronously, enabling direct assertions after the call.
 */
class ItemCreatorGUITest {

    private lateinit var plugin: Plugin
    private lateinit var pluginManager: PluginManager
    private lateinit var signInputManager: SignInputManager
    private lateinit var player: Player
    private lateinit var playerInventory: PlayerInventory
    private lateinit var mockInventory: Inventory
    private lateinit var world: World

    // Tracks whether listenerUnregister was called
    private var listenerUnregistered = false

    // Unconfined scope: coroutines run synchronously in tests
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private lateinit var gui: ItemCreatorGUI

    @BeforeEach
    fun setup() {
        val server = mockk<org.bukkit.Server>(relaxed = true)
        pluginManager = mockk(relaxed = true)
        plugin = mockk(relaxed = true)
        every { plugin.server } returns server
        every { server.pluginManager } returns pluginManager
        every { pluginManager.registerEvents(any(), any()) } just Runs

        world = mockk(relaxed = true)
        playerInventory = mockk(relaxed = true)
        player = mockk(relaxed = true)
        every { player.inventory } returns playerInventory
        every { player.world } returns world
        // Default: inventory has space (no leftover)
        every { playerInventory.addItem(any()) } returns hashMapOf()

        mockInventory = mockk(relaxed = true)
        signInputManager = mockk()
        listenerUnregistered = false

        gui = ItemCreatorGUI(
            plugin = plugin,
            signInputManager = signInputManager,
            scope = scope,
            inventoryFactory = { _, _ -> mockInventory },
            listenerUnregister = { listenerUnregistered = true }
        )
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    // =========================================================================
    // open() — Requirements 3.1–3.5
    // =========================================================================

    /**
     * Req 3.1–3.4: GUI opens with 9 slots and places 4 icon items at slots 0–3.
     */
    @Test
    fun `open places 4 icon items at slots 0 through 3`() {
        gui.open(player)

        verify { mockInventory.setItem(eq(0), any()) } // BOOK — Req 3.2
        verify { mockInventory.setItem(eq(1), any()) } // MAGMA_CREAM — Req 3.3
        verify { mockInventory.setItem(eq(2), any()) } // IRON_INGOT — Req 3.4
        verify { mockInventory.setItem(eq(3), any()) } // COMPASS — Req 3.5
    }

    /**
     * Req 3.1: open() registers the event listener and opens the inventory for the player.
     */
    @Test
    fun `open registers event listener and opens inventory for player`() {
        gui.open(player)

        verify { pluginManager.registerEvents(gui, plugin) }
        verify { player.openInventory(mockInventory) }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun makeClickEvent(slot: Int): InventoryClickEvent {
        val view = mockk<org.bukkit.inventory.InventoryView>(relaxed = true)
        @Suppress("DEPRECATION")
        every { view.title } returns "§8Item Creator"

        val event = mockk<InventoryClickEvent>(relaxed = true)
        every { event.whoClicked } returns player
        every { event.view } returns view
        every { event.rawSlot } returns slot
        every { event.isCancelled = any() } just Runs
        return event
    }

    // =========================================================================
    // Slot 0 — Text (BOOK) — Requirement 3.6
    // =========================================================================

    /**
     * Req 3.6: Clicking BOOK (slot 0) calls awaitSignInput and delivers item to inventory.
     */
    @Test
    fun `click on slot 0 calls awaitSignInput and delivers item to inventory`() = runTest {
        coEvery { signInputManager.awaitSignInput(player, "") } returns "hello world"

        gui.onInventoryClick(makeClickEvent(0))

        coVerify { signInputManager.awaitSignInput(player, "") }
        verify { playerInventory.addItem(any()) }
    }

    /**
     * Req 3.6: Null sign input (cancelled) does not deliver an item.
     */
    @Test
    fun `click on slot 0 with null sign input does not deliver item`() = runTest {
        coEvery { signInputManager.awaitSignInput(player, "") } returns null

        gui.onInventoryClick(makeClickEvent(0))

        verify(exactly = 0) { playerInventory.addItem(any()) }
    }

    // =========================================================================
    // Slot 1 — Number (MAGMA_CREAM) — Requirement 3.7
    // =========================================================================

    /**
     * Req 3.7: Valid numeric input creates a Number item and delivers it.
     */
    @Test
    fun `click on slot 1 with valid number delivers item to inventory`() = runTest {
        coEvery { signInputManager.awaitSignInput(player, "") } returns "42.5"

        gui.onInventoryClick(makeClickEvent(1))

        coVerify { signInputManager.awaitSignInput(player, "") }
        verify { playerInventory.addItem(any()) }
    }

    /**
     * Req 3.7: Non-numeric input sends error message and does NOT deliver an item.
     */
    @Test
    fun `click on slot 1 with non-numeric input sends error and does not deliver item`() = runTest {
        coEvery { signInputManager.awaitSignInput(player, "") } returns "not_a_number"

        gui.onInventoryClick(makeClickEvent(1))

        verify { player.sendMessage(match<String> { it.contains("корректное число") }) }
        verify(exactly = 0) { playerInventory.addItem(any()) }
    }

    /**
     * Req 3.7: Null sign input (cancelled) does not deliver an item.
     */
    @Test
    fun `click on slot 1 with null sign input does not deliver item`() = runTest {
        coEvery { signInputManager.awaitSignInput(player, "") } returns null

        gui.onInventoryClick(makeClickEvent(1))

        verify(exactly = 0) { playerInventory.addItem(any()) }
    }

    // =========================================================================
    // Slot 2 — Variable (IRON_INGOT) — Requirement 3.8
    // =========================================================================

    /**
     * Req 3.8: Clicking IRON_INGOT (slot 2) calls awaitSignInput and delivers item.
     */
    @Test
    fun `click on slot 2 calls awaitSignInput and delivers item to inventory`() = runTest {
        coEvery { signInputManager.awaitSignInput(player, "") } returns "myVar"

        gui.onInventoryClick(makeClickEvent(2))

        coVerify { signInputManager.awaitSignInput(player, "") }
        verify { playerInventory.addItem(any()) }
    }

    /**
     * Req 3.8: Null sign input (cancelled) does not deliver an item.
     */
    @Test
    fun `click on slot 2 with null sign input does not deliver item`() = runTest {
        coEvery { signInputManager.awaitSignInput(player, "") } returns null

        gui.onInventoryClick(makeClickEvent(2))

        verify(exactly = 0) { playerInventory.addItem(any()) }
    }

    // =========================================================================
    // Slot 3 — Location (COMPASS) — Requirement 3.9
    // =========================================================================

    /**
     * Req 3.9: Clicking COMPASS (slot 3) uses player.location and delivers item
     * WITHOUT calling awaitSignInput.
     */
    @Test
    fun `click on slot 3 uses player location and delivers item without sign input`() = runTest {
        val loc = mockk<Location>(relaxed = true)
        val locWorld = mockk<World>(relaxed = true)
        every { loc.x } returns 10.0
        every { loc.y } returns 64.0
        every { loc.z } returns -5.0
        every { loc.yaw } returns 90f
        every { loc.pitch } returns 0f
        every { loc.world } returns locWorld
        every { locWorld.name } returns "world"
        every { player.location } returns loc

        gui.onInventoryClick(makeClickEvent(3))

        coVerify(exactly = 0) { signInputManager.awaitSignInput(any(), any()) }
        verify { playerInventory.addItem(any()) }
    }

    // =========================================================================
    // Inventory full — Requirements 3.10, 3.11
    // =========================================================================

    /**
     * Req 3.10: Successful item creation adds item to player inventory without dropping.
     */
    @Test
    fun `successful item creation adds item to player inventory`() = runTest {
        coEvery { signInputManager.awaitSignInput(player, "") } returns "test text"
        every { playerInventory.addItem(any()) } returns hashMapOf()

        gui.onInventoryClick(makeClickEvent(0))

        verify { playerInventory.addItem(any()) }
        verify(exactly = 0) { world.dropItem(any(), any()) }
    }

    /**
     * Req 3.11: When inventory is full, item is dropped at player's feet and error message is sent.
     */
    @Test
    fun `when inventory is full item is dropped at player feet and error message is sent`() = runTest {
        coEvery { signInputManager.awaitSignInput(player, "") } returns "overflow text"

        val leftoverItem = mockk<ItemStack>(relaxed = true)
        every { playerInventory.addItem(any()) } returns hashMapOf(0 to leftoverItem)

        val loc = mockk<Location>(relaxed = true)
        every { player.location } returns loc

        gui.onInventoryClick(makeClickEvent(0))

        verify { world.dropItem(loc, any()) }
        verify { player.sendMessage(match<String> { it.contains("Инвентарь полон") }) }
    }

    // =========================================================================
    // Event handler guards
    // =========================================================================

    /**
     * Clicks outside slots 0–3 are ignored.
     */
    @Test
    fun `click on slot outside 0-3 is ignored`() = runTest {
        val view = mockk<org.bukkit.inventory.InventoryView>(relaxed = true)
        @Suppress("DEPRECATION")
        every { view.title } returns "§8Item Creator"
        val event = mockk<InventoryClickEvent>(relaxed = true)
        every { event.whoClicked } returns player
        every { event.view } returns view
        every { event.rawSlot } returns 5
        every { event.isCancelled = any() } just Runs

        gui.onInventoryClick(event)

        coVerify(exactly = 0) { signInputManager.awaitSignInput(any(), any()) }
        verify(exactly = 0) { playerInventory.addItem(any()) }
    }

    /**
     * Clicks in a different inventory (wrong title) are ignored entirely.
     */
    @Test
    fun `click in different inventory is ignored`() = runTest {
        val view = mockk<org.bukkit.inventory.InventoryView>(relaxed = true)
        @Suppress("DEPRECATION")
        every { view.title } returns "Some Other GUI"
        val event = mockk<InventoryClickEvent>(relaxed = true)
        every { event.whoClicked } returns player
        every { event.view } returns view
        every { event.rawSlot } returns 0
        every { event.isCancelled = any() } just Runs

        gui.onInventoryClick(event)

        coVerify(exactly = 0) { signInputManager.awaitSignInput(any(), any()) }
        verify(exactly = 0) { playerInventory.addItem(any()) }
    }

    /**
     * A valid click unregisters the listener and closes the player's inventory.
     */
    @Test
    fun `valid click unregisters listener and closes player inventory`() = runTest {
        coEvery { signInputManager.awaitSignInput(player, "") } returns "text"

        gui.onInventoryClick(makeClickEvent(0))

        assert(listenerUnregistered) { "listenerUnregister should have been called" }
        verify { player.closeInventory() }
    }
}
