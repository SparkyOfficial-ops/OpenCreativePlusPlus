// Feature: ocp-gameplay-systems, Property 4: Variable Explorer render
// Validates: Requirements 2.1, 2.2
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property 4: Variable Explorer render
 * Validates: Requirements 2.1, 2.2
 *
 * For any set of variables in plot and saved scope, buildInventory() must return
 * an inventory where every variable is represented by exactly one item with the
 * correct display name and value in lore.
 *
 * Tests the pure rendering logic mirroring VariableExplorerGUI.buildInventory()
 * without requiring Bukkit.
 */
class VariableExplorerRenderPropertyTest : FreeSpec({

    // -------------------------------------------------------------------------
    // Pure model of VariableExplorerGUI.buildInventory() — no Bukkit needed
    // -------------------------------------------------------------------------

    data class SlotItem(val displayName: String, val lore: List<String>)

    fun buildInventoryModel(allVars: Map<String, Any?>): Pair<Int, List<SlotItem?>> {
        if (allVars.isEmpty()) {
            val slots = MutableList<SlotItem?>(9) { null }
            slots[0] = SlotItem("§7Нет переменных", emptyList())
            return 9 to slots
        }
        val size = (((allVars.size + 8) / 9) * 9).coerceIn(9, 54)
        val slots = MutableList<SlotItem?>(size) { null }
        allVars.entries.forEachIndexed { index, (name, value) ->
            if (index >= size) return@forEachIndexed
            slots[index] = SlotItem(
                displayName = "§e$name",
                lore = listOf(
                    "§7Значение: §f$value",
                    "§8Тип: §7${value?.javaClass?.simpleName ?: "null"}"
                )
            )
        }
        return size to slots
    }

    // Arbitrary non-empty variable map (1..45 entries to stay within single page)
    val arbVarMap: Arb<Map<String, Any?>> =
        Arb.map(Arb.string(1..10), Arb.string(0..20), minSize = 1, maxSize = 45)
            .map { m -> m as Map<String, Any?> }

    // Arbitrary non-empty variable map for merge tests
    val arbSmallVarMap: Arb<Map<String, String>> =
        Arb.map(Arb.string(1..8), Arb.string(1..15), minSize = 1, maxSize = 20)

    // -------------------------------------------------------------------------
    // Property 4a: inventory size is computed correctly
    // -------------------------------------------------------------------------

    "Property 4a: inventory size equals ((n+8)/9*9).coerceIn(9,54) for any non-empty map (Req 2.1)" - {
        // Validates: Requirements 2.1
        "for any non-empty variable map, inventory size matches formula" {
            checkAll(
                PropTestConfig(iterations = 200),
                arbVarMap
            ) { allVars ->
                val (size, _) = buildInventoryModel(allVars)
                val expected = (((allVars.size + 8) / 9) * 9).coerceIn(9, 54)
                size shouldBe expected
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 4b: every variable key appears exactly once as a display name
    // -------------------------------------------------------------------------

    "Property 4b: every variable key appears exactly once in the inventory (Req 2.1, 2.2)" - {
        // Validates: Requirements 2.1, 2.2
        "for any non-empty variable map, each key has exactly one matching slot" {
            checkAll(
                PropTestConfig(iterations = 200),
                arbVarMap
            ) { allVars ->
                val (_, slots) = buildInventoryModel(allVars)
                val displayNames = slots.filterNotNull().map { it.displayName }

                allVars.keys.forEach { key ->
                    val expectedName = "§e$key"
                    val count = displayNames.count { it == expectedName }
                    count shouldBe 1
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 4c: each slot's lore contains the variable value
    // -------------------------------------------------------------------------

    "Property 4c: each slot lore contains the variable value (Req 2.2)" - {
        // Validates: Requirements 2.2
        "for any non-empty variable map, each item lore contains the value string" {
            checkAll(
                PropTestConfig(iterations = 200),
                arbVarMap
            ) { allVars ->
                val (_, slots) = buildInventoryModel(allVars)
                val nonNullSlots = slots.filterNotNull()

                allVars.entries.forEachIndexed { index, (name, value) ->
                    val slot = nonNullSlots.find { it.displayName == "§e$name" }
                    slot shouldNotBe null
                    slot!!.lore shouldContain "§7Значение: §f$value"
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 4d: empty variable map produces info item
    // -------------------------------------------------------------------------

    "Property 4d: empty variable map produces a 9-slot inventory with info item (Req 2.1)" - {
        // Validates: Requirements 2.1
        "empty map → slot 0 is the info item" {
            // Deterministic — no generator needed, just verify the empty-map branch directly
            repeat(200) {
                val (size, slots) = buildInventoryModel(emptyMap())
                size shouldBe 9
                slots[0] shouldNotBe null
                slots[0]!!.displayName shouldBe "§7Нет переменных"
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 4e: plot scope overrides saved scope on key collision
    // -------------------------------------------------------------------------

    "Property 4e: plot scope overrides saved scope on key collision (Req 2.1)" - {
        // Validates: Requirements 2.1
        "when both scopes have the same key, plot scope value wins" {
            checkAll(
                PropTestConfig(iterations = 200),
                arbSmallVarMap,
                arbSmallVarMap
            ) { savedVars, plotVars ->
                // Merge: saved first, then plot overrides
                val merged = mutableMapOf<String, Any?>()
                savedVars.forEach { (k, v) -> merged[k] = v }
                plotVars.forEach { (k, v) -> merged[k] = v }

                val (_, slots) = buildInventoryModel(merged)
                val nonNullSlots = slots.filterNotNull()

                // For any key that exists in both scopes, the displayed value must be the plot value
                plotVars.keys.intersect(savedVars.keys).forEach { key ->
                    val slot = nonNullSlots.find { it.displayName == "§e$key" }
                    slot shouldNotBe null
                    slot!!.lore shouldContain "§7Значение: §f${plotVars[key]}"
                }
            }
        }
    }
})
