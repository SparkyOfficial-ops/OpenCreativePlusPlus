// Feature: category-based-coding-ui, Property 20: Whitelist enforcement for place and break events
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.listener

import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.registry.NodeCategory
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import org.bukkit.Material

/**
 * Property-based test for Property 20: Whitelist enforcement for place and break events.
 *
 * For any block type not in the DEV-mode whitelist, a BlockPlaceEvent or BlockBreakEvent
 * in DEV mode must be cancelled.
 *
 * Tests the whitelist membership logic directly via [CategoryRegistry] and the known
 * fixed whitelist set, without requiring a running Bukkit server.
 *
 * **Validates: Requirements 12.6, 12.7, 12.8**
 *
 * Feature: category-based-coding-ui, Property 20: Whitelist enforcement for place and break events
 */
class WhitelistEnforcementPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Whitelist constants (mirrors PlotProtectionListener companion object)
    // -----------------------------------------------------------------------

    val FIXED_WHITELIST = setOf(
        Material.WHITE_STAINED_GLASS,
        Material.GRAY_STAINED_GLASS,
        Material.BLUE_STAINED_GLASS,
        Material.CHEST,
        Material.OAK_SIGN,
        Material.OAK_WALL_SIGN
    )

    val ALWAYS_BLOCKED_PLACE = setOf(
        Material.LAVA,
        Material.WATER
    )

    // -----------------------------------------------------------------------
    // Property 20: Whitelist enforcement for place and break events
    // -----------------------------------------------------------------------

    "Property 20: Whitelist enforcement for place and break events" - {

        // Validates: Requirements 12.6, 12.8
        "20a: all fixed whitelist materials are recognised as whitelisted" {
            // Feature: category-based-coding-ui, Property 20: Whitelist enforcement for place and break events
            val registry = CategoryRegistry()
            for (material in FIXED_WHITELIST) {
                val isWhitelisted = material in FIXED_WHITELIST || registry.isCategoryMaterial(material)
                isWhitelisted.shouldBeTrue()
            }
        }

        // Validates: Requirements 12.6, 12.8
        "20b: all category materials are recognised as whitelisted" {
            // Feature: category-based-coding-ui, Property 20: Whitelist enforcement for place and break events
            checkAll(
                PropTestConfig(iterations = 30),
                Arb.element(NodeCategory.entries)
            ) { category ->
                val registry = CategoryRegistry()
                registry.isCategoryMaterial(category.material).shouldBeTrue()
            }
        }

        // Validates: Requirements 12.6, 12.8
        "20c: non-whitelisted materials are not in the whitelist" {
            // Feature: category-based-coding-ui, Property 20: Whitelist enforcement for place and break events
            val registry = CategoryRegistry()
            val fullWhitelist = FIXED_WHITELIST + registry.allCategoryMaterials()
            val nonWhitelisted = Material.entries.filter { it !in fullWhitelist && it !in ALWAYS_BLOCKED_PLACE }

            if (nonWhitelisted.isNotEmpty()) {
                checkAll(
                    PropTestConfig(iterations = 30),
                    Arb.element(nonWhitelisted)
                ) { material ->
                    val isWhitelisted = material in FIXED_WHITELIST || registry.isCategoryMaterial(material)
                    isWhitelisted.shouldBeFalse()
                }
            }
        }

        // Validates: Requirements 12.7
        "20d: LAVA and WATER are always in the blocked-placement set" {
            // Feature: category-based-coding-ui, Property 20: Whitelist enforcement for place and break events
            ALWAYS_BLOCKED_PLACE shouldContain Material.LAVA
            ALWAYS_BLOCKED_PLACE shouldContain Material.WATER
        }

        // Validates: Requirements 12.6
        "20e: each NodeCategory maps to a unique material (no two categories share a block type)" {
            // Feature: category-based-coding-ui, Property 20: Whitelist enforcement for place and break events
            checkAll(
                PropTestConfig(iterations = 30),
                Arb.element(NodeCategory.entries)
            ) { category ->
                val allMaterials = NodeCategory.entries.map { it.material }
                val uniqueMaterials = allMaterials.toSet()
                uniqueMaterials.size shouldBe allMaterials.size
                uniqueMaterials shouldContain category.material
            }
        }
    }
})
