@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import com.opencreativeplus.api.model.VariableChange
import com.opencreativeplus.api.model.VariableScopeType
import com.opencreativeplus.core.execution.VariableManager
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldBeGreaterThanOrEqualTo
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemFactory
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * Property 17: ReactiveGUI batching under load
 * Validates: s 11.5
 *
 * s 11.5: WHEN more than 10 players have the same ReactiveGUI open simultaneously,
 *         THE OCP_Engine SHALL batch GUI updates into a single tick to avoid
 *         performance spikes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReactiveGUIBatchPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    class TestReactiveGUI(
        plotId: UUID,
        variableManager: VariableManager,
        plugin: Plugin,
        scope: CoroutineScope,
        watchedVarNames: List<String> = listOf("score")
    ) : ReactiveGUI(plotId, variableManager, plugin, scope) {

        var buildCount = 0
            private set

        init {
            watchedVarNames.forEach { watchVariable(it) }
        }

        override fun buildInventory(): Inventory {
            buildCount++
            return mockk(relaxed = true)
        }
    }

    /**
     * Sets up Bukkit mocks.
     * @param runTaskLaterImmediately if true, the Runnable passed to runTaskLater is executed
     *   immediately (simulates tick passing). If false, it is captured but NOT run.
     * @param runTaskImmediately if true, the Runnable passed to runTask is executed immediately.
     */
    fun setupBukkit(
        runTaskLaterImmediately: Boolean,
        runTaskImmediately: Boolean = true
    ): Triple<BukkitScheduler, () -> Unit, () -> Unit> {
        val itemMeta = mockk<ItemMeta>(relaxed = true)
        val itemFactory = mockk<ItemFactory>(relaxed = true)
        every { itemFactory.getItemMeta(any()) } returns itemMeta

        mockkStatic(Bukkit::class)
        every { Bukkit.getItemFactory() } returns itemFactory

        val stubbedTask = mockk<BukkitTask>(relaxed = true)
        val scheduler = mockk<BukkitScheduler>(relaxed = true)
        every { Bukkit.getScheduler() } returns scheduler

        val laterSlot = slot<Runnable>()
        val immediateSlot = slot<Runnable>()

        every { scheduler.runTaskLater(any<Plugin>(), capture(laterSlot), any()) } answers {
            stubbedTask.also { if (runTaskLaterImmediately) laterSlot.captured.run() }
        }
        every { scheduler.runTask(any<Plugin>(), capture(immediateSlot)) } answers {
            stubbedTask.also { if (runTaskImmediately) immediateSlot.captured.run() }
        }

        // Expose a way to manually trigger the captured runnables
        val runLater = { if (laterSlot.isCaptured) laterSlot.captured.run() }
        val runImmediate = { if (immediateSlot.isCaptured) immediateSlot.captured.run() }

        return Triple(scheduler, runLater, runImmediate)
    }

    fun makePlayer(): Player {
        val player = mockk<Player>(relaxed = true)
        every { player.openInventory(any<Inventory>()) } returns mockk<InventoryView>(relaxed = true)
        return player
    }

    afterEach { unmockkAll() }

    // -----------------------------------------------------------------------
    // Property 17a: >10 viewers — N rapid changes produce exactly 1 runTaskLater call
    // -----------------------------------------------------------------------

    "Property 17a: with >10 viewers, N rapid variable changes schedule runTaskLater exactly once (s 11.5)" - {
        // Validates: Requirement 11.5
        // The AtomicBoolean debounce flag ensures that while a batched update is
        // pending, additional changes do not schedule additional runTaskLater calls.
        "multiple rapid changes with >10 viewers result in a single scheduled batch" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(11..20),  // viewerCount > 10
                Arb.int(2..8)     // numberOfChanges
            ) { viewerCount, numberOfChanges ->
                // Do NOT run the task immediately — we want to verify it's only scheduled once
                val (scheduler, _, _) = setupBukkit(runTaskLaterImmediately = false)

                val plotId = UUID.randomUUID()
                val changesFlow = MutableSharedFlow<VariableChange>(extraBufferCapacity = 32)
                val vm = mockk<VariableManager>()
                every { vm.changes(plotId) } returns changesFlow

                val dispatcher = UnconfinedTestDispatcher()
                val scope = CoroutineScope(dispatcher)
                val plugin = mockk<Plugin>(relaxed = true)

                val gui = TestReactiveGUI(plotId, vm, plugin, scope)

                // Add viewerCount players (all >10)
                repeat(viewerCount) { gui.open(makePlayer()) }

                // Emit numberOfChanges rapid changes for the watched variable
                runTest(dispatcher) {
                    repeat(numberOfChanges) { i ->
                        changesFlow.emit(
                            VariableChange(plotId, "score", i, VariableScopeType.PLOT)
                        )
                    }
                }

                // runTaskLater should have been called exactly once, not numberOfChanges times
                verify(exactly = 1) {
                    scheduler.runTaskLater(any<Plugin>(), any<Runnable>(), 1L)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 17b: ≤10 viewers — each change schedules an immediate runTask (no batching)
    // -----------------------------------------------------------------------

    "Property 17b: with ≤10 viewers, each variable change schedules runTask immediately (s 11.5)" - {
        // Validates: Requirement 11.5 (contrapositive)
        // When viewer count is ≤10, the batching path is NOT taken; each change
        // triggers an immediate runTask call.
        "each change with ≤10 viewers calls runTask once per change" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1..10),  // viewerCount ≤ 10
                Arb.int(1..5)    // numberOfChanges
            ) { viewerCount, numberOfChanges ->
                // Run tasks immediately so the debounce flag doesn't interfere
                val (scheduler, _, _) = setupBukkit(
                    runTaskLaterImmediately = false,
                    runTaskImmediately = true
                )

                val plotId = UUID.randomUUID()
                val changesFlow = MutableSharedFlow<VariableChange>(extraBufferCapacity = 32)
                val vm = mockk<VariableManager>()
                every { vm.changes(plotId) } returns changesFlow

                val dispatcher = UnconfinedTestDispatcher()
                val scope = CoroutineScope(dispatcher)
                val plugin = mockk<Plugin>(relaxed = true)

                val gui = TestReactiveGUI(plotId, vm, plugin, scope)

                repeat(viewerCount) { gui.open(makePlayer()) }

                runTest(dispatcher) {
                    repeat(numberOfChanges) { i ->
                        changesFlow.emit(
                            VariableChange(plotId, "score", i, VariableScopeType.PLOT)
                        )
                    }
                }

                // Each change should have triggered an immediate runTask (not runTaskLater)
                verify(exactly = numberOfChanges) {
                    scheduler.runTask(any<Plugin>(), any<Runnable>())
                }
                // runTaskLater should NOT have been called at all
                verify(exactly = 0) {
                    scheduler.runTaskLater(any<Plugin>(), any<Runnable>(), any())
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 17c: after batch completes, a new batch of changes is again batched once
    // -----------------------------------------------------------------------

    "Property 17c: after first batch runs, subsequent changes are again batched into one call (s 11.5)" - {
        // Validates: Requirement 11.5
        // Once the scheduled runTaskLater Runnable executes (resetting the debounce flag),
        // a new wave of changes must again be batched into exactly one new runTaskLater call.
        "second wave of changes after first batch completes schedules exactly one more runTaskLater" {
            checkAll(
                PropTestConfig(iterations = 50),
                Arb.int(11..20),  // viewerCount > 10
                Arb.int(2..5)     // changesPerWave
            ) { viewerCount, changesPerWave ->
                var laterCallCount = 0
                var capturedRunnable: Runnable? = null

                val itemMeta = mockk<ItemMeta>(relaxed = true)
                val itemFactory = mockk<ItemFactory>(relaxed = true)
                every { itemFactory.getItemMeta(any()) } returns itemMeta
                mockkStatic(Bukkit::class)
                every { Bukkit.getItemFactory() } returns itemFactory

                val stubbedTask = mockk<BukkitTask>(relaxed = true)
                val scheduler = mockk<BukkitScheduler>(relaxed = true)
                every { Bukkit.getScheduler() } returns scheduler

                // Capture but do NOT auto-run; we control execution manually
                every { scheduler.runTaskLater(any<Plugin>(), any<Runnable>(), any()) } answers {
                    laterCallCount++
                    capturedRunnable = secondArg<Runnable>()
                    stubbedTask
                }
                every { scheduler.runTask(any<Plugin>(), any<Runnable>()) } answers {
                    secondArg<Runnable>().run()
                    stubbedTask
                }

                val plotId = UUID.randomUUID()
                val changesFlow = MutableSharedFlow<VariableChange>(extraBufferCapacity = 64)
                val vm = mockk<VariableManager>()
                every { vm.changes(plotId) } returns changesFlow

                val dispatcher = UnconfinedTestDispatcher()
                val scope = CoroutineScope(dispatcher)
                val plugin = mockk<Plugin>(relaxed = true)

                val gui = TestReactiveGUI(plotId, vm, plugin, scope)
                repeat(viewerCount) { gui.open(makePlayer()) }

                // Wave 1: emit changesPerWave changes — should schedule exactly 1 runTaskLater
                runTest(dispatcher) {
                    repeat(changesPerWave) { i ->
                        changesFlow.emit(VariableChange(plotId, "score", i, VariableScopeType.PLOT))
                    }
                }
                laterCallCount shouldBe 1

                // Simulate the tick passing: run the captured runnable (resets debounce flag)
                capturedRunnable?.run()
                capturedRunnable = null

                // Wave 2: emit more changes — should schedule exactly 1 more runTaskLater
                runTest(dispatcher) {
                    repeat(changesPerWave) { i ->
                        changesFlow.emit(VariableChange(plotId, "score", i + 100, VariableScopeType.PLOT))
                    }
                }
                laterCallCount shouldBe 2
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 17d: batched update calls buildInventory exactly once per batch
    // -----------------------------------------------------------------------

    "Property 17d: batched update triggers buildInventory exactly once regardless of change count (s 11.5)" - {
        // Validates: Requirement 11.5
        // When >10 viewers are present and N changes arrive before the tick fires,
        // buildInventory() should be called exactly once when the batch runs
        // (not N times).
        "N changes with >10 viewers produce exactly 1 buildInventory call per batch" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(11..20),  // viewerCount > 10
                Arb.int(2..8)     // numberOfChanges
            ) { viewerCount, numberOfChanges ->
                var capturedRunnable: Runnable? = null

                val itemMeta = mockk<ItemMeta>(relaxed = true)
                val itemFactory = mockk<ItemFactory>(relaxed = true)
                every { itemFactory.getItemMeta(any()) } returns itemMeta
                mockkStatic(Bukkit::class)
                every { Bukkit.getItemFactory() } returns itemFactory

                val stubbedTask = mockk<BukkitTask>(relaxed = true)
                val scheduler = mockk<BukkitScheduler>(relaxed = true)
                every { Bukkit.getScheduler() } returns scheduler

                every { scheduler.runTaskLater(any<Plugin>(), any<Runnable>(), any()) } answers {
                    capturedRunnable = secondArg<Runnable>()
                    stubbedTask
                }
                every { scheduler.runTask(any<Plugin>(), any<Runnable>()) } answers {
                    secondArg<Runnable>().run()
                    stubbedTask
                }

                val plotId = UUID.randomUUID()
                val changesFlow = MutableSharedFlow<VariableChange>(extraBufferCapacity = 32)
                val vm = mockk<VariableManager>()
                every { vm.changes(plotId) } returns changesFlow

                val dispatcher = UnconfinedTestDispatcher()
                val scope = CoroutineScope(dispatcher)
                val plugin = mockk<Plugin>(relaxed = true)

                val gui = TestReactiveGUI(plotId, vm, plugin, scope)
                repeat(viewerCount) { gui.open(makePlayer()) }

                val buildCountAfterOpen = gui.buildCount

                // Emit N changes — all batched, task not yet run
                runTest(dispatcher) {
                    repeat(numberOfChanges) { i ->
                        changesFlow.emit(VariableChange(plotId, "score", i, VariableScopeType.PLOT))
                    }
                }

                // Before the batch runs, buildCount should not have increased
                gui.buildCount shouldBe buildCountAfterOpen

                // Now simulate the tick: run the batched task
                capturedRunnable?.run()

                // After the batch runs, buildInventory should have been called exactly once
                gui.buildCount shouldBe buildCountAfterOpen + 1
            }
        }
    }
})
