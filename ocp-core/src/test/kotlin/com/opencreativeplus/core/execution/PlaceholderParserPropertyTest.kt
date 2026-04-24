// Feature: ocp-manifest-roadmap, Property 6: PlaceholderParser round-trip
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based tests for [PlaceholderParserImpl].
 *
 * Property 6: PlaceholderParser round-trip
 *
 * 1. For any template that contains no placeholder tokens, `parse()` returns the
 *    original string unchanged.
 * 2. For any template containing `%var(name)%` and a context where `name` is set
 *    to value `v`, the result contains the string representation of `v`.
 *
 * **Validates: Requirements 5.1–5.5, 5.6**
 */
class PlaceholderParserPropertyTest : FreeSpec({

    val parser = PlaceholderParserImpl()

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Build a minimal stub [VariableScope] backed by a plain map. */
    fun stubScope(vars: Map<String, Any> = emptyMap()): VariableScope {
        val scope = mockk<VariableScope>(relaxed = true)
        every { scope.get(any()) } answers { vars[firstArg()] }
        every { scope.has(any()) } answers { vars.containsKey(firstArg()) }
        return scope
    }

    /** Build a minimal stub [ExecutionContext]. */
    fun stubContext(
        player: Player? = null,
        eventData: Map<String, Any> = emptyMap(),
        localVars: Map<String, Any> = emptyMap(),
        plotVars: Map<String, Any> = emptyMap()
    ): ExecutionContext {
        val ctx = mockk<ExecutionContext>(relaxed = true)
        every { ctx.player } returns player
        every { ctx.eventData } returns eventData
        every { ctx.localScope } returns stubScope(localVars)
        every { ctx.plotScope } returns stubScope(plotVars)
        every { ctx.savedScope } returns stubScope()
        every { ctx.plotId } returns UUID.randomUUID()
        every { ctx.operationCount } returns AtomicInteger(0)
        every { ctx.callStackSize } returns AtomicInteger(0)
        return ctx
    }

    /**
     * Arbitrary strings that contain none of the known placeholder tokens.
     * We exclude the `%` character entirely to guarantee no accidental placeholder.
     */
    val arbPlainString: Arb<String> =
        Arb.string(0..80).filter { s -> '%' !in s && '$' !in s }

    /**
     * Arbitrary valid variable names: letters, digits, underscores, non-empty.
     * Must start with a letter to be a valid identifier.
     */
    val arbVarName: Arb<String> =
        Arb.string(1..20).map { s ->
            // Normalise to a valid identifier: keep only [a-zA-Z0-9_], ensure starts with letter
            val cleaned = s.filter { it.isLetterOrDigit() || it == '_' }
            if (cleaned.isEmpty() || !cleaned[0].isLetter()) "v$cleaned" else cleaned
        }.filter { it.isNotEmpty() }

    /**
     * Arbitrary variable values (non-empty strings so we can assert containment).
     */
    val arbVarValue: Arb<String> =
        Arb.string(1..30).filter { '%' !in it }

    // -----------------------------------------------------------------------
    // Property 6a: plain text is returned unchanged
    // -----------------------------------------------------------------------

    "Property 6a: for any template without placeholders, parse() returns the original string" - {
        "plain text round-trip" {
            // Validates: Requirements 5.6
            checkAll(PropTestConfig(iterations = 100), arbPlainString) { template ->
                val result = parser.parse(template, stubContext())
                result shouldBe template
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 6b: %var(name)% is replaced with the variable value
    // -----------------------------------------------------------------------

    "Property 6b: template with %var(name)% contains the variable value after parse()" - {
        "variable placeholder is substituted from local scope" {
            // Validates: Requirements 5.3, 5.6
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbVarValue,
                arbPlainString,
                arbPlainString
            ) { varName, varValue, prefix, suffix ->
                val template = "$prefix%var($varName)%$suffix"
                val ctx = stubContext(localVars = mapOf(varName to varValue))

                val result = parser.parse(template, ctx)

                result shouldContain varValue
            }
        }

        "variable placeholder is substituted from plot scope when not in local scope" {
            // Validates: Requirements 5.3, 5.6
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbVarValue,
                arbPlainString,
                arbPlainString
            ) { varName, varValue, prefix, suffix ->
                val template = "$prefix%var($varName)%$suffix"
                val ctx = stubContext(plotVars = mapOf(varName to varValue))

                val result = parser.parse(template, ctx)

                result shouldContain varValue
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 6c: missing variable → empty string (not the placeholder literal)
    // -----------------------------------------------------------------------

    "Property 6c: %var(name)% with no matching variable is replaced with empty string" - {
        "absent variable produces empty string" {
            // Validates: Requirements 5.4
            checkAll(PropTestConfig(iterations = 100), arbVarName) { varName ->
                val template = "%var($varName)%"
                val ctx = stubContext() // no variables set

                val result = parser.parse(template, ctx)

                result shouldBe ""
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 6d: %player% is replaced with the player name
    // -----------------------------------------------------------------------

    "Property 6d: %player% is replaced with the player's name" - {
        "player placeholder resolves to player name" {
            // Validates: Requirements 5.1
            checkAll(
                PropTestConfig(iterations = 100),
                arbPlainString,
                arbPlainString
            ) { prefix, suffix ->
                val playerName = "TestPlayer"
                val player = mockk<Player>(relaxed = true)
                every { player.name } returns playerName

                val template = "$prefix%player%$suffix"
                val ctx = stubContext(player = player)

                val result = parser.parse(template, ctx)

                result shouldBe "$prefix$playerName$suffix"
            }
        }

        "absent player produces empty string for %player%" {
            // Validates: Requirements 5.1 (null player → empty)
            checkAll(PropTestConfig(iterations = 100), arbPlainString) { template ->
                val ctx = stubContext(player = null)
                val withPlaceholder = "%player%$template"

                val result = parser.parse(withPlaceholder, ctx)

                result shouldBe template
            }
        }
    }
})
