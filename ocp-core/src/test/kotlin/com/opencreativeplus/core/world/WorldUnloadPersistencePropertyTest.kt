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

class WorldUnloadPersistencePropertyTest : FreeSpec({

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

    "Property 14: Сохранение переменных перед выгрузкой мира" - {

        "updatePlot is called before unloadWorld for any plot" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1, 1)
            ) { _ ->
                val harness = buildHarness()
                val plot = makePlot()
                coEvery { harness.plotPersistence.loadPlot(plot.id) } returns plot
                try {
                    val playerId = harness.loadPlotViaCreate(plot)
                    harness.callOrder.clear()
                    harness.worldManager.onPlayerLeave(plot.id, playerId)
                    harness.capturedTimerRunnables.lastOrNull()?.run()

                    val updateIndex = harness.callOrder.indexOf("updatePlot")
                    val unloadIndex = harness.callOrder.indexOf("unloadWorld")
                    updateIndex shouldBe 0
                    unloadIndex shouldBe 1
                    (updateIndex < unloadIndex) shouldBe true
                } finally {
                    clearAllMocks()
                }
            }
        }

        "updatePlot is always called during world unload — never skipped" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1, 1)
            ) { _ ->
                val harness = buildHarness()
                val plot = makePlot()
                coEvery { harness.plotPersistence.loadPlot(plot.id) } returns plot
                try {
                    val playerId = harness.loadPlotViaCreate(plot)
                    harness.callOrder.clear()
                    harness.worldManager.onPlayerLeave(plot.id, playerId)
                    harness.capturedTimerRunnables.lastOrNull()?.run()
                    harness.callOrder.contains("updatePlot") shouldBe true
                } finally {
                    clearAllMocks()
                }
            }
        }

        "unloadWorld is always called after updatePlot during auto-unload" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1, 1)
            ) { _ ->
                val harness = buildHarness()
                val plot = makePlot()
                coEvery { harness.plotPersistence.loadPlot(plot.id) } returns plot
                try {
                    val playerId = harness.loadPlotViaCreate(plot)
                    harness.callOrder.clear()
                    harness.worldManager.onPlayerLeave(plot.id, playerId)
                    harness.capturedTimerRunnables.lastOrNull()?.run()
                    harness.callOrder.contains("unloadWorld") shouldBe true
                    val updateIndex = harness.callOrder.indexOf("updatePlot")
                    val unloadIndex = harness.callOrder.indexOf("unloadWorld")
                    (unloadIndex > updateIndex) shouldBe true
                } finally {
                    clearAllMocks()
                }
            }
        }

        "unload only fires after the last player leaves — not while others remain" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(2, 4)
            ) { playerCount ->
                val harness = buildHarness()
                val plot = makePlot()
                coEvery { harness.plotPersistence.loadPlot(plot.id) } returns plot
                try {
                    val playerIds = (1..playerCount).map { UUID.randomUUID() }
                    playerIds.forEach { harness.worldManager.onPlayerJoin(plot.id, it) }

                    val player = mockk<Player>(relaxed = true)
                    every { player.uniqueId } returns playerIds[0]
                    val successSlot = slot<(String) -> Unit>()
                    every { harness.worldOps.copyTemplate(any(), any(), capture(successSlot), any()) } answers {
                        successSlot.captured(plot.mainWorldName)
                    }
                    harness.worldManager.createPlot(player, plot, "template_void")
                    harness.callOrder.clear()
                    harness.capturedTimerRunnables.clear()

                    playerIds.dropLast(1).forEach { harness.worldManager.onPlayerLeave(plot.id, it) }
                    harness.worldManager.getState(plot.id) shouldBe WorldLifecycleState.LOADED

                    harness.worldManager.onPlayerLeave(plot.id, playerIds.last())
                    harness.capturedTimerRunnables.lastOrNull()?.run()

                    harness.callOrder.contains("updatePlot") shouldBe true
                    harness.callOrder.contains("unloadWorld") shouldBe true
                } finally {
                    clearAllMocks()
                }
            }
        }

        "player rejoining before timer fires cancels unload — no persistence or unload called" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1, 1)
            ) { _ ->
                val harness = buildHarness()
                val plot = makePlot()
                coEvery { harness.plotPersistence.loadPlot(plot.id) } returns plot
                try {
                    val playerId = harness.loadPlotViaCreate(plot)
                    harness.callOrder.clear()
                    harness.capturedTimerRunnables.clear()

                    harness.worldManager.onPlayerLeave(plot.id, playerId)
                    harness.worldManager.onPlayerJoin(plot.id, playerId)

                    harness.callOrder.contains("updatePlot") shouldBe false
                    harness.callOrder.contains("unloadWorld") shouldBe false
                } finally {
                    clearAllMocks()
                }
            }
        }

        "world is unloaded even when updatePlot throws an exception" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1, 1)
            ) { _ ->
                val harness = buildHarness()
                val plot = makePlot()
                coEvery { harness.plotPersistence.loadPlot(plot.id) } returns plot
                coEvery { harness.plotPersistence.updatePlot(any()) } coAnswers {
                    harness.callOrder.add("updatePlot")
                    throw RuntimeException("MongoDB unavailable")
                }
                try {
                    val playerId = harness.loadPlotViaCreate(plot)
                    harness.callOrder.clear()
                    harness.worldManager.onPlayerLeave(plot.id, playerId)
                    harness.capturedTimerRunnables.lastOrNull()?.run()
                    harness.callOrder.contains("updatePlot") shouldBe true
                    harness.callOrder.contains("unloadWorld") shouldBe true
                } finally {
                    clearAllMocks()
                }
            }
        }
    }
})
