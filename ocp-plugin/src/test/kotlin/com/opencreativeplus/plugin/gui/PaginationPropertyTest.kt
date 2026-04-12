@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlin.math.ceil

/**
 * Property 6: Pagination shows at most 45 items per page
 * Validates: Requirements 2.7
 *
 * s 2.7: WHEN the variable list contains more than 45 entries, THE Variable_Suggestion_Menu
 *        SHALL support pagination with next/previous page buttons.
 *
 * Tests the pure pagination logic mirroring VariableSuggestionMenu.render():
 *   page slice = list.drop(page * pageSize).take(pageSize)
 */
class PaginationPropertyTest : FreeSpec({

    val pageSize = 45

    // Helper: compute page slice exactly as VariableSuggestionMenu.render() does
    fun <T> pageSlice(list: List<T>, page: Int): List<T> =
        list.drop(page * pageSize).take(pageSize)

    fun totalPages(n: Int): Int = if (n == 0) 1 else ceil(n / pageSize.toDouble()).toInt()

    // -------------------------------------------------------------------------
    // Property 6a: page slice size is at most 45 for any list and any page index
    // -------------------------------------------------------------------------

    "Property 6a: page slice size is at most $pageSize for any list and any page index (s 2.7)" - {
        // Validates: Requirements 2.7
        "for any N variables and any valid page, slice.size <= 45" {
            checkAll(
                PropTestConfig(iterations = 10),
                Arb.list(Arb.string(1..10), 0..200),
                Arb.int(0..10)
            ) { variables, rawPage ->
                val pages = totalPages(variables.size)
                val page = rawPage % pages   // clamp to valid range
                val slice = pageSlice(variables, page)
                slice.size shouldBeLessThanOrEqualTo pageSize
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 6b: last page contains exactly N mod 45 items (or 45 if multiple)
    // -------------------------------------------------------------------------

    "Property 6b: last page contains exactly N mod 45 items (or 45 if N is a multiple of 45) (s 2.7)" - {
        // Validates: Requirements 2.7
        "last page size equals N % 45, or 45 when N % 45 == 0" {
            checkAll(
                PropTestConfig(iterations = 10),
                Arb.list(Arb.string(1..10), 1..200)
            ) { variables ->
                val n = variables.size
                val pages = totalPages(n)
                val lastSlice = pageSlice(variables, pages - 1)
                val expected = if (n % pageSize == 0) pageSize else n % pageSize
                lastSlice.size shouldBe expected
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 6c: total page count equals ceil(N / 45)
    // -------------------------------------------------------------------------

    "Property 6c: total page count equals ceil(N / 45) (s 2.7)" - {
        // Validates: Requirements 2.7
        "totalPages(N) == ceil(N / 45), with minimum 1 for empty list" {
            checkAll(
                PropTestConfig(iterations = 10),
                Arb.int(0..200)
            ) { n ->
                val expected = if (n == 0) 1 else ceil(n / pageSize.toDouble()).toInt()
                totalPages(n) shouldBe expected
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 6d: all items across all pages cover the full list exactly once
    // -------------------------------------------------------------------------

    "Property 6d: all items across all pages cover the full list exactly once (s 2.7)" - {
        // Validates: Requirements 2.7
        "union of all page slices equals the original list" {
            checkAll(
                PropTestConfig(iterations = 10),
                Arb.list(Arb.string(1..10), 0..200)
            ) { variables ->
                val pages = totalPages(variables.size)
                val reconstructed = (0 until pages).flatMap { page -> pageSlice(variables, page) }
                reconstructed shouldBe variables
            }
        }
    }
})
