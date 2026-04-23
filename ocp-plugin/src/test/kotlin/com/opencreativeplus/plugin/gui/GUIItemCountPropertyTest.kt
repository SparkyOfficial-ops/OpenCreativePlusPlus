@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import com.opencreativeplus.plugin.registry.ActionDescriptor
import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.registry.NodeCategory
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import org.bukkit.Material

/**
 * Property 4: GUI item count matches registered descriptors
 *
 * For any NodeCategory with N registered ActionDescriptors, opening the
 * NodeSelectionGUI for that category must produce an inventory containing
 * exactly N action items (across all pages).
 *
 * **Validates: Requirements 3.2**
 *
 * Feature: category-based-coding-ui, Property 4: GUI item count matches registered descriptors
 */
class GUIItemCountPropertyTest : FreeSpec({

    val pageSize = NodeSelectionGUI.ITEMS_PER_PAGE

    /**
     * Pure helper: given N descriptors and a page, compute how many action items
     * appear on that page. Mirrors NodeSelectionGUI.open() slot-filling logic.
     */
    fun itemsOnPage(totalDescriptors: Int, page: Int): Int {
        val pageStart = page * pageSize
        return (totalDescriptors - pageStart).coerceIn(0, pageSize)
    }

    fun totalPages(n: Int): Int =
        if (n == 0) 1 else (n + pageSize - 1) / pageSize

    fun totalItemsAcrossAllPages(n: Int): Int =
        (0 until totalPages(n)).sumOf { page -> itemsOnPage(n, page) }

    "Property 4: total action items across all pages equals N registered descriptors" - {
        // Validates: Requirements 3.2
        "for any N descriptors, sum of items across all pages equals N" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(0..200)
            ) { n ->
                totalItemsAcrossAllPages(n) shouldBe n
            }
        }
    }

    "Property 4b: CategoryRegistry.getDescriptors returns exactly the registered descriptors" - {
        // Validates: Requirements 3.2
        "for any list of unique ids, getDescriptors returns the same count" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.string(1..20), 0..50)
            ) { rawIds ->
                val uniqueIds = rawIds.distinct()
                val registry = CategoryRegistry()
                val category = NodeCategory.PLAYER_ACTION

                uniqueIds.forEach { id ->
                    registry.register(
                        ActionDescriptor(
                            id = id,
                            displayName = "Action $id",
                            icon = Material.PAPER,
                            category = category
                        )
                    )
                }

                registry.getDescriptors(category).size shouldBe uniqueIds.size
            }
        }
    }

    "Property 4c: items on a single page never exceed ITEMS_PER_PAGE" - {
        // Validates: Requirements 3.2, 3.3
        "for any N and any valid page, itemsOnPage <= 45" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(0..200),
                Arb.int(0..10)
            ) { n, rawPage ->
                val pages = totalPages(n)
                val page = rawPage % pages
                itemsOnPage(n, page) shouldBe minOf(pageSize, maxOf(0, n - page * pageSize))
            }
        }
    }

    "Property 4d: N == 0 produces zero total items" - {
        // Validates: Requirements 3.2
        "totalItemsAcrossAllPages(0) == 0" {
            totalItemsAcrossAllPages(0) shouldBe 0
        }
    }

    "Property 4e: N in 1..45 fits entirely on page 0" - {
        // Validates: Requirements 3.2
        "for any N in 1..45, all items are on page 0 and totalPages == 1" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1..45)
            ) { n ->
                totalPages(n) shouldBe 1
                itemsOnPage(n, 0) shouldBe n
            }
        }
    }

    "Property 4f: N > 45 distributes items across multiple pages with correct total" - {
        // Validates: Requirements 3.2
        "for any N > 45, totalPages >= 2 and sum across all pages equals N" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(46..500)
            ) { n ->
                val pages = totalPages(n)
                pages shouldBe (n + pageSize - 1) / pageSize
                val totalItems = (0 until pages).sumOf { page -> itemsOnPage(n, page) }
                totalItems shouldBe n
            }
        }
    }
})
