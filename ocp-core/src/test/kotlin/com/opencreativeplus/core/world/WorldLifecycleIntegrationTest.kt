// Feature: ocp-visual-programming-platform, Integration Test: WorldLifecycleIntegrationTest

package com.opencreativeplus.core.world

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotSettings
import com.opencreativeplus.core.database.PlotPersistence
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [WorldManager] covering the full world lifecycle:
 * create → unload → load.
 *
 * Exercises the three lifecycle phases end-to-end using real [WorldManager] logic
 * with mocked Bukkit and persistence collaborators.
 *
 * Requirements: 7.2, 7.5, 7.6
 */
class WorldLifecycleIntegrationTest {

    private lateinit var worldManager: WorldManager
    private lateinit var plotPersistence: PlotPersistence
    private lateinit var worldOps: WorldOperations
    private lateinit var plugin: Plugin
    private lateinit var scheduler: BukkitScheduler
    private val capturedTimerRunnables = mutableListOf<Runnable>()
    private val callOrder = mutableListOf<String>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun makePlot(id: UUID = UUID.randomUUID()): Plot = Plot(
        id = id,
        owner = UUID.randomUUID(),
        name = "Integration Test Plot",
        description = "",
        mainWorldName = id.toString(),
        devWorldName = "${id}_dev",
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        settings = PlotSettings(biome = "PLAINS", timeOfDay = 6000L, pvpEnabled = false, mobSpawningEnabled = false, worldBorderSize = 1024),
        metadata = PlotMetadata()
    )

    private fun mockPlayer(): Player {
        val p = mockk<Player>(relaxed = true)
        every { p.uniqueId } returns UUID.randomUUID()
        return p
    }

    @BeforeEach
    fun setup() {
        capturedTimerRunnables.clear()
        callOrder.clear()

        plotPersistence = mockk(relaxed = true)
        worldOps = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        val server = mockk<Server>(relaxed = true)
        plugin = mockk(relaxed = true)

        every { plugin.server } returns server
        every { server.scheduler } returns scheduler
        every { scheduler.runTaskLater(any<Plugin>(), any<Runnable>(), any<Long>()) } answers {
            capturedTimerRunnables.add(secondArg())
            mockk<BukkitTask>(relaxed = true)
        }

        coEvery { plotPersistence.updatePlot(any()) } coAnswers { callOrder.add("updatePlot") }
        every { worldOps.unloadWorld(any()) } answers { callOrder.add("unloadWorld") }

        worldManager = WorldManager(
            plugin = plugin,
            plotPersistence = plotPersistence,
            worldOps = worldOps,
            scope = scope,
            emptyUnloadDelayMs = 300_000L
        )
    }

