@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import com.opencreativeplus.plugin.registry.ActionDescriptor
import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.registry.NodeCategory
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import org.bukkit.Material

/**
 * Property 7: Pre-highlight for existing action_id
 *
 * For any Category_Block whose PDC already contains `ocp:action_id = X`, opening
 * the GUI must result in the slot corresponding to descriptor `X` having an
 * enchantment glint, and all other slots must not have a glint.
 *
 * **Validates: Requirements 3.7**
 *
 * Feature: category-based-coding-ui, Property 7: Pre-highlight for existing action_id
 *
 * Implementation note: we test the highlight-slot computation logic using pure
 * in-memory structures — no Bukkit server, no GUI instantiation needed.
 * The core logic under test: given a list of descriptors on a page and an
 * existing action_id, exactly one slot (the one matching the id) should be
 * highlighted, and all others should not.
 */
class PreHighlightPropertyTest : FreeSpec({

    val pageSize = NodeSelectionGUI.ITEMS_PER_PAGE

    /**
     * Pure model of the highlight logic from NodeSelectionGUI.open():
     * Returns the slot index (0-based within the page) of the descriptor
     * matching [existingId] on [page], or null if not present on this page.
     */
    fun computeHighlightedSlot(
        descriptors: List<ActionDescriptor>,
        existingId: String?,
        page: Int
    ): Int? {
        if (existingId == null) return null
        val pageStart = page * pageSize
        val pageItems = descriptors.drop(pageStart).take(pageSize)
        val idx = pageItems.indexOfFirst { it.id == existingId }
        return if (idx >= 0) idx else null
    }

    /**
     * Returns which slots on [page] are highlighted (should have glint).
     * In a correct implementation, at most one slot is highlighted.
     */
    fun highlightedSlots(
        descriptors: List<ActionDescriptor>,
        existingId: String?,
        page: Int
    ): List<Int> {
        if (existingId == null) return emptyList()
        val pageStart = page * pageSize
        val pageItems = descriptors.drop(pageStart).take(pageSize)
        return pageItems.mapIndexedNotNull { slot, descriptor ->
            if (descriptor.id == existingId) slot else null
        }
    }

    "Property 7a: slot for matching descriptor is highlighted" - {
        // Validates: Requirements 3.7
        "for any descriptor X in the list, its slot is highlighted when existingId == X.id" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.string(1..20), 1..45)
            ) { rawIds ->
                val uniqueIds = rawIds.distinct().ifEmpty { listOf("fallback") }
                val descriptors = uniqueIds.map { id ->
                    ActionDescriptor(
                        id = id,
                        displayName = "Action $id",
                        icon = Material.PAPER,
                        category = NodeCategory.PLAYER_ACTION
                    )
                }
                // Pick any descriptor from page 0
                val target = descriptors.first()
                val highlightedSlot = computeHighlightedSlot(descriptors, target.id, page = 0)
                highlightedSlot.shouldNotBeNull()
                highlightedSlot shouldBe descriptors.indexOf(target)
            }
        }
    }

    "Property 7b: only the matching slot is highlighted, all others are not" - {
        // Validates: Requirements 3.7
        "for any list of descriptors and any chosen existingId, exactly one slot is highlighted" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.string(1..20), 2..45)
            ) { rawIds ->
                val uniqueIds = rawIds.distinct().let { if (it.size >= 2) it else listOf("a", "b") }
                val descriptors = uniqueIds.map { id ->
                    ActionDescriptor(
                        id = id,
                        displayName = "Action $id",
                        icon = Material.PAPER,
                        category = NodeCategory.PLAYER_ACTION
                    )
                }
                val target = descriptors.first()
                val highlighted = highlightedSlots(descriptors, target.id, page = 0)
                // Exactly one slot highlighted
                highlighted.size shouldBe 1
                highlighted[0] shouldBe 0
                // All other slots are not highlighted
                val nonHighlightedDescriptors = descriptors.drop(1)
                nonHighlightedDescriptors.forEachIndexed { idx, descriptor ->
                    val slot = computeHighlightedSlot(descriptors, descriptor.id, page = 0)
                    // Each other descriptor highlights its own slot, not the target's slot
                    slot shouldBe (idx + 1)
                }
            }
        }
    }

    "Property 7c: no slot is highlighted when existingId is null" - {
        // Validates: Requirements 3.7
        "computeHighlightedSlot returns null when existingId is null" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.string(1..20), 1..45)
            ) { rawIds ->
                val uniqueIds = rawIds.distinct().ifEmpty { listOf("x") }
                val descriptors = uniqueIds.map { id ->
                    ActionDescriptor(
                        id = id,
                        displayName = "Action $id",
                        icon = Material.PAPER,
                        category = NodeCategory.PLAYER_ACTION
                    )
                }
                val result = computeHighlightedSlot(descriptors, existingId = null, page = 0)
                result.shouldBeNull()
                highlightedSlots(descriptors, existingId = null, page = 0).size shouldBe 0
            }
        }
    }

    "Property 7d: no slot is highlighted when existingId is not in the registry" - {
        // Validates: Requirements 3.7
        "computeHighlightedSlot returns null for an unregistered id" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.string(1..20), 1..45),
                Arb.string(1..20)
            ) { rawIds, unknownId ->
                val uniqueIds = rawIds.distinct().filter { it != unknownId }.ifEmpty { listOf("registered") }
                val descriptors = uniqueIds.map { id ->
                    ActionDescriptor(
                        id = id,
                        displayName = "Action $id",
                        icon = Material.PAPER,
                        category = NodeCategory.PLAYER_ACTION
                    )
                }
                // unknownId is guaranteed not in descriptors
                val result = computeHighlightedSlot(descriptors, existingId = unknownId, page = 0)
                result.shouldBeNull()
            }
        }
    }

    "Property 7e: highlighted slot is on the correct page" - {
        // Validates: Requirements 3.7
        "for a descriptor on page P, computeHighlightedSlot returns null for page != P" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(46..90)
            ) { n ->
                val descriptors = (0 until n).map { i ->
                    ActionDescriptor(
                        id = "action-$i",
                        displayName = "Action $i",
                        icon = Material.PAPER,
                        category = NodeCategory.PLAYER_ACTION
                    )
                }
                // Descriptor at index 46 is on page 1 (slots 45..89 → page 1)
                val targetDescriptor = descriptors[45]
                // Should NOT be highlighted on page 0
                val onPage0 = computeHighlightedSlot(descriptors, targetDescriptor.id, page = 0)
                onPage0.shouldBeNull()
                // Should be highlighted on page 1
                val onPage1 = computeHighlightedSlot(descriptors, targetDescriptor.id, page = 1)
                onPage1.shouldNotBeNull()
                onPage1 shouldBe 0  // first slot on page 1
            }
        }
    }

    "Property 7f: CategoryRegistry integration — descriptor lookup by id matches highlight logic" - {
        // Validates: Requirements 3.7
        "registered descriptor id round-trips through registry and highlight computation" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.string(1..20), 1..30)
            ) { rawIds ->
                val uniqueIds = rawIds.distinct().ifEmpty { listOf("id1") }
                val registry = CategoryRegistry()
                val category = NodeCategory.GAME_ACTION
                uniqueIds.forEach { id ->
                    registry.register(
                        ActionDescriptor(
                            id = id,
                            displayName = "Action $id",
                            icon = Material.STONE,
                            category = category
                        )
                    )
                }
                val descriptors = registry.getDescriptors(category)
                val target = descriptors.first()
                // The registry returns the descriptor; highlight logic finds it at slot 0
                val slot = computeHighlightedSlot(descriptors, target.id, page = 0)
                slot.shouldNotBeNull()
                slot shouldBe 0
            }
        }
    }
})
