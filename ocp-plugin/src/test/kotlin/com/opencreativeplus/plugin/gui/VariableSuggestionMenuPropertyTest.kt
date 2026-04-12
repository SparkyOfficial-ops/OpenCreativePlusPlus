@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.core.execution.VariableScopeImpl
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemFactory
import org.bukkit.inventory.meta.ItemMeta
import java.util.UUID

/**
 * Property 4: Variable Suggestion Menu reflects DB state
 * Validates: s 2.3, 2.4
 *
 * s 2.3: WHEN the Variable_Suggestion_Menu opens, THE OCP_Engine SHALL load the variable
 *        list from MongoDB for the current plot.
 * s 2.4: THE Variable_Suggestion_Menu SHALL display each variable as an item with the
 *        variable name as item display name and its last known type as lore.
 *
 * Tests that [VariableSuggestionMenu.variables] always mirrors the contents of the
 * plot scope returned by [VariableManager.getPlotScope].
 */
class VariableSuggestionMenuPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    fun makeMenu(
        player: Player,
        plotId: UUID,
        variableManager: VariableManager
    ): VariableSuggestionMenu = VariableSuggestionMenu(
        player = player,
        plotId = plotId,
        variableManager = variableManager,
        onSelect = {}
    )

    /**
     * Set up all Bukkit statics needed by render():
     *  - Bukkit.createInventory → mock Inventory
     *  - Bukkit.getItemFactory  → mock ItemFactory that returns a mock ItemMeta
     */
    fun mockBukkit(): Pair<Player, Inventory> {
        val itemMeta = mockk<ItemMeta>(relaxed = true)
        val itemFactory = mockk<ItemFactory>(relaxed = true)
        every { itemFactory.getItemMeta(any()) } returns itemMeta
        every { itemFactory.isApplicable(any(), any<org.bukkit.inventory.ItemStack>()) } returns true

        val inv = mockk<Inventory>(relaxed = true)

        mockkStatic(Bukkit::class)
        @Suppress("DEPRECATION")
        every { Bukkit.createInventory(null, 54, any<String>()) } returns inv
        every { Bukkit.getItemFactory() } returns itemFactory

        val player = mockk<Player>(relaxed = true)
        every { player.openInventory(any<Inventory>()) } returns mockk<InventoryView>(relaxed = true)
        return player to inv
    }

    afterEach { unmockkAll() }

    // -----------------------------------------------------------------------
    // Property 4a: variables list size equals the number of entries in the scope
    // -----------------------------------------------------------------------

    "Property 4a: variables list size equals scope entry count (s 2.3)" - {
        // Validates: Requirements 2.3
        // For any map of variable names → values stored in the plot scope,
        // after open() the menu's variables list has the same size as the scope map.
        "for any scope map, variables.size equals the map size" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.map(Arb.string(1..20), Arb.string(1..20), minSize = 0, maxSize = 20)
            ) { varMap ->
                val (player, _) = mockBukkit()
                val plotId = UUID.randomUUID()
                val scope = VariableScopeImpl().also { s -> varMap.forEach { (k, v) -> s.set(k, v) } }
                val vm = mockk<VariableManager>()
                every { vm.getPlotScope(plotId) } returns scope

                val menu = makeMenu(player, plotId, vm)
                runBlocking { menu.open() }

                menu.variables.size shouldBe varMap.size
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 4b: every variable name in the menu matches a key in the scope
    // -----------------------------------------------------------------------

    "Property 4b: every variable entry name matches a scope key (s 2.3, 2.4)" - {
        // Validates: Requirements 2.3, 2.4
        // For any scope contents, each VariableEntry.name in the menu's variables list
        // must be a key that exists in the scope.
        "each entry name is a key present in the original scope map" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.map(Arb.string(1..20), Arb.string(1..20), minSize = 1, maxSize = 20)
            ) { varMap ->
                val (player, _) = mockBukkit()
                val plotId = UUID.randomUUID()
                val scope = VariableScopeImpl().also { s -> varMap.forEach { (k, v) -> s.set(k, v) } }
                val vm = mockk<VariableManager>()
                every { vm.getPlotScope(plotId) } returns scope

                val menu = makeMenu(player, plotId, vm)
                runBlocking { menu.open() }

                menu.variables.forEach { entry ->
                    varMap.containsKey(entry.name) shouldBe true
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 4c: lastKnownType reflects the runtime type of the scope value
    // -----------------------------------------------------------------------

    "Property 4c: lastKnownType reflects the value's class simpleName (s 2.4)" - {
        // Validates: Requirements 2.4
        // For String values stored in the scope, lastKnownType must equal "String".
        "String values produce lastKnownType == \"String\"" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.map(Arb.string(1..20), Arb.string(1..20), minSize = 1, maxSize = 20)
            ) { varMap ->
                val (player, _) = mockBukkit()
                val plotId = UUID.randomUUID()
                val scope = VariableScopeImpl().also { s -> varMap.forEach { (k, v) -> s.set(k, v) } }
                val vm = mockk<VariableManager>()
                every { vm.getPlotScope(plotId) } returns scope

                val menu = makeMenu(player, plotId, vm)
                runBlocking { menu.open() }

                menu.variables.forEach { entry ->
                    entry.lastKnownType shouldBe "String"
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 4d: variables list is sorted alphabetically by name
    // -----------------------------------------------------------------------

    "Property 4d: variables list is sorted alphabetically by name (s 2.4)" - {
        // Validates: Requirements 2.4
        // The menu sorts variables by name so the display is deterministic.
        "variables are in ascending alphabetical order regardless of insertion order" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.map(Arb.string(1..20), Arb.string(1..20), minSize = 2, maxSize = 20)
            ) { varMap ->
                val (player, _) = mockBukkit()
                val plotId = UUID.randomUUID()
                val scope = VariableScopeImpl().also { s -> varMap.forEach { (k, v) -> s.set(k, v) } }
                val vm = mockk<VariableManager>()
                every { vm.getPlotScope(plotId) } returns scope

                val menu = makeMenu(player, plotId, vm)
                runBlocking { menu.open() }

                menu.variables shouldBeSortedWith compareBy { it.name }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 4e: empty scope produces empty variables list
    // -----------------------------------------------------------------------

    "Property 4e: empty scope produces empty variables list (s 2.3, 2.6)" - {
        // Validates: Requirements 2.3
        // When the plot has no variables, the menu's variables list is empty.
        "empty scope yields variables.size == 0" {
            val (player, _) = mockBukkit()
            val plotId = UUID.randomUUID()
            val scope = VariableScopeImpl() // empty
            val vm = mockk<VariableManager>()
            every { vm.getPlotScope(plotId) } returns scope

            val menu = makeMenu(player, plotId, vm)
            runBlocking { menu.open() }

            menu.variables.size shouldBe 0
        }
    }

    // -----------------------------------------------------------------------
    // Property 4f: variables list contains no duplicates
    // -----------------------------------------------------------------------

    "Property 4f: variables list contains no duplicate names (s 2.3)" - {
        // Validates: Requirements 2.3
        // Since the scope is a map (unique keys), the resulting variables list
        // must also have unique names.
        "no two entries share the same name" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.map(Arb.string(1..20), Arb.string(1..20), minSize = 1, maxSize = 20)
            ) { varMap ->
                val (player, _) = mockBukkit()
                val plotId = UUID.randomUUID()
                val scope = VariableScopeImpl().also { s -> varMap.forEach { (k, v) -> s.set(k, v) } }
                val vm = mockk<VariableManager>()
                every { vm.getPlotScope(plotId) } returns scope

                val menu = makeMenu(player, plotId, vm)
                runBlocking { menu.open() }

                val names = menu.variables.map { it.name }
                names.size shouldBe names.distinct().size
            }
        }
    }
})
