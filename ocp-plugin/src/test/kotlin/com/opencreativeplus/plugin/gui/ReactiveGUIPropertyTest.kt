@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import com.opencreativeplus.api.model.VariableChange
import com.opencreativeplus.api.model.VariableScopeType
import com.opencreativeplus.core.execution.VariableManager
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
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
import io.mockk.mockkStatic
import io.mockk.slot

/**
 * Property 16: ReactiveGUI update propagation
 * Validates: s 11.2, 11.3, 11.4
 *
 * s 11.2: WHEN a plot variable changes, THE OCP_Engine SHALL notify all open ReactiveGUI
 *         instances that declared a dependency on that variable.
 * s 11.3: WHEN a ReactiveGUI receives a change notification, THE OCP_Engine SHALL
 *         re-render the GUI contents and update the inventory of all viewers on the
 *         next server tick.
 * s 11.4: WHEN a player closes a ReactiveGUI, THE OCP_Engine SHALL unregister it from
 *         variable change notifications.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReactiveGUIPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Concrete minimal subclass of ReactiveGUI for testing.
     * Tracks how many times buildInventory() was called and which variables
     * were watched.
     */
    class TestReactiveGUI(
        plotId: UUID,
        variableManager: VariableManager,
        plugin: Plugin,
        scope: CoroutineScope,
        watchedVarNames: List<String> = emptyList(),
        private val inventorySupplier: () -> Inventory = { mockk(relaxed = true) }
    ) : ReactiveGUI(plotId, variableManager, plugin, scope) {

        var buildCount = 0
            private set

        init {
            watchedVarNames.forEach { watchVariable(it) }
        }

        override fun buildInventory(): Inventory {
            buildCount++
            return inventorySupplier()
        }
    }

    /** Set up Bukkit statics required by ReactiveGUI.scheduleUpdate / open. */
    fun mockBukkit(runTaskImmediately: Boolean = true): Pair<Player, Inventory> {
        val itemMeta = mockk<ItemMeta>(relaxed = true)
        val itemFactory = mockk<ItemFactory>(relaxed = true)
        every { itemFactory.getItemMeta(any()) } returns itemMeta

        mockkStatic(Bukkit::class)
        every { Bukkit.getItemFactory() } returns itemFactory

        val stubbedTask = mockk<BukkitTask>(relaxed = true)
        val scheduler = mockk<BukkitScheduler>(relaxed = true)
        every { Bukkit.getScheduler() } returns scheduler

        val runnableSlot = slot<Runnable>()
        every { scheduler.runTask(any<Plugin>(), capture(runnableSlot)) } answers {
            stubbedTask.also { if (runTaskImmediately) runnableSlot.captured.run() }
        }
        every { scheduler.runTaskLater(any<Plugin>(), capture(runnableSlot), any()) } answers {
            stubbedTask.also { if (runTaskImmediately) runnableSlot.captured.run() }
        }

        val player = mockk<Player>(relaxed = true)
        every { player.openInventory(any<Inventory>()) } returns mockk<InventoryView>(relaxed = true)
        return player to mockk(relaxed = true)
    }

    afterEach { unmockkAll() }

    // -----------------------------------------------------------------------
    // Property 16a: watched variable change triggers buildInventory for each viewer
    // -----------------------------------------------------------------------

    "Property 16a: watched variable change triggers re-render for all viewers (s 11.2, 11.3)" - {
        // Validates: Requirements 11.2, 11.3
        // For any list of watched variable names, emitting a change for one of those
        // variables must cause buildInventory() to be called at least once after open().
        "emitting a watched variable change causes buildInventory to be called" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.string(1..15), 1..5)
            ) { varNames ->
                val (player, _) = mockBukkit()
                val plotId = UUID.randomUUID()
                val changesFlow = MutableSharedFlow<VariableChange>(extraBufferCapacity = 16)
                val vm = mockk<VariableManager>()
                every { vm.changes(plotId) } returns changesFlow

                val dispatcher = UnconfinedTestDispatcher()
                val scope = CoroutineScope(dispatcher)
                val plugin = mockk<Plugin>(relaxed = true)

                val gui = TestReactiveGUI(plotId, vm, plugin, scope, varNames)
                gui.open(player)
                val buildCountAfterOpen = gui.buildCount

                // Emit a change for the first watched variable
                runTest(dispatcher) {
                    changesFlow.emit(
                        VariableChange(plotId, varNames.first(), "newValue", VariableScopeType.PLOT)
                    )
                }

                gui.buildCount shouldBe buildCountAfterOpen + 1
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 16b: unwatched variable change does NOT trigger re-render
    // -----------------------------------------------------------------------

    "Property 16b: unwatched variable change does NOT trigger re-render (s 11.2)" - {
        // Validates: Requirements 11.2
        // Only variables declared via watchVariable() should trigger updates.
        // Emitting a change for a variable not in the watched set must not cause
        // an additional buildInventory() call.
        "emitting an unwatched variable change does not increase buildCount" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.string(1..10), 1..5)
            ) { varNames ->
                val (player, _) = mockBukkit()
                val plotId = UUID.randomUUID()
                val changesFlow = MutableSharedFlow<VariableChange>(extraBufferCapacity = 16)
                val vm = mockk<VariableManager>()
                every { vm.changes(plotId) } returns changesFlow

                val dispatcher = UnconfinedTestDispatcher()
                val scope = CoroutineScope(dispatcher)
                val plugin = mockk<Plugin>(relaxed = true)

                val gui = TestReactiveGUI(plotId, vm, plugin, scope, varNames)
                gui.open(player)
                val buildCountAfterOpen = gui.buildCount

                // Emit a change for a variable that is definitely NOT watched
                val unwatchedName = "___unwatched___"
                runTest(dispatcher) {
                    changesFlow.emit(
                        VariableChange(plotId, unwatchedName, "value", VariableScopeType.PLOT)
                    )
                }

                gui.buildCount shouldBe buildCountAfterOpen
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 16c: all open viewers receive the updated inventory (s 11.3)
    // -----------------------------------------------------------------------

    "Property 16c: all open viewers have openInventory called on update (s 11.3)" - {
        // Validates: Requirements 11.3
        // When a watched variable changes, every player currently viewing the GUI
        // must have openInventory() called with the new inventory.
        "each viewer gets openInventory called after a watched variable change" {
            val plotId = UUID.randomUUID()
            val changesFlow = MutableSharedFlow<VariableChange>(extraBufferCapacity = 16)
            val vm = mockk<VariableManager>()
            every { vm.changes(plotId) } returns changesFlow

            val dispatcher = UnconfinedTestDispatcher()
            val scope = CoroutineScope(dispatcher)
            val plugin = mockk<Plugin>(relaxed = true)

            // Create 3 viewers
            val players = (1..3).map {
                val (p, _) = mockBukkit()
                p
            }

            val gui = TestReactiveGUI(plotId, vm, plugin, scope, listOf("score"))
            players.forEach { gui.open(it) }

            runTest(dispatcher) {
                changesFlow.emit(VariableChange(plotId, "score", 42, VariableScopeType.PLOT))
            }

            // Each player should have had openInventory called at least twice:
            // once on open(), once on the update
            players.forEach { p ->
                verify(atLeast = 2) { p.openInventory(any<Inventory>()) }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 16d: onClose unregisters player from updates (s 11.4)
    // -----------------------------------------------------------------------

    "Property 16d: after onClose, player no longer receives updates (s 11.4)" - {
        // Validates: Requirements 11.4
        // After onClose() is called for a player, subsequent variable changes must
        // NOT cause openInventory() to be called on that player again.
        "closed viewer does not receive further openInventory calls" {
            checkAll(
                PropTestConfig(iterations = 50),
                Arb.string(1..15)
            ) { varName ->
                val (player, _) = mockBukkit()
                val plotId = UUID.randomUUID()
                val changesFlow = MutableSharedFlow<VariableChange>(extraBufferCapacity = 16)
                val vm = mockk<VariableManager>()
                every { vm.changes(plotId) } returns changesFlow

                val dispatcher = UnconfinedTestDispatcher()
                val scope = CoroutineScope(dispatcher)
                val plugin = mockk<Plugin>(relaxed = true)

                val gui = TestReactiveGUI(plotId, vm, plugin, scope, listOf(varName))
                gui.open(player)

                // Close the GUI for this player
                gui.onClose(player)

                // After close, no further updates should reach this player

                // Emit a change — should NOT reach the closed player
                runTest(dispatcher) {
                    changesFlow.emit(
                        VariableChange(plotId, varName, "updated", VariableScopeType.PLOT)
                    )
                }

                // The subscription should have been cancelled (no viewers left),
                // so buildCount must not have increased beyond the initial open call
                // (1 call from open itself)
                gui.buildCount shouldBe 1
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 16e: subscription is cancelled when last viewer closes (s 11.4)
    // -----------------------------------------------------------------------

    "Property 16e: subscription is cancelled when last viewer closes (s 11.4)" - {
        // Validates: Requirements 11.4
        // When the last viewer closes the GUI, the Flow subscription must be
        // cancelled so no further updates are processed.
        "after last viewer closes, subsequent changes do not increment buildCount" {
            checkAll(
                PropTestConfig(iterations = 50),
                Arb.string(1..15)
            ) { varName ->
                val (player, _) = mockBukkit()
                val plotId = UUID.randomUUID()
                val changesFlow = MutableSharedFlow<VariableChange>(extraBufferCapacity = 16)
                val vm = mockk<VariableManager>()
                every { vm.changes(plotId) } returns changesFlow

                val dispatcher = UnconfinedTestDispatcher()
                val scope = CoroutineScope(dispatcher)
                val plugin = mockk<Plugin>(relaxed = true)

                val gui = TestReactiveGUI(plotId, vm, plugin, scope, listOf(varName))
                gui.open(player)
                gui.onClose(player) // last viewer leaves

                val buildCountAfterClose = gui.buildCount

                // Emit changes — subscription should be cancelled, no new builds
                runTest(dispatcher) {
                    repeat(3) {
                        changesFlow.emit(
                            VariableChange(plotId, varName, "val$it", VariableScopeType.PLOT)
                        )
                    }
                }

                gui.buildCount shouldBe buildCountAfterClose
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 16f: multiple watched variables all trigger updates (s 11.2)
    // -----------------------------------------------------------------------

    "Property 16f: each watched variable independently triggers re-render (s 11.2)" - {
        // Validates: Requirements 11.2
        // For any list of watched variable names, emitting a change for EACH of them
        // must each independently trigger a buildInventory() call.
        "N distinct watched variable changes produce N additional buildInventory calls" {
            checkAll(
                PropTestConfig(iterations = 50),
                Arb.list(Arb.string(1..10), 2..5)
            ) { varNames ->
                val distinctNames = varNames.distinct()
                if (distinctNames.size < 2) return@checkAll

                val (player, _) = mockBukkit()
                val plotId = UUID.randomUUID()
                val changesFlow = MutableSharedFlow<VariableChange>(extraBufferCapacity = 32)
                val vm = mockk<VariableManager>()
                every { vm.changes(plotId) } returns changesFlow

                val dispatcher = UnconfinedTestDispatcher()
                val scope = CoroutineScope(dispatcher)
                val plugin = mockk<Plugin>(relaxed = true)

                val gui = TestReactiveGUI(plotId, vm, plugin, scope, distinctNames)
                gui.open(player)
                val buildCountAfterOpen = gui.buildCount

                runTest(dispatcher) {
                    distinctNames.forEach { name ->
                        changesFlow.emit(
                            VariableChange(plotId, name, "v", VariableScopeType.PLOT)
                        )
                    }
                }

                gui.buildCount shouldBe buildCountAfterOpen + distinctNames.size
            }
        }
    }
})
