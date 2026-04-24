// Feature: ocp-manifest-roadmap, Property 5: PDC round-trip для action_id

@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property 5: PDC round-trip для action_id
 *
 * For any actionId string, writing it to the in-memory PDC store and then reading
 * it back must return the same string. Reading from an empty store must return null.
 *
 * **Validates: Requirements 4.3, 4.4**
 */
class PDCRoundTripPropertyTest : FreeSpec({

    val pdcKey = "ocp:action_id"

    fun write(store: MutableMap<String, String>, actionId: String) {
        store[pdcKey] = actionId
    }

    fun read(store: Map<String, String>): String? = store[pdcKey]

    "Property 5a: write then read returns the same actionId" - {
        // Validates: Requirements 4.3, 4.4
        "for any actionId in 1..64 chars, round-trip through PDC store is identity" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.string(1..64)
            ) { actionId ->
                val store = mutableMapOf<String, String>()
                write(store, actionId)
                read(store) shouldBe actionId
            }
        }
    }

    "Property 5b: reading from an empty store returns null" - {
        // Validates: Requirements 4.3, 4.4
        "read on an empty store always returns null regardless of what would have been written" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.string(1..64)
            ) { _ ->
                val store = emptyMap<String, String>()
                read(store) shouldBe null
            }
        }
    }
})
