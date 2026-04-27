package com.opencreativeplus.plugin.gui

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotSettings
import com.opencreativeplus.core.database.PlotPersistence
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests for PlotTopGUI.
 * Verifies: click on a plot entry teleports the player to that plot (Req 8.4).
 */
class PlotTopGUITest {

    private lateinit var plotPersistence: PlotPersistence
    private lateinit var plotManager: PlotManagerImpl
    private lateinit var player: Player
    private lateinit var inventory: Inventory
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @BeforeEach
    fun setup() {
        plotPersistence = mockk(relaxed = true)
        plotManager = mockk(relaxed = true)
        player = mockk(relaxed = true)
        inventory = mockk(relaxed = true)

        every { player.uniqueId } returns UUID.randomUUID()
        every { player.isOnline } returns true

        mockkStatic("org.bukkit.Bukkit")
        every { Bukkit.createInventory(any(), any<Int>(), any<String>()) } returns inventory
        every { inventory.setItem(any(), any()) } just Runs
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makePlot(name: String = "TestPlot", rating: Int = 10): Plot = Plot(
        id = UUID.randomUUID(),
        owner = UUID.randomUUID(),
        name = name,
        description = "",
        mainWorldName = "world_main",
        devWorldName = "world_dev",
        createdAt = 0L,
        updatedAt = 0L,
        settings = PlotSettings(),
        metadata = PlotMetadata(rating = rating)
    )

    /**
     * Inject plots into the private openInventories map via reflection so the
     * click handler can resolve the plot for the clicked slot.
     */
    private fun injectOpenInventory(gui: PlotTopGUI, playerId: UUID, plots: List<Plot>) {
        val field = PlotTopGUI::class.java.getDeclaredField("openInventories")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(gui) as ConcurrentHashMap<UUID, List<Plot>>
        map[playerId] = plots
    }

    /**
     * Build a minimal InventoryClickEvent mock pointing at the given slot
     * inside the §8Plot Top inventory.
     */
    private fun makeClickEvent(slot: Int): InventoryClickEvent {
        val view = mockk<InventoryView>(relaxed = true)
        every { view.title } returns "§8Plot Top"

        val event = mockk<InventoryClickEvent>(relaxed = true)
        every { event.whoClicked } returns player
        every { event.view } returns view
        every { event.rawSlot } returns slot
        every { event.isCancelled = any() } just Runs

        return event
    }

    // -------------------------------------------------------------------------
    // Test: click → teleport is triggered (Req 8.4)
    // -------------------------------------------------------------------------

    @Test
    fun `clicking a plot slot triggers ensurePlotLoaded for that plot`() {
        val plot = makePlot(name = "ClickablePlot", rating = 42)
        val plots = listOf(plot)

        val mainWorld = mockk<org.bukkit.World>(relaxed = true)
        val spawnLocation = mockk<org.bukkit.Location>(relaxed = true)
        every { mainWorld.spawnLocation } returns spawnLocation

        // ensurePlotLoaded returns (mainWorld, devWorld)
        coEvery { plotManager.ensurePlotLoaded(plot.id) } returns Pair(mainWorld, mockk(relaxed = true))

        // Stub Bukkit.getScheduler().runTask so the teleport lambda executes immediately
        val scheduler = mockk<org.bukkit.scheduler.BukkitScheduler>(relaxed = true)
        every { Bukkit.getScheduler() } returns scheduler
        every { Bukkit.getPluginManager() } returns mockk(relaxed = true) {
            every { getPlugin("OpenCreativePlus") } returns mockk(relaxed = true)
        }
        every {
            scheduler.runTask(any<org.bukkit.plugin.Plugin>(), any<Runnable>())
        } answers {
            // Execute the runnable synchronously so teleport is called in the test
            secondArg<Runnable>().run()
            mockk(relaxed = true)
        }

        val gui = PlotTopGUI(plotPersistence, plotManager, scope)
        injectOpenInventory(gui, player.uniqueId, plots)

        val event = makeClickEvent(slot = 0)
        gui.onInventoryClick(event)

        // Verify the event was cancelled
        verify { event.isCancelled = true }

        // Verify teleport was attempted for the correct plot
        coVerify { plotManager.ensurePlotLoaded(plot.id) }

        // Verify the player was teleported to the world's spawn location
        verify { player.teleport(spawnLocation) }
    }

    @Test
    fun `clicking a slot with no associated plot does nothing`() {
        val gui = PlotTopGUI(plotPersistence, plotManager, scope)
        // openInventories is empty — no plots registered for this player

        val event = makeClickEvent(slot = 0)
        gui.onInventoryClick(event)

        // Event is still cancelled (GUI click protection)
        verify { event.isCancelled = true }

        // But no teleport attempt
        coVerify(exactly = 0) { plotManager.ensurePlotLoaded(any()) }
    }

    @Test
    fun `clicking outside the 27-slot range does not teleport`() {
        val plot = makePlot()
        val gui = PlotTopGUI(plotPersistence, plotManager, scope)
        injectOpenInventory(gui, player.uniqueId, listOf(plot))

        // Slot 27 is out of range (valid range is 0–26)
        val event = makeClickEvent(slot = 27)
        gui.onInventoryClick(event)

        coVerify(exactly = 0) { plotManager.ensurePlotLoaded(any()) }
    }

    @Test
    fun `clicking in a different inventory title does not trigger teleport`() {
        val plot = makePlot()
        val gui = PlotTopGUI(plotPersistence, plotManager, scope)
        injectOpenInventory(gui, player.uniqueId, listOf(plot))

        val view = mockk<InventoryView>(relaxed = true)
        every { view.title } returns "§8Some Other GUI"

        val event = mockk<InventoryClickEvent>(relaxed = true)
        every { event.whoClicked } returns player
        every { event.view } returns view

        gui.onInventoryClick(event)

        coVerify(exactly = 0) { plotManager.ensurePlotLoaded(any()) }
    }
}
