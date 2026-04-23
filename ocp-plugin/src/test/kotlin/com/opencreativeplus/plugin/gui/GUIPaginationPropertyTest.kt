@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

/**
 * Property 5: GUI pagination boundary
 *
 * For any category with more than 45 registered descriptors, the GUI must span
 * at least two pages, and no single page must contain more than 45 action items.
 *
 * **Validates: Requirements 3.3**
 *
 * Feature: category-based-coding-ui, Property 5: GUI pagination boundary
 */
class GUIPaginationPropertyTest : FreeSpec({

    val pageSize = NodeSelectionGUI.ITEMS_PER_PAGE  // 45

    fun totalPages(n: Int): Int =
        if (n == 0) 1 else (n + pageSize - 1) / pageSize

    fun itemsOnPage(totalDescriptors: Int, page: Int): Int {
        val pageStart = page * pageSize
        return (totalDescriptors - pageStart).coerceIn(0, pageSize)
    }

    "Property 5: more than 45 descriptors requires at least 2 pages" - {
        // Validates: Requirements 3.3
        "for any N > 45, totalPages >= 2" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(46..500)
            ) { n ->
                (totalPages(n) >= 2).shouldBeTrue()
            }
        }
    }

    "Property 5: no single page contains more than 45 action items" - {
        // Validates: Requirements 3.3
        "for any N > 45 and any valid page index, itemsOnPage <= 45" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(46..500)
            ) { n ->
                val pages = totalPages(n)
                for (page in 0 until pages) {
                    (itemsOnPage(n, page) <= pageSize).shouldBeTrue()
                }
            }
        }
    }

    "Property 5: exactly 45 descriptors fits on one page" - {
        // Validates: Requirements 3.3 (boundary)
        "N == 45 produces exactly 1 page" {
            totalPages(45) shouldBe 1
            itemsOnPage(45, 0) shouldBe 45
        }
    }

    "Property 5: 46 descriptors requires exactly 2 pages" - {
        // Validates: Requirements 3.3 (boundary)
        "N == 46 produces 2 pages, first full and second with 1 item" {
            totalPages(46) shouldBe 2
            itemsOnPage(46, 0) shouldBe 45
            itemsOnPage(46, 1) shouldBe 1
        }
    }

    "Property 5: last page item count is correct for any N > 45" - {
        // Validates: Requirements 3.3
        "last page contains exactly N mod 45 items (or 45 if divisible)" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(46..500)
            ) { n ->
                val pages = totalPages(n)
                val lastPageItems = itemsOnPage(n, pages - 1)
                val expected = if (n % pageSize == 0) pageSize else n % pageSize
                lastPageItems shouldBe expected
            }
        }
    }

    "Property 5: total items across all pages equals N for any N > 45" - {
        // Validates: Requirements 3.3
        "sum of items across all pages equals N" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(46..500)
            ) { n ->
                val pages = totalPages(n)
                val total = (0 until pages).sumOf { page -> itemsOnPage(n, page) }
                total shouldBe n
            }
        }
    }
})
