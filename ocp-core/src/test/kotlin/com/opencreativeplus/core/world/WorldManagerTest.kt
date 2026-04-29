// Feature: ocp-visual-programming-platform, Tasks 11.1, 11.2, 11.3

package com.opencreativeplus.core.world

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotSettings
import com.opencreativeplus.core.database.PlotPersistence
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class WorldManagerTest {

    private lateinit var worldManager: WorldManager
    private lateinit var plotPersistence: PlotPersistence
    private lateinit var worldOps: WorldOperations
    private lateinit var player: Player
    private lateinit var plugin: Plugin
    private lateinit var scheduler: BukkitScheduler
    private val capturedTimerRunnables = mutableListOf<Runnable>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun makePlot(id: UUID = UUID.randomUUID()): Plot = Plot(
        id = id,
        owner = UUID.randomUUID(),
        name = "Test Plot",
        description = "",
        mainWorldName = id.toString(),
        devWorldName = "${id}_dev",
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        settings = PlotSettings(biome = "PLAINS", timeOfDay = 6000L, pvpEnabled = false, mobSpawningEnabled = false, worldBorderSize = 1024),
        metadata = PlotMetadata()
    )

    @BeforeEach
    fun setup() {
        capturedTimerRunnables.clear()

        plotPersistence = mockk(relaxed = true)
        worldOps = mockk(relaxed = true)
        player = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        val server = mockk<Server>(relaxed = true)
        plugin = mockk(relaxed = true)

        every { plugin.server } returns server
        every { server.scheduler } returns scheduler
        every { scheduler.runTaskLater(any<Plugin>(), any<Runnable>(), any<Long>()) } answers {
            capturedTimerRunnables.add(secondArg())
            mockk<BukkitTask>(relaxed = true)
        }

        worldManager = WorldManager(
            plugin = plugin,
            plotPersistence = plotPersistence,
            worldOps = worldOps,
            scope = scope,
            emptyUnloadDelayMs = 300_000L
        )
    }

    // -------------------------------------------------------------------------
    // createPlot tests (Req 7.1, 7.2, 7.3, 7.4)
    // -------------------------------------------------------------------------

    @Test
    fun `createPlot with valid template calls copyTemplate and teleports player`() {
        val plot = makePlot()
        val successSlot = slot<(String) -> Unit>()

        every { worldOps.copyTemplate(any(), any(), capture(successSlot), any()) } answers {
            successSlot.captured("new_world_name")
        }

        worldManager.createPlot(player, plot, "template_void")

        verify { worldOps.copyTemplate(PlotTemplate.VOID, plot.id, any(), any()) }
        verify { worldOps.teleportToPlot(player, "new_world_name") }
        verify { player.sendMessage(match<String> { it.contains("Teleporting") || it.contains("created") }) }
    }

    @Test
    fun `createPlot with invalid template sends error listing available templates`() {
        val plot = makePlot()

        worldManager.createPlot(player, plot, "template_nonexistent")

        verify { player.sendMessage(match<String> { it.contains("template_void") && it.contains("template_flat") }) }
        verify(exactly = 0) { worldOps.copyTemplate(any(), any(), any(), any()) }
    }

    @Test
    fun `createPlot on copy error notifies player and logs severe`() {
        val plot = makePlot()
        val errorSlot = slot<(Exception) -> Unit>()

        every { worldOps.copyTemplate(any(), any(), any(), capture(errorSlot)) } answers {
            errorSlot.captured(RuntimeException("disk full"))
        }

        worldManager.createPlot(player, plot, "template_flat")

        verify { player.sendMessage(match<String> { it.contains("Failed") }) }
        assertEquals(WorldLifecycleState.UNLOADED, worldManager.getState(plot.id))
    }

    // -------------------------------------------------------------------------
    // Auto-unload tests (Req 7.5, 7.8)
    // -------------------------------------------------------------------------

    @Test
    fun `onPlayerLeave when last player leaves schedules unload timer`() {
        val plotId = UUID.randomUUID()
        val playerId = UUID.randomUUID()

        // Simulate player is on the plot
        worldManager.onPlayerJoin(plotId, playerId)
        capturedTimerRunnables.clear() // clear any timers from join

        worldManager.onPlayerLeave(plotId, playerId)

        verify { scheduler.runTaskLater(plugin, any<Runnable>(), any<Long>()) }
    }

    @Test
    fun `onPlayerLeave when other players remain does not schedule unload`() {
        val plotId = UUID.randomUUID()
        val player1 = UUID.randomUUID()
        val player2 = UUID.randomUUID()

        worldManager.onPlayerJoin(plotId, player1)
        worldManager.onPlayerJoin(plotId, player2)
        capturedTimerRunnables.clear()

        worldManager.onPlayerLeave(plotId, player1)

        // player2 still on plot — no unload timer
        verify(exactly = 0) { scheduler.runTaskLater(any<Plugin>(), any<Runnable>(), any<Long>()) }
    }

    @Test
    fun `onPlayerJoin cancels pending unload timer`() {
        val plotId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        val mockTask = mockk<BukkitTask>(relaxed = true)

        every { scheduler.runTaskLater(any<Plugin>(), any<Runnable>(), any<Long>()) } returns mockTask

        // Leave to schedule a timer
        worldManager.onPlayerLeave(plotId, playerId)
        // Join to cancel it
        worldManager.onPlayerJoin(plotId, playerId)

        verify { mockTask.cancel() }
    }

    @Test
    fun `unload timer fires and calls updatePlot before unloading world`() {
        val plot = makePlot()
        val playerId = UUID.randomUUID()
        every { player.uniqueId } returns playerId

        // Use Dispatchers.Unconfined so scope.launch runs the coroutine synchronously
        // on the calling thread when the Runnable fires
        val unconfinedScope = CoroutineScope(Dispatchers.Unconfined)
        val testWorldManager = WorldManager(
            plugin = plugin,
            plotPersistence = plotPersistence,
            worldOps = worldOps,
            scope = unconfinedScope,
            emptyUnloadDelayMs = 300_000L
        )

        // Mark world as loaded via createPlot — this adds player.uniqueId to plotPlayers
        val successSlot = slot<(String) -> Unit>()
        every { worldOps.copyTemplate(any(), any(), capture(successSlot), any()) } answers {
            successSlot.captured(plot.mainWorldName)
        }
        testWorldManager.createPlot(player, plot, "template_void")

        coEvery { plotPersistence.loadPlot(plot.id) } returns plot
        coEvery { plotPersistence.updatePlot(any()) } just Runs

        // Leave with the same playerId that was added during createPlot
        testWorldManager.onPlayerLeave(plot.id, playerId)

        // Fire the timer runnable manually — with Unconfined dispatcher, the coroutine
        // runs synchronously on this thread
        capturedTimerRunnables.lastOrNull()?.run()

        // Req 7.8: updatePlot must be called before unloadWorld
        coVerify { plotPersistence.updatePlot(any()) }
        verify { worldOps.unloadWorld(plot.id.toString()) }
    }

    // -------------------------------------------------------------------------
    // Async world loading tests (Req 7.6, 7.7)
    // -------------------------------------------------------------------------

    @Test
    fun `loadWorldForPlayer when world already loaded teleports immediately`() {
        val plot = makePlot()
        val playerId = UUID.randomUUID()
        every { player.uniqueId } returns playerId

        // Mark as loaded via createPlot
        val successSlot = slot<(String) -> Unit>()
        every { worldOps.copyTemplate(any(), any(), capture(successSlot), any()) } answers {
            successSlot.captured(plot.mainWorldName)
        }
        worldManager.createPlot(player, plot, "template_void")

        // Now load for another player
        val player2 = mockk<Player>(relaxed = true)
        every { player2.uniqueId } returns UUID.randomUUID()
        worldManager.loadWorldForPlayer(player2, plot)

        verify { worldOps.teleportToPlot(player2, plot.mainWorldName) }
        verify(exactly = 0) { worldOps.loadWorld(any(), any(), any()) }
    }

    @Test
    fun `loadWorldForPlayer when world unloaded calls loadWorld and teleports on success`() {
        val plot = makePlot()
        val playerId = UUID.randomUUID()
        every { player.uniqueId } returns playerId

        val successSlot = slot<() -> Unit>()
        every { worldOps.loadWorld(any(), capture(successSlot), any()) } answers {
            successSlot.captured()
        }

        worldManager.loadWorldForPlayer(player, plot)

        verify { worldOps.loadWorld(plot.mainWorldName, any(), any()) }
        verify { worldOps.teleportToPlot(player, plot.mainWorldName) }
        assertEquals(WorldLifecycleState.LOADED, worldManager.getState(plot.id))
    }

    @Test
    fun `loadWorldForPlayer on load failure notifies player`() {
        val plot = makePlot()
        every { player.uniqueId } returns UUID.randomUUID()

        val errorSlot = slot<(Exception) -> Unit>()
        every { worldOps.loadWorld(any(), any(), capture(errorSlot)) } answers {
            errorSlot.captured(RuntimeException("IO error"))
        }

        worldManager.loadWorldForPlayer(player, plot)

        verify { player.sendMessage(match<String> { it.contains("Failed") || it.contains("failed") }) }
        assertEquals(WorldLifecycleState.UNLOADED, worldManager.getState(plot.id))
    }
}
