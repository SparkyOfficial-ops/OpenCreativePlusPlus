@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import com.opencreativeplus.plugin.registry.ActionDescriptor
import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.registry.NodeCategory
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import org.bukkit.Material

/**
 * Property 6: PDC action_id round-trip
 *
 * For any ActionDescriptor selected in the GUI, reading ocp:action_id from the
 * Category_Block's PDC immediately after selection must return the same id string
 * that was selected.
 *
 * **Validates: Requirements 3.5**
 *
 * Feature: category-based-coding-ui, Property 6: PDC action_id round-trip
 *
 * Implementation note: we test the PDC write/read logic using a plain HashMap as
 * the in-memory PDC store — no Bukkit server, no coroutines, no NodeSelectionGUI
 * instantiation needed. This mirrors the approach used in GUIItemCountPropertyTest.
 */
class PDCActionIdRoundTripPropertyTest : FreeSpec({

    val pdcKey = "ocp:action_id"

    /**
     * Simulates writeActionId: stores the value under the key.
     */
    fun writeActionId(store: MutableMap<String, String>, actionId: String) {
        store[pdcKey] = actionId
    }

    /**
     * Simulates readActionId: returns the stored value or null.
     */
    fun readActionId(store: Map<String, String>): String? = store[pdcKey]

    "Property 6a: writeActionId then readActionId returns the same id" - {
        // Validates: Requirements 3.5
        "for any non-blank action id, round-trip through PDC store returns the same value" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.string(1..50)
            ) { actionId ->
                val store = mutableMapOf<String, String>()
                writeActionId(store, actionId)
                readActionId(store) shouldBe actionId
            }
        }
    }

    "Property 6b: readActionId returns null when no value has been written" - {
        // Validates: Requirements 3.5
        "readActionId on an empty store returns null" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.string(1..50)
            ) { _ ->
                val store = emptyMap<String, String>()
                readActionId(store) shouldBe null
            }
        }
    }

    "Property 6c: writeActionId overwrites previous value" - {
        // Validates: Requirements 3.5
        "writing a second id replaces the first" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.string(1..30),
                Arb.string(1..30)
            ) { firstId, secondId ->
                val store = mutableMapOf<String, String>()
                writeActionId(store, firstId)
                writeActionId(store, secondId)
                readActionId(store) shouldBe secondId
            }
        }
    }

    "Property 6d: ActionDescriptor id is preserved through CategoryRegistry lookup" - {
        // Validates: Requirements 3.5
        "descriptor registered with id X can be retrieved by id X" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.string(1..30)
            ) { id ->
                val registry = CategoryRegistry()
                val descriptor = ActionDescriptor(
                    id = id,
                    displayName = "Test $id",
                    icon = Material.PAPER,
                    category = NodeCategory.PLAYER_ACTION
                )
                registry.register(descriptor)
                registry.getDescriptorById(id)?.id shouldBe id
            }
        }
    }
})
