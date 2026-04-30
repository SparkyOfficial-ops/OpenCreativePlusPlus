@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.node.scoreboard

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property 18: Scoreboard template variable substitution
 * Validates: Requirement 12.5 — When a scoreboard template contains `{variable_name}`,
 * the engine replaces it with the current value of that variable from the plot scope
 * at render time.
 */
class ScoreboardTemplatePropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers — same minimal fake pattern as ArrayNodePropertyTest
    // -----------------------------------------------------------------------

    fun mapScope(initial: Map<String, Any> = emptyMap()): VariableScope {
        val store = initial.toMutableMap()
        return object : VariableScope {
            override fun get(name: String): Any? = store[name]
            override fun set(name: String, value: Any) { store[name] = value }
            override fun has(name: String): Boolean = store.containsKey(name)
            override fun clear() = store.clear()
        }
    }

    fun makeContext(plot: Map<String, Any> = emptyMap(), local: Map<String, Any> = emptyMap()): ExecutionContext {
        val plotScope = mapScope(plot)
        val localScope = mapScope(local)
        return object : ExecutionContext {
            override val plotId: UUID = UUID.randomUUID()
            override val player: Player? = null
            override val eventData: Map<String, Any> = emptyMap()
            override val localScope: VariableScope = localScope
            override val plotScope: VariableScope = plotScope
            override val savedScope: VariableScope = mapScope()
            override val operationCount: AtomicInteger = AtomicInteger(0)
            override val callStackSize: AtomicInteger = AtomicInteger(0)
            override val targets: MutableList<org.bukkit.entity.Entity> = mutableListOf()
            override var currentTarget: org.bukkit.entity.Entity? = null
            override suspend fun <T> syncContext(block: () -> T): T = block()
        }
    }

    // Valid \w+ identifiers: letters/digits/underscore, non-empty, no braces
    val arbVarName: Arb<String> = Arb.string(1..8).filter { it.matches(Regex("\\w+")) }

    // Arbitrary string values (no braces to avoid accidental placeholder collisions)
    val arbValue: Arb<String> = Arb.string(0..20).filter { !it.contains('{') && !it.contains('}') }

    // -----------------------------------------------------------------------
    // Property 18a: All {varName} placeholders replaced when variable exists in plotScope
    // -----------------------------------------------------------------------

    "Property 18a: all {varName} placeholders are replaced when variable exists in plotScope" - {
        // Validates: Requirement 12.5
        // For any map of variable names → string values, build a template that contains
        // each name as {varName}. resolveTemplate must replace every placeholder with
        // the corresponding value from plotScope.
        "every placeholder is replaced by its plotScope value" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.map(arbVarName, arbValue, minSize = 1, maxSize = 6)
            ) { vars ->
                val template = vars.keys.joinToString(" ") { "{$it}" }
                val ctx = makeContext(plot = vars)

                val result = resolveTemplate(template, ctx)

                vars.forEach { (name, value) ->
                    result.contains(value) shouldBe true
                    result.contains("{$name}") shouldBe false
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 18b: Placeholders for missing variables are left unchanged
    // -----------------------------------------------------------------------

    "Property 18b: placeholders for missing variables are left unchanged" - {
        // Validates: Requirement 12.5
        // When a variable is absent from both plotScope and localScope,
        // the original {varName} token must remain in the output.
        "unknown placeholder is preserved as-is" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName
            ) { varName ->
                val template = "prefix {$varName} suffix"
                val ctx = makeContext() // empty scopes

                val result = resolveTemplate(template, ctx)

                result shouldBe "prefix {$varName} suffix"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 18c: plotScope takes priority over localScope
    // -----------------------------------------------------------------------

    "Property 18c: plotScope value takes priority over localScope value" - {
        // Validates: Requirement 12.5
        // When the same variable name exists in both scopes with different values,
        // the plotScope value must win.
        "plotScope value is used when both scopes contain the variable" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbValue,
                arbValue
            ) { varName, plotValue, localValue ->
                val effectiveLocal = if (localValue == plotValue) plotValue + "x" else localValue

                val template = "{$varName}"
                val ctx = makeContext(
                    plot = mapOf(varName to plotValue),
                    local = mapOf(varName to effectiveLocal)
                )

                val result = resolveTemplate(template, ctx)

                result shouldBe plotValue
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 18d: Template with no placeholders is returned unchanged
    // -----------------------------------------------------------------------

    "Property 18d: template with no {…} placeholders is returned unchanged" - {
        // Validates: Requirement 12.5
        // A plain string with no {word} patterns must pass through resolveTemplate unchanged.
        "plain string is identity under resolveTemplate" {
            checkAll(
                PropTestConfig(iterations = 100),
                // Strings without braces cannot contain {varName} patterns
                Arb.string(0..40).filter { !it.contains('{') && !it.contains('}') },
                Arb.map(arbVarName, arbValue, minSize = 0, maxSize = 5)
            ) { plainText, vars ->
                val ctx = makeContext(plot = vars)

                val result = resolveTemplate(plainText, ctx)

                result shouldBe plainText
            }
        }
    }
})