    /**
     * Full lifecycle round-trip: create → unload → load.
     *
     * 1. Create a plot from a template — world transitions to LOADED, player teleported (Req 7.2)
     * 2. Player leaves → timer fires → updatePlot called BEFORE unloadWorld → UNLOADED (Req 7.5, 7.8)
     * 3. New player joins unloaded world → loadWorld triggered → LOADED, player teleported (Req 7.6)
     *
     * Requirements: 7.2, 7.5, 7.6
     */
    @Test
    fun `full lifecycle - create then unload then load round-trip`() {
        val plot = makePlot()
        val originalPlayer = mockPlayer()
        val newPlayer = mockPlayer()

        // ---- Phase 1: Create (Req 7.2) ----
        val createSuccessSlot = slot<(String) -> Unit>()
        every { worldOps.copyTemplate(any(), any(), capture(createSuccessSlot), any()) } answers {
            createSuccessSlot.captured(plot.mainWorldName)
        }

        worldManager.createPlot(originalPlayer, plot, "template_void")

        assertEquals(WorldLifecycleState.LOADED, worldManager.getState(plot.id), "World must be LOADED after createPlot")
        assertEquals(1, worldManager.getPlayerCount(plot.id))
        verify { worldOps.teleportToPlot(originalPlayer, plot.mainWorldName) }

        callOrder.clear()
        capturedTimerRunnables.clear()

        // ---- Phase 2: Unload (Req 7.5, 7.8) ----
        coEvery { plotPersistence.loadPlot(plot.id) } returns plot

        worldManager.onPlayerLeave(plot.id, originalPlayer.uniqueId)

        assertEquals(1, capturedTimerRunnables.size, "Unload timer must be scheduled after last player leaves")
        assertEquals(WorldLifecycleState.LOADED, worldManager.getState(plot.id), "World must still be LOADED while timer is pending")

        // Fire the timer — Unconfined dispatcher runs the coroutine synchronously
        capturedTimerRunnables[0].run()

        val updateIdx = callOrder.indexOf("updatePlot")
        val unloadIdx = callOrder.indexOf("unloadWorld")
        assertTrue(updateIdx >= 0, "updatePlot must be called before unload")
        assertTrue(unloadIdx >= 0, "unloadWorld must be called")
        assertTrue(updateIdx < unloadIdx, "updatePlot must precede unloadWorld (Req 7.8)")

        assertEquals(WorldLifecycleState.UNLOADED, worldManager.getState(plot.id), "World must be UNLOADED after timer fires")
        assertEquals(0, worldManager.getPlayerCount(plot.id))

        callOrder.clear()

        // ---- Phase 3: Load (Req 7.6) ----
        val loadSuccessSlot = slot<() -> Unit>()
        every { worldOps.loadWorld(any(), capture(loadSuccessSlot), any()) } answers {
            callOrder.add("loadWorld")
            loadSuccessSlot.captured()
        }

        worldManager.loadWorldForPlayer(newPlayer, plot)

        assertTrue(callOrder.contains("loadWorld"), "loadWorld must be called when joining an unloaded world (Req 7.6)")
        assertEquals(WorldLifecycleState.LOADED, worldManager.getState(plot.id), "World must be LOADED after async load")
        verify { worldOps.teleportToPlot(newPlayer, plot.mainWorldName) }
        assertEquals(1, worldManager.getPlayerCount(plot.id))
    }

    /**
     * Verifies that the unload timer is cancelled when a player rejoins before it fires,
     * and neither persistence nor unload is triggered.
     *
     * Requirements: 7.5
     */
    @Test
    fun `unload is cancelled when player rejoins before timer fires`() {
        val plot = makePlot()
        val player = mockPlayer()
        val mockTask = mockk<BukkitTask>(relaxed = true)

        every { scheduler.runTaskLater(any<Plugin>(), any<Runnable>(), any<Long>()) } returns mockTask

        // Put world into LOADED state
        val successSlot = slot<(String) -> Unit>()
        every { worldOps.copyTemplate(any(), any(), capture(successSlot), any()) } answers {
            successSlot.captured(plot.mainWorldName)
        }
        worldManager.createPlot(player, plot, "template_void")
        callOrder.clear()

        // Player leaves — timer scheduled
        worldManager.onPlayerLeave(plot.id, player.uniqueId)

        // Player rejoins — timer must be cancelled
        worldManager.onPlayerJoin(plot.id, player.uniqueId)

        verify { mockTask.cancel() }
        assertTrue(callOrder.none { it == "updatePlot" }, "updatePlot must NOT be called when unload is cancelled")
        assertTrue(callOrder.none { it == "unloadWorld" }, "unloadWorld must NOT be called when unload is cancelled")
        assertEquals(WorldLifecycleState.LOADED, worldManager.getState(plot.id))
    }

    /**
     * Verifies that when a load fails, the player is notified and the world stays UNLOADED.
     *
     * Requirements: 7.7
     */
    @Test
    fun `load failure notifies player and leaves world UNLOADED`() {
        val plot = makePlot()
        val player = mockPlayer()

        val errorSlot = slot<(Exception) -> Unit>()
        every { worldOps.loadWorld(any(), any(), capture(errorSlot)) } answers {
            errorSlot.captured(RuntimeException("Disk I/O error"))
        }

        worldManager.loadWorldForPlayer(player, plot)

        verify { player.sendMessage(match<String> { it.contains("Failed") || it.contains("failed") }) }
        assertEquals(WorldLifecycleState.UNLOADED, worldManager.getState(plot.id))
        verify(exactly = 0) { worldOps.teleportToPlot(any(), any()) }
    }
}
