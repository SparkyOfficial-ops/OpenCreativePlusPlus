@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property 1: Smart GUI renders all parameters.
 * Validates: s 1.2
 *
 * For any non-empty map of String→Any params, buildParamItems returns a list
 * whose size equals the number of params (each param gets exactly one item entry).
 *
 * Uses the pure helper SmartGUI.buildParamItems to avoid Bukkit Inventory dependency.
 */
class SmartGUIPropertyTest : FreeSpec({

    "Property 1: Smart GUI renders all parameters" - {
        "for any non-empty param map, buildParamItems size equals params size" {
            // Validates: s 1.2
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.map(Arb.string(1..20), Arb.string(1..20), minSize = 1, maxSize = 10)
            ) { params ->
                val items = SmartGUI.buildParamItems(params)
                items.size shouldBe params.size
            }
        }
    }

    "Property 1b: buildParamItems preserves all param names as display names" - {
        "every param key appears as the first element of its pair" {
            // Validates: s 1.2
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.map(Arb.string(1..20), Arb.string(1..20), minSize = 1, maxSize = 10)
            ) { params ->
                val items = SmartGUI.buildParamItems(params)
                val itemKeys = items.map { it.first }.toSet()
                val paramKeys = params.keys
                itemKeys shouldBe paramKeys
            }
        }
    }

    "Property 1c: buildParamItems preserves all param values as lore values" - {
        "every param value appears as the second element of its pair" {
            // Validates: s 1.2
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.map(Arb.string(1..20), Arb.string(1..20), minSize = 1, maxSize = 10)
            ) { params ->
                val items = SmartGUI.buildParamItems(params)
                items.forEach { (key, value) ->
                    value shouldBe params[key].toString()
                }
            }
        }
    }

    "Property 1d: empty param map produces empty item list" - {
        "buildParamItems on empty map returns empty list" {
            val items = SmartGUI.buildParamItems(emptyMap())
            items.size shouldBe 0
        }
    }
})
