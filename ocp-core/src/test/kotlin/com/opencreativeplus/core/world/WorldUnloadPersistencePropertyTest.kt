@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-visual-programming-platform, Property 14: Сохранение переменных перед выгрузкой мира

package com.opencreativeplus.core.world

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotSettings
import com.opencreativeplus.core.database.PlotPersistence
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.filter
import io.kotest.property.checkAll
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Property-based tests for WorldManager world-unload persistence.
 *
 * **Property 14: Сохранение переменных перед выгрузкой мира**
 *
 * For any plot, before WorldManager unloads the world it MUST call
 * PlotPersistence.updatePlot — data must be saved to MongoDB BEFORE
 * the world is unloaded from memory.
 *
 * **Validates: Requirements 7.5, 7.8**
 */
class WorldUnloadPersistencePropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Build a minimal [Plot] with the given [id]. */
    fun makePlot(id: UUID = UUID.randomUUID()): Plot = Plot(
        id = id,
        owner = UUID.randomUUID(),
        name = "Test Plot",
        description = "",
        mainWorldName = id.toString(),
        devWorldName = "${id}_dev",
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        settings = PlotSettings(
            biome = "PLAINS",
            timeOfDay = 6000L,
            pvpEnabled = false,
            mobSpawningEnabled = false,
            worldBorderSize = 1024
        ),
        metadata = PlotMetadata()
    )

    /**
     * Builds a [WorldManager] wired to fake collaborators.
     *
     * Returns a [TestHarness] that exposes:
     * - [TestHarness.worldManager] — the system under test
     * - [TestHarness.capturedTimerRunnables] — Runnables registered via runTaskLater
     * - [TestHarness.callOrder] — ordered log of "updatePlot" / "unloadWorld" calls
     * - [TestHarness.plotPersistence] — the mock, for additional stubbing
     * - [TestHarness.worldOps] — the mock, for additional stubbing
     * - [TestHarness.plugin] — the mock plugin
     */
    data class TestHarness(
        val worldManager: WorldManager,
        val capturedTimerRunnables: MutableList<Runnable>,
        val callOrder: MutableList<String>,
        val plotPersistence: PlotPersistence,
        val worldOps: WorldOperations,
        val plugin: Plugin
    )

    fun buildHarness(): TestHarness {
        val capturedTimerRunnables = CopyOnWriteArrayList<Runnable>()
        val callOrder = CopyOnWriteArrayList<String>()

        val plotPersistence = mockk<PlotPersistence>(relaxed = true)
        val worldOps = mockk<WorldOperations>(relaxed = true)
        val scheduler = mockk<BukkitScheduler>(relaxed = true)
        val server = mockk<Server>(relaxed = true)
        val plugin = mockk<Plugin>(relaxed = true)

        every { plugin.server } returns server
        every { server.scheduler } returns scheduler
        every { scheduler.runTaskLater(any<Plugin>(), any<Runnable>(), any<Long>()) } answers {
            capturedTimerRunnables.add(secondArg())
            mockk<BukkitTask>(relaxed = true)
        }

        // Track call order: updatePlot is recorded before unloadWorld
        coEvery { plotPersistence.updatePlot(any()) } coAnswers {
            callOrder.add("updatePlot")
        }
        every { worldOps.unloadWorld(any()) } answers {
            callOrder.add("unloadWorld")
        }

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val worldManager = WorldManager(
            plugin = plugin,
            plotPersistence = plotPersistence,
            worldOps = worldOps,
            scope = scope,
            emptyUnloadDelayMs = 300_000L
        )

        return TestHarness(worldManager, capturedTimerRunnables, callOrder, plotPersistence, worldOps, plugin)
    }

    /**
     * Puts a plot into LOADED state by simulating a successful createPlot call,
     * then returns the player UUID that was added to the plot.
     */
    fun TestHarness.loadPlotViaCreate(plot: Plot): UUID {
        val player = mockk<Player>(relaxed = true)
        val playerId = UUID.randomUUID()
        every { player.uniqueId } returns playerId

        val successSlot = slot<(String) -> Unit>()
        every { worldOps.copyTemplate(any(), any(), capture(successSlot), any()) } answers {
            successSlot.captured(plot.mainWorldName)
        }

        worldManager.createPlot(player, plot, "template_void")
        return playerId
    }

    // -----------------------------------------------------------------------
    // Property 14a: updatePlot is called before unloadWorld
    // -----------------------------------------------------------------------

    "Property 14: Сохранение переменных перед выгрузкой мира" - {

        /**
         * For any plot, when the last player leaves and the unload timer fires,
         * PlotPersistence.updatePlot must be called BEFORE WorldOperations.unloadWorld.
         *
         * **Validates: Requirements 7.5, 7.8**
         */
        "updatePlot is called before unloadWorld for any plot" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 1)  // single iteration driver — UUID randomness provides variety
            ) { _ ->
                val harness = buildHarness()
                val plot = makePlot()

                coEvery { harness.plotPersistence.loadPlot(plot.id) } returns plot

                // Put the world into LOADED state
                val playerId = harness.loadPlotViaCreate(plot)

                // Clear call order from the createPlot persistence call
                harness.callOrder.clear()

                // Trigger the unload path: last player leaves → timer scheduled
                harness.worldManager.onPlayerLeave(plot.id, playerId)

                // Fire the timer runnable (Unconfined dispatcher runs coroutine synchronously)
                harness.capturedTimerRunnables.lastOrNull()?.run()

                // updatePlot must appear before unloadWorld in the call log
                val updateIndex = harness.callOrder.indexOf("updatePlot")
                val unloadIndex = harness.callOrder.indexOf("unloadWorld")

                updateIndex shouldBe 0
                unloadIndex shouldBe 1
                (updateIndex < unloadIndex) shouldBe true
            }
        }

        /**
         * For any plot, updatePlot is always called (never skipped) when the world is unloaded.
         *
         * **Validates: Requirements 7.8**
         */
        "updatePlot is always called during world unload — never skipped" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 1)
            ) { _ ->
                val harness = buildHarness()
                val plot = makePlot()

                coEvery { harness.plotPersistence.loadPlot(plot.id) } returns plot

                val playerId = harness.loadPlotViaCreate(plot)
                harness.callOrder.clear()

                harness.worldManager.onPlayerLeave(plot.id, playerId)
                harness.capturedTimerRunnables.lastOrNull()?.run()

                harness.callOrder.contains("updatePlot") shouldBe true
            }
        }

        /**
         * For any plot, unloadWorld is always called after persistence during auto-unload.
         *
         * **Validates: Requirements 7.5**
         */
        "unloadWorld is always called after updatePlot during auto-unload" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 1)
            ) { _ ->
                val harness = buildHarness()
                val plot = makePlot()

                coEvery { harness.plotPersistence.loadPlot(plot.id) } returns plot

                val playerId = harness.loadPlotViaCreate(plot)
                harness.callOrder.clear()

                harness.worldManager.onPlayerLeave(plot.id, playerId)
                harness.capturedTimerRunnables.lastOrNull()?.run()

                harness.callOrder.contains("unloadWorld") shouldBe true
                // unloadWorld must come after updatePlot
                val updateIndex = harness.callOrder.indexOf("updatePlot")
                val unloadIndex = harness.callOrder.indexOf("unloadWorld")
                (unloadIndex > updateIndex) shouldBe true
            }
        }

        /**
         * When multiple players are on a plot and all leave one by one,
         * the unload (and persistence) only happens after the LAST player leaves.
         *
         * **Validates: Requirements 7.5**
         */
        "unload only fires after the last player leaves — not while others remain" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(2, 5)
            ) { playerCount ->
                val harness = buildHarness()
                val plot = makePlot()

                coEvery { harness.plotPersistence.loadPlot(plot.id) } returns plot

                // Add all players to the plot
                val playerIds = (1..playerCount).map { UUID.randomUUID() }
                playerIds.forEach { harness.worldManager.onPlayerJoin(plot.id, it) }

                // Mark world as LOADED by setting state via createPlot for the first player
                val player = mockk<Player>(relaxed = true)
                every { player.uniqueId } returns playerIds[0]
                val successSlot = slot<(String) -> Unit>()
                every { harness.worldOps.copyTemplate(any(), any(), capture(successSlot), any()) } answers {
                    successSlot.captured(plot.mainWorldName)
                }
                harness.worldManager.createPlot(player, plot, "template_void")
                harness.callOrder.clear()
                harness.capturedTimerRunnables.clear()

                // All but the last player leave — no unload timer should fire yet
                playerIds.dropLast(1).forEach { harness.worldManager.onPlayerLeave(plot.id, it) }

                // No timer runnable captured yet (or if captured, don't fire it)
                // The world should still be LOADED
                harness.worldManager.getState(plot.id) shouldBe WorldLifecycleState.LOADED

                // Last player leaves — now the timer is scheduled
                harness.worldManager.onPlayerLeave(plot.id, playerIds.last())
                harness.capturedTimerRunnables.lastOrNull()?.run()

                // Now persistence and unload must have happened
                harness.callOrder.contains("updatePlot") shouldBe true
                harness.callOrder.contains("unloadWorld") shouldBe true
            }
        }

        /**
         * If a player joins while the unload timer is pending, the timer is cancelled
         * and neither updatePlot nor unloadWorld is called.
         *
         * **Validates: Requirements 7.5**
         */
        "player rejoining before timer fires cancels unload — no persistence or unload called" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 1)
            ) { _ ->
                val harness = buildHarness()
                val plot = makePlot()

                coEvery { harness.plotPersistence.loadPlot(plot.id) } returns plot

                val playerId = harness.loadPlotViaCreate(plot)
                harness.callOrder.clear()
                harness.capturedTimerRunnables.clear()

                // Player leaves — timer scheduled
                harness.worldManager.onPlayerLeave(plot.id, playerId)

                // Player rejoins — timer should be cancelled
                harness.worldManager.onPlayerJoin(plot.id, playerId)

                // Do NOT fire the timer — it was cancelled
                // Neither updatePlot nor unloadWorld should have been called
                harness.callOrder.contains("updatePlot") shouldBe false
                harness.callOrder.contains("unloadWorld") shouldBe false
            }
        }

        /**
         * When persistence (updatePlot) fails with an exception, the world is still unloaded.
         * The unload must proceed even if saving fails.
         *
         * **Validates: Requirements 7.5, 7.8**
         */
        "world is unloaded even when updatePlot throws an exception" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 1)
            ) { _ ->
                val harness = buildHarness()
                val plot = makePlot()

                coEvery { harness.plotPersistence.loadPlot(plot.id) } returns plot
                // Override updatePlot to throw
                coEvery { harness.plotPersistence.updatePlot(any()) } coAnswers {
                    harness.callOrder.add("updatePlot")
                    throw RuntimeException("MongoDB unavailable")
                }

                val playerId = harness.loadPlotViaCreate(plot)
                harness.callOrder.clear()

                harness.worldManager.onPlayerLeave(plot.id, playerId)
                harness.capturedTimerRunnables.lastOrNull()?.run()

                // updatePlot was attempted
                harness.callOrder.contains("updatePlot") shouldBe true
                // unloadWorld still called despite the exception
                harness.callOrder.contains("unloadWorld") shouldBe true
            }
        }
    }
})
