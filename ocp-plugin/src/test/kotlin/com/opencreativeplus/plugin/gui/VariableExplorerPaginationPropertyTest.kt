// Feature: ocp-gameplay-systems, Property 6: Pagination invariant
// Validates: Requirements 2.6
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property 6: Pagination invariant
 * Validates: Requirements 2.6
 *
 * Tests the pure pagination logic mirroring VariableExplorerGUI.buildPagedInventory():
 *   - Slots 0–44: variable items for the current page (up to 45 per page)
 *   - Slot 45: ARROW ("← Назад") if page > 0, else AIR
 *   - Slot 53: ARROW ("Вперёд →") if page < totalPages - 1, else AIR
 *   - totalPages = (allVars.size + 44) / 45
 */
class VariableExplorerPaginationPropertyTest : FreeSpec({

    // Mirror the pagination logic from VariableExplorerGUI (no Bukkit needed)
    fun pageSlice(allVars: Map<String, Any?>, page: Int): List<Map.Entry<String, Any?>> =
        allVars.entries.toList().drop(page * 45).take(45)

    fun totalPages(n: Int): Int = (n + 44) / 45

    fun hasNextButton(page: Int, totalPages: Int): Boolean = page < totalPages - 1
    fun hasPrevButton(page: Int): Boolean = page > 0

    // Generator: map with > 45 entries (using Arb.map from kotest-property)
    val arbLargeVarMap: Arb<Map<String, Any?>> =
        Arb.map(Arb.string(1..10), Arb.string(0..20), minSize = 46, maxSize = 200)
            .map { m -> m as Map<String, Any?> }

    // -------------------------------------------------------------------------
    // Property 6a: page slice contains at most 45 items
    // -------------------------------------------------------------------------

    "Property 6a: page slice contains at most 45 items for any valid page (Req 2.6)" - {
        // Validates: Requirements 2.6
        "for any variable map with size > 45 and any valid page, slice.size <= 45" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbLargeVarMap,
                Arb.int(0..10)
            ) { allVars, rawPage ->
                val pages = totalPages(allVars.size)
                val page = rawPage % pages
                val slice = pageSlice(allVars, page)
                slice.size shouldBeLessThanOrEqualTo 45
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 6b: page 0 has a "next page" button when totalPages > 1
    // -------------------------------------------------------------------------

    "Property 6b: page 0 has a next-page button when totalPages > 1 (Req 2.6)" - {
        // Validates: Requirements 2.6
        "for any variable map with size > 45, hasNextButton(0, totalPages) is true" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbLargeVarMap
            ) { allVars ->
                val pages = totalPages(allVars.size)
                // size > 45 guarantees pages >= 2, so page 0 always has a next button
                hasNextButton(0, pages).shouldBeTrue()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 6c: last page does NOT have a "next page" button
    // -------------------------------------------------------------------------

    "Property 6c: last page does not have a next-page button (Req 2.6)" - {
        // Validates: Requirements 2.6
        "for any variable map with size > 45, hasNextButton(lastPage, totalPages) is false" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbLargeVarMap
            ) { allVars ->
                val pages = totalPages(allVars.size)
                val lastPage = pages - 1
                hasNextButton(lastPage, pages).shouldBeFalse()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 6d: first page (page=0) does NOT have a "previous page" button
    // -------------------------------------------------------------------------

    "Property 6d: first page does not have a previous-page button (Req 2.6)" - {
        // Validates: Requirements 2.6
        "for any variable map with size > 45, hasPrevButton(0) is false" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbLargeVarMap
            ) { allVars ->
                // Suppress unused warning — the property holds for any large map
                @Suppress("UNUSED_EXPRESSION") allVars
                hasPrevButton(0).shouldBeFalse()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 6e: all items across all pages cover the full list exactly once
    // -------------------------------------------------------------------------

    "Property 6e: all items across all pages cover the full list exactly once (Req 2.6)" - {
        // Validates: Requirements 2.6
        "union of all page slices equals the original variable list" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbLargeVarMap
            ) { allVars ->
                val pages = totalPages(allVars.size)
                val reconstructed = (0 until pages).flatMap { page -> pageSlice(allVars, page) }
                reconstructed shouldBe allVars.entries.toList()
            }
        }
    }
})
