// Feature: ocp-gameplay-systems, Property 5: Variable Explorer delete
// Validates: Requirements 2.3
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property 5: Variable Explorer delete
 * Validates: Requirements 2.3
 *
 * For any variable map and any valid slot index, after simulating a delete of
 * the variable at that slot, the deleted key must NOT appear in the resulting
 * inventory model for that page.
 *
 * Tests the pure delete + render logic mirroring VariableExplorerGUI.handleClick()
 * and buildPagedInventory() without requiring Bukkit.
 */
class VariableExplorerDeletePropertyTest : FreeSpec({

    // -------------------------------------------------------------------------
    // Pure model types and helpers (mirror VariableExplorerGUI logic)
    // -------------------------------------------------------------------------

    data class SlotItem(val displayName: String, val lore: List<String>)

    /**
     * Build a list of up to 45 SlotItems for the given [page] from [allVars].
     * Mirrors VariableExplorerGUI.buildPagedInventory() — no Bukkit needed.
     */
    fun buildPagedInventoryModel(allVars: Map<String, Any?>, page: Int): List<SlotItem?> {
        if (allVars.isEmpty()) {
            return MutableList<SlotItem?>(9) { null }.also {
                it[0] = SlotItem("§7Нет переменных", emptyList())
            }
        }
        val pageEntries = allVars.entries.toList().drop(page * 45).take(45)
        return pageEntries.map { (name, value) ->
            SlotItem(
                displayName = "§e$name",
                lore = listOf(
                    "§7Значение: §f$value",
                    "§8Тип: §7${value?.javaClass?.simpleName ?: "null"}"
                )
            )
        }
    }

    /**
     * Simulate the delete operation from handleClick(slot, player):
     *   varIndex = page * 45 + slot
     *   key = allVars.entries.toList()[varIndex].key
     *   result = allVars - key
     * Returns the deleted key and the resulting map.
     */
    fun simulateDelete(allVars: Map<String, Any?>, page: Int, slot: Int): Pair<String, Map<String, Any?>> {
        val varIndex = page * 45 + slot
        val entries = allVars.entries.toList()
        val key = entries[varIndex].key
        val remaining = allVars.toMutableMap().also { it.remove(key) }
        return key to remaining
    }

    // -------------------------------------------------------------------------
    // Generators
    // -------------------------------------------------------------------------

    /** Single-page map: 1..45 entries */
    val arbSinglePageMap: Arb<Map<String, Any?>> =
        Arb.map(Arb.string(1..10), Arb.string(0..20), minSize = 1, maxSize = 45)

    /** Multi-page map: 46..200 entries */
    val arbMultiPageMap: Arb<Map<String, Any?>> =
        Arb.map(Arb.string(1..10), Arb.string(0..20), minSize = 46, maxSize = 200)

    // -------------------------------------------------------------------------
    // Property 5a: single-page — deleted key absent from rebuilt inventory
    // -------------------------------------------------------------------------

    "Property 5a: deleted variable is absent from the rebuilt single-page inventory (Req 2.3)" - {
        /**
         * Validates: Requirements 2.3
         *
         * Given a map of 1..45 variables (single page), pick any valid slot,
         * simulate delete, rebuild the inventory model, and assert the deleted
         * key does NOT appear in any slot's displayName.
         */
        "for any single-page variable map and any valid slot, deleted key is absent after delete" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbSinglePageMap,
                Arb.int(0..44)
            ) { allVars, rawSlot ->
                val slot = rawSlot % allVars.size   // clamp to valid range
                val (deletedKey, remaining) = simulateDelete(allVars, page = 0, slot = slot)

                val slots = buildPagedInventoryModel(remaining, page = 0)
                val displayNames = slots.filterNotNull().map { it.displayName }

                // The deleted key must NOT appear in any slot
                displayNames.none { it == "§e$deletedKey" } shouldBe true
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 5b: single-page — remaining variables are still present
    // -------------------------------------------------------------------------

    "Property 5b: all remaining variables are still present after delete (Req 2.3)" - {
        /**
         * Validates: Requirements 2.3
         *
         * After deleting one variable, every other variable must still appear
         * in the rebuilt inventory model.
         */
        "for any single-page variable map, remaining keys are all present after delete" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbSinglePageMap,
                Arb.int(0..44)
            ) { allVars, rawSlot ->
                val slot = rawSlot % allVars.size
                val (deletedKey, remaining) = simulateDelete(allVars, page = 0, slot = slot)

                val slots = buildPagedInventoryModel(remaining, page = 0)
                val displayNames = slots.filterNotNull().map { it.displayName }

                remaining.keys.forEach { key ->
                    displayNames.any { it == "§e$key" } shouldBe true
                }

                // Sanity: deleted key is not in remaining
                remaining.containsKey(deletedKey) shouldBe false
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 5c: single-page — inventory shrinks by exactly one slot
    // -------------------------------------------------------------------------

    "Property 5c: inventory shrinks by exactly one item after delete (Req 2.3)" - {
        /**
         * Validates: Requirements 2.3
         *
         * When the map has more than 1 entry, the number of non-null variable
         * slots in the rebuilt inventory must be exactly (original size - 1).
         * The single-entry edge case (delete → empty) is covered by Property 5f.
         */
        "for any single-page variable map with size > 1, slot count decreases by 1 after delete" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.map(Arb.string(1..10), Arb.string(0..20), minSize = 2, maxSize = 45),
                Arb.int(0..44)
            ) { allVars, rawSlot ->
                val slot = rawSlot % allVars.size
                val (_, remaining) = simulateDelete(allVars, page = 0, slot = slot)

                // Count only real variable slots (exclude the info item for empty state)
                val slotsBefore = buildPagedInventoryModel(allVars, page = 0)
                    .filterNotNull()
                    .filter { it.displayName != "§7Нет переменных" }
                val slotsAfter = buildPagedInventoryModel(remaining, page = 0)
                    .filterNotNull()
                    .filter { it.displayName != "§7Нет переменных" }

                slotsAfter.size shouldBe (slotsBefore.size - 1)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 5d: multi-page — deleted key absent from the same page after delete
    // -------------------------------------------------------------------------

    "Property 5d: deleted variable is absent from the same page in multi-page inventory (Req 2.3)" - {
        /**
         * Validates: Requirements 2.3
         *
         * Given a map with > 45 entries, pick a random page and a random slot
         * on that page, simulate delete of the variable at (page * 45 + slot),
         * and assert the deleted key is absent from the rebuilt inventory for
         * that page.
         */
        "for any multi-page variable map, deleted key is absent from the page after delete" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbMultiPageMap,
                Arb.int(0..10),
                Arb.int(0..44)
            ) { allVars, rawPage, rawSlot ->
                val totalPages = (allVars.size + 44) / 45
                val page = rawPage % totalPages
                val pageSize = allVars.entries.toList().drop(page * 45).take(45).size
                if (pageSize == 0) return@checkAll   // skip empty pages (shouldn't happen, but guard)

                val slot = rawSlot % pageSize
                val (deletedKey, remaining) = simulateDelete(allVars, page, slot)

                // Rebuild the same page from the remaining map
                val newTotalPages = if (remaining.isEmpty()) 1 else (remaining.size + 44) / 45
                val safePage = page.coerceAtMost(newTotalPages - 1)
                val slots = buildPagedInventoryModel(remaining, safePage)
                val displayNames = slots.filterNotNull().map { it.displayName }

                displayNames.none { it == "§e$deletedKey" } shouldBe true
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 5e: multi-page — deleted key absent from ALL pages after delete
    // -------------------------------------------------------------------------

    "Property 5e: deleted variable is absent from ALL pages after delete (Req 2.3)" - {
        /**
         * Validates: Requirements 2.3
         *
         * After deleting a variable, it must not appear on any page of the
         * rebuilt inventory model.
         */
        "for any multi-page variable map, deleted key is absent from every page after delete" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbMultiPageMap,
                Arb.int(0..10),
                Arb.int(0..44)
            ) { allVars, rawPage, rawSlot ->
                val totalPages = (allVars.size + 44) / 45
                val page = rawPage % totalPages
                val pageSize = allVars.entries.toList().drop(page * 45).take(45).size
                if (pageSize == 0) return@checkAll

                val slot = rawSlot % pageSize
                val (deletedKey, remaining) = simulateDelete(allVars, page, slot)

                val newTotalPages = if (remaining.isEmpty()) 1 else (remaining.size + 44) / 45
                val allDisplayNames = (0 until newTotalPages).flatMap { p ->
                    buildPagedInventoryModel(remaining, p).filterNotNull().map { it.displayName }
                }

                allDisplayNames.none { it == "§e$deletedKey" } shouldBe true
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 5f: delete on last variable produces empty-state inventory
    // -------------------------------------------------------------------------

    "Property 5f: deleting the only variable produces the empty-state inventory (Req 2.3)" - {
        /**
         * Validates: Requirements 2.3
         *
         * When the map has exactly one variable and it is deleted, the rebuilt
         * inventory must show the "no variables" info item.
         */
        "deleting the sole variable yields the empty-state info item" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.string(1..10),
                Arb.string(0..20)
            ) { key, value ->
                val allVars: Map<String, Any?> = mapOf(key to value)
                val (_, remaining) = simulateDelete(allVars, page = 0, slot = 0)

                remaining.isEmpty() shouldBe true

                val slots = buildPagedInventoryModel(remaining, page = 0)
                slots[0] shouldNotBe null
                slots[0]!!.displayName shouldBe "§7Нет переменных"
            }
        }
    }
})
