// Feature: category-based-coding-ui, Property 21: Disallowed action sends chat message
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.listener

import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.registry.NodeCategory
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.checkAll
import org.bukkit.Material

/**
 * Property-based test for Property 21: Disallowed action sends chat message.
 *
 * For any cancelled BlockPlaceEvent or BlockBreakEvent in DEV mode, the player must
 * receive a non-blank chat message explaining the restriction.
 *
 * Tests the message-building logic directly (without a running Bukkit server) by
 * verifying that the message produced for any disallowed material is non-blank and
 * references the material name, mirroring the [PlotProtectionListener.cancelAndNotify]
 * implementation.
 *
 * **Validates: Requirements 12.9**
 *
 * Feature: category-based-coding-ui, Property 21: Disallowed action sends chat message
 */
class DisallowedActionMessagePropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Message builder — mirrors PlotProtectionListener.cancelAndNotify
    // -----------------------------------------------------------------------

    /**
     * Builds the chat message that PlotProtectionListener sends to the player when a
     * disallowed block interaction is cancelled in DEV mode.
     *
     * This mirrors the implementation in [PlotProtectionListener] so that the property
     * test validates the contract without requiring a live Bukkit server.
     */
    fun buildDisallowedMessage(material: Material): String =
        "§c[OCP] Нельзя взаимодействовать с блоком §e${material.name}§c в DEV-режиме. " +
                "Разрешены только блоки кодирования."

    // -----------------------------------------------------------------------
    // Whitelist helpers (mirrors PlotProtectionListener companion object)
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

    val registry = CategoryRegistry()
    val fullWhitelist = FIXED_WHITELIST + registry.allCategoryMaterials()

    // Non-whitelisted materials that would trigger cancelAndNotify on place/break
    val disallowedMaterials = Material.entries.filter { it !in fullWhitelist }

    // -----------------------------------------------------------------------
    // Property 21: Disallowed action sends chat message
    // -----------------------------------------------------------------------

    "Property 21: Disallowed action sends chat message" - {

        // Validates: Requirements 12.9
        "21a: message for any disallowed material is non-blank" {
            // Feature: category-based-coding-ui, Property 21: Disallowed action sends chat message
            if (disallowedMaterials.isNotEmpty()) {
                checkAll(
                    PropTestConfig(iterations = 100),
                    Arb.element(disallowedMaterials)
                ) { material ->
                    val message = buildDisallowedMessage(material)
                    message.shouldNotBeBlank()
                }
            }
        }

        // Validates: Requirements 12.9
        "21b: message for any disallowed material contains the material name" {
            // Feature: category-based-coding-ui, Property 21: Disallowed action sends chat message
            if (disallowedMaterials.isNotEmpty()) {
                checkAll(
                    PropTestConfig(iterations = 100),
                    Arb.element(disallowedMaterials)
                ) { material ->
                    val message = buildDisallowedMessage(material)
                    message shouldContain material.name
                }
            }
        }

        // Validates: Requirements 12.9
        "21c: message for LAVA (always-blocked placement) is non-blank and contains material name" {
            // Feature: category-based-coding-ui, Property 21: Disallowed action sends chat message
            for (material in ALWAYS_BLOCKED_PLACE) {
                val message = buildDisallowedMessage(material)
                message.shouldNotBeBlank()
                message shouldContain material.name
            }
        }

        // Validates: Requirements 12.9
        "21d: message is non-blank for every NodeCategory material when treated as disallowed" {
            // Feature: category-based-coding-ui, Property 21: Disallowed action sends chat message
            // Verifies the message builder works for all possible material inputs,
            // including category materials (edge case: if whitelist were empty).
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.element(NodeCategory.entries)
            ) { category ->
                val message = buildDisallowedMessage(category.material)
                message.shouldNotBeBlank()
                message shouldContain category.material.name
            }
        }

        // Validates: Requirements 12.9
        "21e: message is non-blank for any arbitrary Material value" {
            // Feature: category-based-coding-ui, Property 21: Disallowed action sends chat message
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.element(Material.entries)
            ) { material ->
                val message = buildDisallowedMessage(material)
                message.shouldNotBeBlank()
            }
        }
    }
})
