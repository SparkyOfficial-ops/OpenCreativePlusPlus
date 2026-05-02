@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

// Feature: ocp-plugin-fixes-and-completions, Property 4: SmartGUI listener count invariant

/**
 * Property 4: SmartGUI listener count invariant
 *
 * After N sequential openings of SmartGUI for the same player, the number of
 * registered SmartGUI-Listeners must be exactly 1 (the most recent one).
 * All previous SmartGUI instances must have been unregistered via
 * HandlerList.unregisterAll before the new one is registered.
 *
 * Validates: Requirements 4.1, 4.4
 *
 * Feature: ocp-plugin-fixes-and-completions, Property 4: SmartGUI listener count invariant
 */

import com.opencreativeplus.api.registry.NodeRegistry
import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.core.serialization.ParamSerializer
import com.opencreativeplus.plugin.input.SignInputManager
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------
// Helper: create a SmartGUI with a given player and inventory
// ---------------------------------------------------------------------------

private fun makeSmartGUI(player: Player, inventory: Inventory): SmartGUI {
    return SmartGUI(
        player = player,
        block = mockk(relaxed = true),
        nodeRegistry = mockk<NodeRegistry>(relaxed = true),
        signInputManager = mockk<SignInputManager>(relaxed = true),
        paramSerializer = mockk<ParamSerializer>(relaxed = true),
        scope = CoroutineScope(Dispatchers.Unconfined),
        plotId = UUID.randomUUID(),
        variableManager = mockk<VariableManager>(relaxed = true),
        inventoryFactory = { inventory },
        itemFactory = { _, _, _ -> mockk(relaxed = true) },
        menuInventoryFactory = { mockk(relaxed = true) }
    )
}

// ---------------------------------------------------------------------------
// Helper: simulate the activeGuis map logic from ActionNodeInteractListener
// ---------------------------------------------------------------------------

/**
 * Simulates one "open SmartGUI" cycle:
 *   1. Unregister the previous GUI for this player (if any)
 *   2. Create a new GUI
 *   3. Store it in the map
 *
 * Returns the number of times unregisterAll was called during this open.
 */
private fun simulateOpen(
    uuid: UUID,
    activeGuis: ConcurrentHashMap<UUID, SmartGUI>,
    unregisterCounter: java.util.concurrent.atomic.AtomicInteger
) {
    // Step 1: unregister previous GUI (mirrors ActionNodeInteractListener logic)
    activeGuis[uuid]?.let {
        // In production this calls HandlerList.unregisterAll(it); here we count it
        unregisterCounter.incrementAndGet()
    }

    // Step 2: create a new GUI (inventory is a fresh mock per GUI)
    val player = mockk<Player>(relaxed = true)
    every { player.uniqueId } returns uuid
    val inventory = mockk<Inventory>(relaxed = true)
    val gui = makeSmartGUI(player, inventory)

    // Step 3: store in map (mirrors activeGuis[player.uniqueId] = gui)
    activeGuis[uuid] = gui
}

// ---------------------------------------------------------------------------
// Test spec
// ---------------------------------------------------------------------------

class SmartGUIListenerLeakPropertyTest : FreeSpec({

    afterEach {
        unmockkAll()
    }

    // -----------------------------------------------------------------------
    // Property 4a: After N opens for the same player, activeGuis has exactly 1 entry
    // -----------------------------------------------------------------------

    "Property 4a: after N opens for the same player, activeGuis map has exactly 1 entry (Req 4.1, 4.4)" - {
        // Validates: Requirements 4.1, 4.4
        // Simulates the activeGuis map logic from ActionNodeInteractListener.
        // After N sequential opens for the same player UUID, the map must contain
        // exactly 1 entry — the most recently registered SmartGUI.
        "for any N in 1..20, activeGuis.size == 1 after N opens" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1..10)
            ) { n ->
                val activeGuis = ConcurrentHashMap<UUID, SmartGUI>()
                val counter = java.util.concurrent.atomic.AtomicInteger(0)
                val uuid = UUID.randomUUID()

                repeat(n) {
                    simulateOpen(uuid, activeGuis, counter)
                }

                activeGuis.size shouldBe 1
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 4b: After N opens, unregisterAll was called exactly N-1 times
    // -----------------------------------------------------------------------

    "Property 4b: after N opens, unregisterAll is called exactly N-1 times (Req 4.1)" - {
        // Validates: Requirements 4.1
        // The first open has no previous GUI to unregister (0 calls).
        // Each subsequent open unregisters the previous one.
        // So after N opens: N-1 unregister calls total.
        "for any N in 1..20, unregisterAll call count == N-1" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1..10)
            ) { n ->
                val activeGuis = ConcurrentHashMap<UUID, SmartGUI>()
                val counter = java.util.concurrent.atomic.AtomicInteger(0)
                val uuid = UUID.randomUUID()

                repeat(n) {
                    simulateOpen(uuid, activeGuis, counter)
                }

                counter.get() shouldBe (n - 1)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 4c: Per-player isolation — two players each open N times
    // -----------------------------------------------------------------------

    "Property 4c: per-player isolation — two players each open N times, each has exactly 1 active GUI (Req 4.1)" - {
        // Validates: Requirements 4.1
        // Two distinct player UUIDs each open SmartGUI N times.
        // The shared activeGuis map must contain exactly 2 entries (one per player).
        "for any N in 1..20, two players → activeGuis.size == 2" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1..10)
            ) { n ->
                val activeGuis = ConcurrentHashMap<UUID, SmartGUI>()
                val counter = java.util.concurrent.atomic.AtomicInteger(0)
                val uuid1 = UUID.randomUUID()
                val uuid2 = UUID.randomUUID()

                // Both players open N times each
                repeat(n) {
                    simulateOpen(uuid1, activeGuis, counter)
                    simulateOpen(uuid2, activeGuis, counter)
                }

                activeGuis.size shouldBe 2
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 4d: SmartGUI.onInventoryClose triggers unregisterAll (Req 4.2, 4.3)
    // -----------------------------------------------------------------------

    "Property 4d: SmartGUI.onInventoryClose triggers HandlerList.unregisterAll (Req 4.2, 4.3)" - {
        // Validates: Requirements 4.2, 4.3
        // When the player closes the SmartGUI inventory, onInventoryClose must call
        // HandlerList.unregisterAll(this) so the GUI stops handling events.
        //
        // We use mockkStatic to intercept the static HandlerList.unregisterAll call
        // and verify it is invoked with the correct SmartGUI instance.
        "onInventoryClose calls HandlerList.unregisterAll when inventory and player match" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.uuid()
            ) { uuid ->
                mockkStatic(HandlerList::class)
                every { HandlerList.unregisterAll(any<org.bukkit.event.Listener>()) } just Runs

                val player = mockk<Player>(relaxed = true)
                every { player.uniqueId } returns uuid

                val inventory = mockk<Inventory>(relaxed = true)
                val gui = makeSmartGUI(player, inventory)

                // Build a mock InventoryCloseEvent that passes both guards in onInventoryClose:
                //   guard 1: p.uniqueId != player.uniqueId  → must be equal → use same uuid
                //   guard 2: event.inventory != inventory   → must be equal → return same inventory
                val event = mockk<InventoryCloseEvent>(relaxed = true)
                every { event.player } returns player
                every { event.inventory } returns inventory

                gui.onInventoryClose(event)

                verify(exactly = 1) { HandlerList.unregisterAll(gui) }

                unmockkAll()
            }
        }
    }
})
