@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-visual-programming-platform, Property 10: Остановка всех циклов при выходе из PLAY

package com.opencreativeplus.core.execution

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.bukkit.Server
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based test for CycleManager cycle stop behaviour.
 *
 * **Validates: Requirements 4.5**
 *
 * Property 10: For any plot with active cycles, after transitioning from PLAY mode
 * (i.e. calling [CycleManager.unregisterAll]), the CycleManager must not contain
 * any active cycles — all BukkitTasks must have been cancelled.
 */
class CycleStopPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a [CycleManager] backed by a fake scheduler.
     *
     * Returns a triple of:
     * - [CycleManager] under test
     * - [AtomicInteger] counting how many tasks were cancelled
     * - a factory that produces fresh [BukkitTask] mocks, each wired to increment
     *   the cancel counter when [BukkitTask.cancel] is called.
     */
    fun buildCycleManager(): Pair<CycleManager, AtomicInteger> {
        val cancelCount = AtomicInteger(0)

        val fakeScheduler = mockk<BukkitScheduler>(relaxed = true)
        every {
            fakeScheduler.runTaskTimer(any<Plugin>(), any<Runnable>(), any<Long>(), any<Long>())
        } answers {
            // Each call returns a fresh mock whose cancel() increments the counter
            mockk<BukkitTask>(relaxed = true).also { task ->
                every { task.cancel() } answers { cancelCount.incrementAndGet(); Unit }
            }
        }

        val fakeServer = mockk<Server>(relaxed = true)
        every { fakeServer.scheduler } returns fakeScheduler

        val fakePlugin = mockk<Plugin>(relaxed = true)
        every { fakePlugin.server } returns fakeServer

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val cycleManager = CycleManager(plugin = fakePlugin, scope = scope)

        return cycleManager to cancelCount
    }

    /**
     * Builds K distinct [CycleEntry] objects for the given [plotId],
     * each with a unique locationKey.
     */
    fun buildCycleEntries(k: Int, plotId: UUID): List<CycleEntry> =
        (0 until k).map { i ->
            CycleEntry(
                locationKey = "world:${i * 10}:64:${i * 10}",
                plotId = plotId,
                intervalTicks = 20L,
                execute = {}
            )
        }

    // -----------------------------------------------------------------------
    // Property 10: After unregisterAll(), all tasks are cancelled
    // -----------------------------------------------------------------------

    "Property 10: Остановка всех циклов при выходе из PLAY" - {

        /**
         * For any K in 1..20, after registering K cycles and calling unregisterAll(),
         * exactly K BukkitTask.cancel() calls must have been made.
         *
         * **Validates: Requirements 4.5**
         */
        "K active cycles → exactly K cancel() calls after unregisterAll()" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 20)
            ) { k ->
                val (cycleManager, cancelCount) = buildCycleManager()
                val plotId = UUID.randomUUID()
                val entries = buildCycleEntries(k, plotId)

                // Register all K cycles (simulates onPlotPlay)
                entries.forEach { entry -> cycleManager.register(entry) }

                // Transition out of PLAY mode (simulates onPlotStop)
                cycleManager.unregisterAll(plotId)

                // All K tasks must have been cancelled
                cancelCount.get() shouldBe k
            }
        }

        /**
         * After unregisterAll(), re-registering the same plot's entries and calling
         * unregisterAll() again must cancel exactly K tasks each time — the manager
         * must be in a clean state after each stop.
         *
         * **Validates: Requirements 4.5**
         */
        "unregisterAll() leaves manager in clean state — second PLAY/STOP cycle also cancels K tasks" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 15)
            ) { k ->
                val (cycleManager, cancelCount) = buildCycleManager()
                val plotId = UUID.randomUUID()
                val entries = buildCycleEntries(k, plotId)

                // First PLAY → STOP cycle
                entries.forEach { entry -> cycleManager.register(entry) }
                cycleManager.unregisterAll(plotId)
                val afterFirstStop = cancelCount.get()
                afterFirstStop shouldBe k

                // Second PLAY → STOP cycle
                entries.forEach { entry -> cycleManager.register(entry) }
                cycleManager.unregisterAll(plotId)
                // Total cancellations = 2 * k (k from first stop + k from second stop)
                cancelCount.get() shouldBe 2 * k
            }
        }

        /**
         * unregisterAll() for a specific plotId must NOT cancel tasks belonging to
         * other plots — only the target plot's tasks are stopped.
         *
         * **Validates: Requirements 4.5**
         */
        "unregisterAll() only cancels tasks for the target plot — other plots unaffected" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 10),
                Arb.int(1, 10)
            ) { k1, k2 ->
                val (cycleManager, cancelCount) = buildCycleManager()
                val plotId1 = UUID.randomUUID()
                val plotId2 = UUID.randomUUID()

                // Use offset to ensure unique location keys across plots
                val entries1 = buildCycleEntries(k1, plotId1)
                val entries2 = (0 until k2).map { i ->
                    CycleEntry(
                        locationKey = "world:${(k1 + i) * 100}:64:${(k1 + i) * 100}",
                        plotId = plotId2,
                        intervalTicks = 20L,
                        execute = {}
                    )
                }

                // Register both plots' cycles
                entries1.forEach { cycleManager.register(it) }
                entries2.forEach { cycleManager.register(it) }

                // Stop only plot1 — only k1 tasks should be cancelled
                cycleManager.unregisterAll(plotId1)
                cancelCount.get() shouldBe k1

                // Stop plot2 — remaining k2 tasks should now be cancelled
                cycleManager.unregisterAll(plotId2)
                cancelCount.get() shouldBe k1 + k2
            }
        }

        /**
         * Calling unregisterAll() on a plot with no registered cycles is a no-op —
         * zero cancel() calls are made.
         *
         * **Validates: Requirements 4.5**
         */
        "unregisterAll() on empty plot is a no-op — zero cancel() calls" {
            repeat(100) {
                val (cycleManager, cancelCount) = buildCycleManager()
                val plotId = UUID.randomUUID()

                // No cycles registered — simulates a plot that never entered PLAY
                cycleManager.unregisterAll(plotId)

                cancelCount.get() shouldBe 0
            }
        }
    }
})
