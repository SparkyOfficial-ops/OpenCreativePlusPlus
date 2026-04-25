package com.opencreativeplus.plugin.gui

import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.core.execution.VariableScopeImpl
import io.mockk.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Unit tests for VariableExplorerGUI.
 * All Bukkit classes are mocked — no running server required.
 *
 * s: 2.1, 2.4, 2.7
 */
class VariableExplorerGUITest {

    private val plotId: UUID = UUID.randomUUID()
    private lateinit var variableManager: VariableManager
    private lateinit var plugin: Plugin
    private lateinit var player: Player
    private lateinit var inventory: Inventory
    private lateinit var scheduler: BukkitScheduler
    private lateinit var mockMeta: ItemMeta

    @BeforeEach
    fun setup() {
        variableManager = mockk(relaxed = true)
        plugin = mockk(relaxed = true)
        player = mockk(relaxed = true)
        inventory = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)

        every { player.uniqueId } returns UUID.randomUUID()

        // Stub changes() so the init block subscription doesn't fail
        every { variableManager.changes(plotId) } returns emptyFlow()

        // Stub Bukkit statics
        mockkStatic("org.bukkit.Bukkit")
        every { Bukkit.createInventory(any(), any<Int>(), any<String>()) } returns inventory
        every { Bukkit.getScheduler() } returns scheduler
        every { inventory.setItem(any(), any()) } just Runs
        every { inventory.getItem(any()) } returns null

        // Mock ItemStack construction so real Bukkit server isn't needed
        mockkConstructor(ItemStack::class)
        mockMeta = mockk(relaxed = true)
        every { anyConstructed<ItemStack>().itemMeta } returns mockMeta
        every { anyConstructed<ItemStack>().setItemMeta(any()) } returns true
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // Test 1: Empty scope → info item
    // -------------------------------------------------------------------------

    @Test
    fun `buildPagedInventory with empty scopes returns 9-slot inventory with info item at slot 0`() {
        // Both scopes empty
        every { variableManager.getPlotScope(plotId) } returns VariableScopeImpl()
        coEvery { variableManager.getSavedScope(plotId) } returns VariableScopeImpl()

        val gui = VariableExplorerGUI(plotId, variableManager, plugin, TestScope())

        gui.buildPagedInventory(0)

        // Empty state: must create a 9-slot inventory
        verify { Bukkit.createInventory(any(), 9, any<String>()) }

        // Slot 0 must receive an item (the info pane)
        verify { inventory.setItem(eq(0), any()) }

        // The item meta display name must be set to the "no variables" label
        // mockMeta is the instance returned by anyConstructed<ItemStack>().itemMeta
        val capturedName = slot<String>()
        verify { mockMeta.setDisplayName(capture(capturedName)) }
        assertEquals("§7Нет переменных", capturedName.captured)
    }

    // -------------------------------------------------------------------------
    // Test 2: Delete from SAVED scope → persistence called
    // -------------------------------------------------------------------------

    @Test
    fun `handleClick on saved-scope variable calls savePlotVariables`() = runTest {
        val savedScope = VariableScopeImpl().also { it.set("myVar", "hello") }
        val plotScope = VariableScopeImpl()

        every { variableManager.getPlotScope(plotId) } returns plotScope
        coEvery { variableManager.getSavedScope(plotId) } returns savedScope
        coEvery { variableManager.savePlotVariables(plotId) } just Runs

        every { player.openInventory(any<Inventory>()) } returns mockk(relaxed = true)

        val gui = VariableExplorerGUI(plotId, variableManager, plugin, this)

        // Register the player at page 0 so handleClick knows the current page
        gui.open(player)

        // Click slot 0 — the only variable (from saved scope)
        gui.handleClick(0, player)

        // Allow launched coroutines to complete
        testScheduler.advanceUntilIdle()

        coVerify { variableManager.savePlotVariables(plotId) }
    }
}
