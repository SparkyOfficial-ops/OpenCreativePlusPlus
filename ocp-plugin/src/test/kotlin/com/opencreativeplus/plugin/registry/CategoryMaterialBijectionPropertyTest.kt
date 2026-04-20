// Feature: category-based-coding-ui, Property 1: Category material bijection
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.registry

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Property-based test for Property 1: Category material bijection.
 *
 * Validates that the mapping from NodeCategory to Material is injective —
 * no two distinct categories share the same block material.
 *
 * Validates: Requirements 1.2
 */
class CategoryMaterialBijectionPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Property 1: Category material bijection
    // -----------------------------------------------------------------------

    "Property 1: Category material bijection" - {

        "all NodeCategory entries have distinct materials" {
            // Feature: category-based-coding-ui, Property 1: Category material bijection
            val materials = NodeCategory.entries.map { it.material }
            val distinctMaterials = materials.toSet()
            distinctMaterials.size shouldBe NodeCategory.entries.size
        }

        "for any two distinct categories, their materials differ" {
            // Feature: category-based-coding-ui, Property 1: Category material bijection
            val entries = NodeCategory.entries
            for (i in entries.indices) {
                for (j in entries.indices) {
                    if (i != j) {
                        entries[i].material shouldNotBe entries[j].material
                    }
                }
            }
        }

        "getCategoryForMaterial returns the correct category for each entry" {
            // Feature: category-based-coding-ui, Property 1: Category material bijection
            val registry = CategoryRegistry()
            for (category in NodeCategory.entries) {
                registry.getCategoryForMaterial(category.material) shouldBe category
            }
        }

        "allCategoryMaterials size equals number of NodeCategory entries" {
            // Feature: category-based-coding-ui, Property 1: Category material bijection
            val registry = CategoryRegistry()
            registry.allCategoryMaterials().size shouldBe NodeCategory.entries.size
        }
    }
})
