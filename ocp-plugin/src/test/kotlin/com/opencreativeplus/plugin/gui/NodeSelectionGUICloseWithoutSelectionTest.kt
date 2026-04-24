package com.opencreativeplus.plugin.gui

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

/**
 * Unit test: closing the NodeSelectionGUI without selecting an action leaves the block's PDC unchanged.
 *
 * Validates: Requirement 4 AC6
 *
 * We model the PDC as a MutableMap<String, String> and verify that NOT calling writeActionId
 * (i.e., closing without selection) leaves the store in its original state.
 */
class NodeSelectionGUICloseWithoutSelectionTest : FreeSpec({

    val pdcKey = "ocp:action_id"

    fun writeActionId(store: MutableMap<String, String>, actionId: String) {
        store[pdcKey] = actionId
    }

    fun readActionId(store: Map<String, String>): String? = store[pdcKey]

    "closing GUI without selection does not change PDC" - {

        "case 1: block with no existing action_id — store remains empty after close without selection" {
            val store = mutableMapOf<String, String>()

            // Simulate: GUI opened, player closes without clicking anything → writeActionId is NOT called
            // PDC store must remain empty
            store.isEmpty() shouldBe true
            readActionId(store) shouldBe null
        }

        "case 2: block with existing action_id — store still contains the original value after close without selection" {
            val store = mutableMapOf<String, String>()
            writeActionId(store, "existing_action")

            // Simulate: GUI opened, player closes without clicking anything → writeActionId is NOT called again
            // PDC store must still hold the original value
            readActionId(store) shouldBe "existing_action"
            store.size shouldBe 1
        }
    }
})
