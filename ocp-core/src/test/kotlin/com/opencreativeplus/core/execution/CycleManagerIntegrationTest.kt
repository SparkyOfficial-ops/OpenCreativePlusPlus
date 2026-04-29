// Feature: ocp-visual-programming-platform, Integration Test: CycleManagerIntegrationTest

package com.opencreativeplus.core.execution

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.bukkit.Server
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * Integration tests for [CycleManager] covering the full PLAY → execution → STOP lifecycle.
 *
 * Since BukkitScheduler tasks cannot actually tick in tests, the [Runnable] passed to
 * [BukkitScheduler.runTaskTimer] is captured and invoked manually to simulate scheduler ticks.
 *
 * Requirements: 4.1, 4.2, 4.5
 */
class CycleManagerIntegrationTest {

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

    private lateinit var cycleManager: CycleManager
    private lateinit var cancelCount: AtomicInteger
    private val capturedRunnables = mutableListOf<Runnable>()

    @BeforeEach
    fun setup() {
        cancelCount = AtomicInteger(0)
        capturedRunnables.clear()

        val fakeScheduler = mockk<BukkitScheduler>(relaxed = true)
        every {
            fakeScheduler.runTaskTimer(any<Plugin>(), any<Runnable>(), any<Long>(), any<Long>())
        } answers {
            capturedRunnables.add(secondArg())
            mockk<BukkitTask>(relaxed = true).also { task ->
                every { task.cancel() } answers { cancelCount.incrementAndGet(); Unit }
            }
        }

        val fakeServer = mockk<Server>(relaxed = true)
        every { fakeServer.scheduler } returns fakeScheduler

        val fakePlugin = mockk<Plugin>(relaxed = true)
        every { fakePlugin.server } returns fakeServer

        // Unconfined dispatcher so coroutines run immediately in tests
        val scope = CoroutineScope(Dispatchers.Unconfined)
        cycleManager = CycleManager(plugin = fakePlugin, scope = scope)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildEntries(count: Int, plotId: UUID, locationOffset: Int = 0): List<CycleEntry> =
        (0 until count).map { i ->
            CycleEntry(
                locationKey = "world:${(locationOffset + i) * 10}:64:${(locationOffset + i) * 10}",
                plotId = plotId,
                intervalTicks = 20L,
                execute = {}
            )
        }

    // =========================================================================
    // Test 1: PLAY registers all cycle entries and STOP cancels them all
    // =========================================================================

    /**
     * Verifies that registering 3 cycle entries schedules 3 BukkitTasks, and that
     * [CycleManager.unregisterAll] cancels all 3 of them.
     *
     * Requirements: 4.1, 4.5
     */
    @Test
    fun `PLAY registers all cycle entries and STOP cancels them all`() {
        val plotId = UUID.randomUUID()
        val entries = buildEntries(3, plotId)

        // Simulate PLAY: register all 3 cycle entries
        entries.forEach { cycleManager.register(it) }

        // Verify 3 tasks were scheduled
        assertEquals(3, capturedRunnables.size, "Expected 3 BukkitTask registrations for 3 cycle entries")

        // Simulate STOP: unregister all cycles for this plot
        cycleManager.unregisterAll(plotId)

        // Verify all 3 tasks were cancelled
        assertEquals(3, cancelCount.get(), "Expected all 3 tasks to be cancelled after unregisterAll")
    }

    // =========================================================================
    // Test 2: cycle execute lambda is invoked when tick fires
    // =========================================================================

    /**
     * Verifies that manually invoking the captured Runnable (simulating a scheduler tick)
     * causes the cycle's execute lambda to be called exactly once.
     *
     * Requirements: 4.2
     */
    @Test
    fun `cycle execute lambda is invoked when tick fires`() = runBlocking {
        val plotId = UUID.randomUUID()
        val counter = AtomicInteger(0)

        val entry = CycleEntry(
            locationKey = "world:0:64:0",
            plotId = plotId,
            intervalTicks = 20L,
            execute = { counter.incrementAndGet() }
        )

        cycleManager.register(entry)
        assertEquals(1, capturedRunnables.size, "Expected exactly 1 Runnable to be captured")

        // Simulate one scheduler tick
        capturedRunnables[0].run()

        // Wait briefly for the coroutine to complete
        delay(50)

        assertEquals(1, counter.get(), "Execute lambda should have been called exactly once after one tick")
    }

    // =========================================================================
    // Test 3: multiple ticks invoke execute lambda multiple times
    // =========================================================================

    /**
     * Verifies that invoking the captured Runnable 3 times (simulating 3 scheduler ticks)
     * causes the execute lambda to be called 3 times.
     *
     * Requirements: 4.2
     */
    @Test
    fun `multiple ticks invoke execute lambda multiple times`() = runBlocking {
        val plotId = UUID.randomUUID()
        val counter = AtomicInteger(0)

        val entry = CycleEntry(
            locationKey = "world:0:64:0",
            plotId = plotId,
            intervalTicks = 20L,
            execute = { counter.incrementAndGet() }
        )

        cycleManager.register(entry)
        val runnable = capturedRunnables[0]

        // Simulate 3 scheduler ticks — each must complete before the next fires
        // (the running flag is reset in the finally block of the coroutine)
        runnable.run()
        delay(50)
        runnable.run()
        delay(50)
        runnable.run()
        delay(50)

        assertEquals(3, counter.get(), "Execute lambda should have been called 3 times after 3 ticks")
    }

    // =========================================================================
    // Test 4: skip-if-running — concurrent tick is skipped
    // =========================================================================

    /**
     * Verifies the skip-if-running guard: if the first tick's coroutine is still running
     * when the second tick fires, the second tick is skipped and execute is called only once.
     *
     * Requirements: 4.4
     */
    @Test
    fun `skip-if-running — concurrent tick is skipped`() = runBlocking {
        val plotId = UUID.randomUUID()
        val counter = AtomicInteger(0)

        // A deferred that the execute lambda awaits — lets us hold the first tick open
        val blocker = CompletableDeferred<Unit>()

        val entry = CycleEntry(
            locationKey = "world:0:64:0",
            plotId = plotId,
            intervalTicks = 20L,
            execute = {
                counter.incrementAndGet()
                blocker.await() // suspend until we release it
            }
        )

        cycleManager.register(entry)
        val runnable = capturedRunnables[0]

        // First tick: starts the coroutine, sets running = true
        runnable.run()

        // Give the coroutine a moment to start and set the running flag
        delay(20)

        // Second tick fires while first is still suspended — should be skipped
        runnable.run()

        // Release the first tick's suspension
        blocker.complete(Unit)

        // Wait for the first coroutine to finish
        delay(50)

        assertEquals(1, counter.get(), "Execute lambda should be called only once — second tick must be skipped")
    }

    // =========================================================================
    // Test 5: unregisterAll stops cycles for target plot only
    // =========================================================================

    /**
     * Verifies that [CycleManager.unregisterAll] cancels only the tasks belonging to
     * the specified plot, leaving other plots' tasks intact.
     *
     * Requirements: 4.5
     */
    @Test
    fun `unregisterAll stops cycles for target plot only`() {
        val plotId1 = UUID.randomUUID()
        val plotId2 = UUID.randomUUID()

        // 2 entries for plot1, 2 entries for plot2 (unique location keys via offset)
        val entries1 = buildEntries(2, plotId1, locationOffset = 0)
        val entries2 = buildEntries(2, plotId2, locationOffset = 100)

        entries1.forEach { cycleManager.register(it) }
        entries2.forEach { cycleManager.register(it) }

        assertEquals(4, capturedRunnables.size, "Expected 4 total BukkitTask registrations")

        // Stop only plot1
        cycleManager.unregisterAll(plotId1)
        assertEquals(2, cancelCount.get(), "Expected exactly 2 cancellations for plot1")

        // Stop plot2
        cycleManager.unregisterAll(plotId2)
        assertEquals(4, cancelCount.get(), "Expected 2 more cancellations for plot2 (4 total)")
    }

    // =========================================================================
    // Test 6: unregisterByLocation removes only that cycle
    // =========================================================================

    /**
     * Verifies that [CycleManager.unregisterByLocation] cancels only the task for the
     * specified location key, leaving the other entries for the same plot active.
     *
     * Requirements: 4.3
     */
    @Test
    fun `unregisterByLocation removes only that cycle`() {
        val plotId = UUID.randomUUID()
        val entries = buildEntries(3, plotId)

        entries.forEach { cycleManager.register(it) }
        assertEquals(3, capturedRunnables.size, "Expected 3 BukkitTask registrations")

        // Remove only the middle entry
        cycleManager.unregisterByLocation(entries[1].locationKey)
        assertEquals(1, cancelCount.get(), "Expected exactly 1 cancellation after unregisterByLocation")

        // Remove the remaining 2 entries for this plot
        cycleManager.unregisterAll(plotId)
        assertEquals(3, cancelCount.get(), "Expected 2 more cancellations (3 total) after unregisterAll")
    }
}
