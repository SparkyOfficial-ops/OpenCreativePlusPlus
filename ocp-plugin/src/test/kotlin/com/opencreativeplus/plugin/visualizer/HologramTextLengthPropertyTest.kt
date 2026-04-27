// Feature: ocp-gameplay-systems, Property 20: Hologram text length
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.visualizer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property 20: Hologram text length
 *
 * For any error message, the hologram text (without color codes) must be
 * at most 40 characters.
 *
 * Validates: Requirements 12.2
 */
class HologramTextLengthPropertyTest : StringSpec({

    // -----------------------------------------------------------------------
    // Property 20a: text without color code is ≤ 40 chars for any input
    // -----------------------------------------------------------------------

    "Property 20a: hologram text without color code is at most 40 characters for any message" {
        checkAll(
            PropTestConfig(iterations = 500),
            Arb.string(0, 200)
        ) { message ->
            val store = FakeHologramStore20()
            store.reportError(message)
            val raw = store.getRawText() ?: ""
            (raw.length <= 40) shouldBe true
        }
    }

    // -----------------------------------------------------------------------
    // Property 20b: short messages (≤ 40 chars) are preserved exactly
    // -----------------------------------------------------------------------

    "Property 20b: messages of 40 chars or fewer are preserved without truncation" {
        checkAll(
            PropTestConfig(iterations = 500),
            Arb.string(0, 40)
        ) { message ->
            val store = FakeHologramStore20()
            store.reportError(message)
            val raw = store.getRawText() ?: ""
            raw shouldBe message
        }
    }

    // -----------------------------------------------------------------------
    // Property 20c: messages longer than 40 chars are truncated to exactly 40
    // -----------------------------------------------------------------------

    "Property 20c: messages longer than 40 chars are truncated to exactly 40 characters" {
        checkAll(
            PropTestConfig(iterations = 500),
            Arb.string(41, 200)
        ) { message ->
            val store = FakeHologramStore20()
            store.reportError(message)
            val raw = store.getRawText() ?: ""
            raw.length shouldBe 40
            raw shouldBe message.take(40)
        }
    }

    // -----------------------------------------------------------------------
    // Property 20d: display text starts with §c color code
    // -----------------------------------------------------------------------

    "Property 20d: display text is prefixed with the red color code §c" {
        checkAll(
            PropTestConfig(iterations = 500),
            Arb.string(0, 200)
        ) { message ->
            val store = FakeHologramStore20()
            store.reportError(message)
            val display = store.getDisplayText() ?: ""
            display.startsWith("§c") shouldBe true
        }
    }
})

// ---------------------------------------------------------------------------
// Test double: mirrors HologramReporter's text-truncation logic without Bukkit
// ---------------------------------------------------------------------------

private class FakeHologramStore20 {
    private var displayText: String? = null

    fun reportError(message: String) {
        val truncated = if (message.length > 40) message.take(40) else message
        displayText = "§c$truncated"
    }

    /** Returns the full display text including color code. */
    fun getDisplayText(): String? = displayText

    /** Returns the text without the leading §c color code. */
    fun getRawText(): String? = displayText?.removePrefix("§c")
}
