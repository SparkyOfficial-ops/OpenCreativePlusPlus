@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.core.execution.VariableScopeImpl
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Property 5: Variable selection inserts reference
 * Validates: s 2.5
 *
 * s 2.5: WHEN a player clicks a variable in the Variable_Suggestion_Menu,
 *        THE OCP_Engine SHALL insert the variable reference into the parameter field
 *        and return to the Smart_GUI.
 *
 * Tests that clicking a slot in VariableSuggestionMenu always invokes onSelect
 * with exactly the variable name at that position, for any variable list and any
 * valid slot index.
 */
class VariableSelectionPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    fun mockPlayer(): Player {
        val player = mockk<Player>(relaxed = true)
        return player
    }

    fun makeMenu(
        player: Player,
        plotId: UUID,
        variableManager: VariableManager,
        onSelect: (String) -> Unit
    ): VariableSuggestionMenu = VariableSuggestionMenu(
        player = player,
        plotId = plotId,
        variableManager = variableManager,
        onSelect = onSelect,
        inventoryFactory = { _ -> mockk(relaxed = true) },
        itemFactory = { _, _, _ -> mockk(relaxed = true) }
    )

    afterEach { unmockkAll() }

    // -----------------------------------------------------------------------
    // Property 5a: clicking a valid slot calls onSelect with the correct name
    // -----------------------------------------------------------------------

    "Property 5a: clicking slot i calls onSelect with variables[i].name (s 2.5)" - {
        // Validates: Requirements 2.5
        // For any non-empty variable list, clicking slot i on page 0 must invoke
        // onSelect with exactly the name of the variable at index i.
        "onSelect receives the exact variable name for the clicked slot" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.map(Arb.string(1..20), Arb.string(1..20), minSize = 1, maxSize = 44)
            ) { varMap ->
                val player = mockPlayer()
                val plotId = UUID.randomUUID()
                val scope = VariableScopeImpl().also { s -> varMap.forEach { (k, v) -> s.set(k, v) } }
                val vm = mockk<VariableManager>()
                every { vm.getPlotScope(plotId) } returns scope

                var selected: String? = null
                val menu = makeMenu(player, plotId, vm) { selected = it }
                runBlocking { menu.open() }

                // Click the first slot — must yield the first (alphabetically sorted) variable
                menu.handleClick(0)

                selected shouldBe menu.variables[0].name
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 5b: clicking any valid slot index always yields the right name
    // -----------------------------------------------------------------------

    "Property 5b: clicking any valid slot index yields variables[slot].name (s 2.5)" - {
        // Validates: Requirements 2.5
        // For any variable list of size N (1..44) and any slot in 0 until N,
        // onSelect must be called with variables[slot].name.
        "onSelect(variables[slot].name) for every valid slot on page 0" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.map(Arb.string(1..20), Arb.string(1..20), minSize = 1, maxSize = 44),
                Arb.int(0..43)
            ) { varMap, rawSlot ->
                val player = mockPlayer()
                val plotId = UUID.randomUUID()
                val scope = VariableScopeImpl().also { s -> varMap.forEach { (k, v) -> s.set(k, v) } }
                val vm = mockk<VariableManager>()
                every { vm.getPlotScope(plotId) } returns scope

                var selected: String? = null
                val menu = makeMenu(player, plotId, vm) { selected = it }
                runBlocking { menu.open() }

                val slot = rawSlot % menu.variables.size  // clamp to valid range
                menu.handleClick(slot)

                selected shouldBe menu.variables[slot].name
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 5c: clicking an out-of-range slot does NOT invoke onSelect
    // -----------------------------------------------------------------------

    "Property 5c: clicking a slot beyond variables.size does not invoke onSelect (s 2.5)" - {
        // Validates: Requirements 2.5
        // If the slot index maps to an index >= variables.size, onSelect must not be called.
        "onSelect is never called for out-of-range slots" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.map(Arb.string(1..20), Arb.string(1..20), minSize = 1, maxSize = 10)
            ) { varMap ->
                val player = mockPlayer()
                val plotId = UUID.randomUUID()
                val scope = VariableScopeImpl().also { s -> varMap.forEach { (k, v) -> s.set(k, v) } }
                val vm = mockk<VariableManager>()
                every { vm.getPlotScope(plotId) } returns scope

                var selected: String? = null
                val menu = makeMenu(player, plotId, vm) { selected = it }
                runBlocking { menu.open() }

                // Slot beyond the loaded variables (but still in 0 until pageSize range)
                val outOfRangeSlot = menu.variables.size  // exactly one past the end
                if (outOfRangeSlot < 45) {
                    menu.handleClick(outOfRangeSlot)
                    selected shouldBe null
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 5d: selection on page N uses the correct offset
    // -----------------------------------------------------------------------

    "Property 5d: selection on page 1 uses offset pageSize (s 2.5)" - {
        // Validates: Requirements 2.5
        // When on page 1, clicking slot i must yield variables[pageSize + i].name,
        // confirming the page offset is applied correctly.
        "clicking slot i on page 1 yields variables[45 + i].name" {
            checkAll(
                PropTestConfig(iterations = 50),
                Arb.map(Arb.string(1..20), Arb.string(1..20), minSize = 46, maxSize = 90),
                Arb.int(0..44)
            ) { varMap, rawSlot ->
                val player = mockPlayer()
                val plotId = UUID.randomUUID()
                val scope = VariableScopeImpl().also { s -> varMap.forEach { (k, v) -> s.set(k, v) } }
                val vm = mockk<VariableManager>()
                every { vm.getPlotScope(plotId) } returns scope

                var selected: String? = null
                val menu = makeMenu(player, plotId, vm) { selected = it }
                runBlocking { menu.open() }

                // Navigate to page 1
                menu.handleClick(53)  // "Next →" slot

                val pageSize = 45
                val slot = rawSlot % (menu.variables.size - pageSize)  // clamp to page-1 range
                menu.handleClick(slot)

                selected shouldBe menu.variables[pageSize + slot].name
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 5e: onSelect is called exactly once per click
    // -----------------------------------------------------------------------

    "Property 5e: onSelect is called exactly once per valid click (s 2.5)" - {
        // Validates: Requirements 2.5
        // A single handleClick on a valid slot must invoke onSelect exactly once,
        // not zero times and not more than once.
        "onSelect invocation count is exactly 1 for a valid slot click" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.map(Arb.string(1..20), Arb.string(1..20), minSize = 1, maxSize = 44)
            ) { varMap ->
                val player = mockPlayer()
                val plotId = UUID.randomUUID()
                val scope = VariableScopeImpl().also { s -> varMap.forEach { (k, v) -> s.set(k, v) } }
                val vm = mockk<VariableManager>()
                every { vm.getPlotScope(plotId) } returns scope

                var callCount = 0
                val menu = makeMenu(player, plotId, vm) { callCount++ }
                runBlocking { menu.open() }

                menu.handleClick(0)

                callCount shouldBe 1
            }
        }
    }
})
