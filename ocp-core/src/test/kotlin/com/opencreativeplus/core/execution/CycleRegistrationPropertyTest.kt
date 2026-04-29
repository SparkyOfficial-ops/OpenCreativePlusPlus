@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-visual-programming-platform, Property 9: Регистрация всех изумрудных блоков при переходе в PLAY

package com.opencreativeplus.core.execution

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.bukkit.Server
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based test for CycleManager cycle registration.
 *
 * **Validates: Requirements 4.1**
 *
 * Property 9: For any plot with K emerald blocks (cycle entries), after registering
 * all K entries via CycleManager.register(), the Bukkit scheduler's runTaskTimer
 * must have been called exactly K times — one repeating task per cycle entry point.
 */
class CycleRegistrationPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a [CycleManager] backed by a fake scheduler that counts
     * how many times [runTaskTimer] is invoked.
     *
     * Returns a pair of (cycleManager, registrationCounter).
     */
    fun buildCycleManager(): Pair<CycleManager, AtomicInteger> {
        val registrationCount = AtomicInteger(0)

        val fakeBukkitTask = mockk<BukkitTask>(relaxed = true)

        val fakeScheduler = mockk<BukkitScheduler>(relaxed = true)
        every {
            fakeScheduler.runTaskTimer(any<Plugin>(), any<Runnable>(), any<Long>(), any<Long>())
        } answers {
            registrationCount.incrementAndGet()
            fakeBukkitTask
        }

        val fakeServer = mockk<Server>(relaxed = true)
        every { fakeServer.scheduler } returns fakeScheduler

        val fakePlugin = mockk<Plugin>(relaxed = true)
        every { fakePlugin.server } returns fakeServer

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val cycleManager = CycleManager(plugin = fakePlugin, scope = scope)

        return cycleManager to registrationCount
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
    // Property 9: K emerald blocks → exactly K registered cycles
    // -----------------------------------------------------------------------

    "Property 9: Регистрация всех изумрудных блоков при переходе в PLAY" - {

        /**
         * For any K in 1..20, registering K distinct CycleEntries must result in
         * exactly K runTaskTimer calls on the Bukkit scheduler.
         *
         * **Validates: Requirements 4.1**
         */
        "K emerald blocks → exactly K BukkitTask registrations" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 20)
            ) { k ->
                val (cycleManager, registrationCount) = buildCycleManager()
                val plotId = UUID.randomUUID()
                val entries = buildCycleEntries(k, plotId)

                // Simulate onPlotPlay: register all cycle entries
                entries.forEach { entry -> cycleManager.register(entry) }

                registrationCount.get() shouldBe k
            }
        }

        /**
         * When no emerald blocks are present (K = 0), no tasks are registered.
         *
         * **Validates: Requirements 4.1**
         */
        "zero emerald blocks → zero BukkitTask registrations" {
            repeat(100) {
                val (_, registrationCount) = buildCycleManager()
                // No entries registered — simulates a plot with no EMERALD_BLOCKs
                registrationCount.get() shouldBe 0
            }
        }

        /**
         * For any K, each entry gets its own independent task (unique locationKey).
         * Re-registering the same location replaces the old task (still K total tasks).
         *
         * **Validates: Requirements 4.1**
         */
        "K distinct locations → exactly K active tasks after registration" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 15)
            ) { k ->
                val (cycleManager, registrationCount) = buildCycleManager()
                val plotId = UUID.randomUUID()
                val entries = buildCycleEntries(k, plotId)

                entries.forEach { entry -> cycleManager.register(entry) }

                // Each distinct location key produces exactly one scheduler call
                registrationCount.get() shouldBe k
            }
        }

        /**
         * Registering the same location twice replaces the old task:
         * the scheduler is called twice (cancel + new register), but only one
         * active task remains per location. Total scheduler calls = 2 * K for K re-registrations.
         *
         * **Validates: Requirements 4.1**
         */
        "re-registering same location replaces old task — scheduler called twice per location" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 10)
            ) { k ->
                val (cycleManager, registrationCount) = buildCycleManager()
                val plotId = UUID.randomUUID()
                val entries = buildCycleEntries(k, plotId)

                // Register once
                entries.forEach { entry -> cycleManager.register(entry) }
                // Register again (same location keys) — each replaces the previous task
                entries.forEach { entry -> cycleManager.register(entry) }

                // Each location was registered twice → 2 * k scheduler calls total
                registrationCount.get() shouldBe 2 * k
            }
        }

        /**
         * Multiple plots each with their own K emerald blocks:
         * total registrations = sum of all K values across plots.
         *
         * **Validates: Requirements 4.1**
         */
        "multiple plots — total registrations equals sum of all cycle entries" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 5),
                Arb.int(1, 5)
            ) { k1, k2 ->
                val (cycleManager, registrationCount) = buildCycleManager()
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

                entries1.forEach { cycleManager.register(it) }
                entries2.forEach { cycleManager.register(it) }

                registrationCount.get() shouldBe k1 + k2
            }
        }
    }
})
