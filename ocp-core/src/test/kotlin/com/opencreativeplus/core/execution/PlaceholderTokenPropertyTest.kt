// Feature: ocp-mvp2-core-systems, Property 9: PlaceholderParser заменяет токены
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property 9: PlaceholderParser заменяет токены
 *
 * For any string containing token `%player%` or `%var(name)%`,
 * `PlaceholderParser.parse` must replace the token with the corresponding
 * value from context (or empty string when absent).
 *
 * Sub-properties 9a–9e cover the pure [PlaceholderParserImpl] behaviour.
 * Sub-properties 9f–9g (ItemVariableResolver integration) are in
 * `ocp-plugin/.../PlaceholderTokenResolverPropertyTest.kt`.
 *
 * **Validates: Requirements 5.4, 5.5**
 */
class PlaceholderTokenPropertyTest : FreeSpec({

    val parser = PlaceholderParserImpl()

    // -----------------------------------------------------------------------
    // Helpers — same pattern as DataContainerTextIdempotencePropertyTest
    // -----------------------------------------------------------------------

    fun stubScope(vars: Map<String, Any> = emptyMap()): VariableScope {
        val scope = mockk<VariableScope>(relaxed = true)
        every { scope.get(any()) } answers { vars[firstArg()] }
        every { scope.has(any()) } answers { vars.containsKey(firstArg()) }
        return scope
    }

    fun stubPlayer(name: String): Player {
        val world = mockk<World>(relaxed = true)
        val location = Location(world, 0.0, 64.0, 0.0)
        val server = mockk<Server>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        every { player.name } returns name
        every { player.location } returns location
        every { server.onlinePlayers } returns mutableListOf()
        every { player.server } returns server
        return player
    }

    fun stubContext(
        player: Player? = null,
        localVars: Map<String, Any> = emptyMap(),
        plotVars: Map<String, Any> = emptyMap()
    ): ExecutionContext {
        val ctx = mockk<ExecutionContext>(relaxed = true)
        every { ctx.player } returns player
        every { ctx.eventData } returns emptyMap()
        every { ctx.localScope } returns stubScope(localVars)
        every { ctx.plotScope } returns stubScope(plotVars)
        every { ctx.savedScope } returns stubScope()
        every { ctx.plotId } returns UUID.randomUUID()
        every { ctx.operationCount } returns AtomicInteger(0)
        every { ctx.callStackSize } returns AtomicInteger(0)
        return ctx
    }

    /** Strings without `%` or newline — safe as prefix/suffix/values. */
    val arbSafe: Arb<String> =
        Arb.string(0..30).filter { '%' !in it && '\n' !in it }

    /** Valid variable names: non-empty, starts with letter, only [a-zA-Z0-9_]. */
    val arbVarName: Arb<String> =
        Arb.string(1..16).map { s ->
            val cleaned = s.filter { it.isLetterOrDigit() || it == '_' }
            if (cleaned.isEmpty() || !cleaned[0].isLetter()) "v$cleaned" else cleaned
        }.filter { it.isNotEmpty() }

    /** Non-empty variable values without `%` so they don't introduce new placeholders. */
    val arbVarValue: Arb<String> =
        Arb.string(1..20).filter { '%' !in it && it.isNotBlank() }

    /** Player names without `%` so they don't introduce new placeholders. */
    val arbPlayerName: Arb<String> =
        Arb.string(1..16).filter { '%' !in it && it.isNotBlank() }

    // -----------------------------------------------------------------------
    // Property 9a: %player% is replaced with player name — Req 5.4
    // -----------------------------------------------------------------------

    "Property 9a: %player% is replaced with player name" - {
        "for any prefix/suffix and player name, template with %player% resolves to prefix+name+suffix" {
            // Validates: Requirements 5.4
            checkAll(
                PropTestConfig(iterations = 100),
                arbSafe,
                arbSafe,
                arbPlayerName
            ) { prefix, suffix, playerName ->
                val player = stubPlayer(playerName)
                val ctx = stubContext(player = player)
                val template = "$prefix%player%$suffix"

                val result = parser.parse(template, ctx)

                result shouldBe "$prefix$playerName$suffix"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 9b: %player% resolves to empty string when player is null — Req 5.4
    // -----------------------------------------------------------------------

    "Property 9b: %player% resolves to empty string when player is null" - {
        "for any prefix/suffix, template with %player% and null player resolves to prefix+suffix" {
            // Validates: Requirements 5.4
            checkAll(
                PropTestConfig(iterations = 100),
                arbSafe,
                arbSafe
            ) { prefix, suffix ->
                val ctx = stubContext(player = null)
                val template = "$prefix%player%$suffix"

                val result = parser.parse(template, ctx)

                result shouldBe "$prefix$suffix"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 9c: %var(name)% is replaced with variable value from localScope — Req 5.5
    // -----------------------------------------------------------------------

    "Property 9c: %var(name)% is replaced with variable value from localScope" - {
        "for any varName, varValue, prefix, suffix — result contains varValue and not the token" {
            // Validates: Requirements 5.5
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbVarValue,
                arbSafe,
                arbSafe
            ) { varName, varValue, prefix, suffix ->
                val ctx = stubContext(localVars = mapOf(varName to varValue))
                val template = "$prefix%var($varName)%$suffix"

                val result = parser.parse(template, ctx)

                result shouldNotContain "%var($varName)%"
                result shouldBe "$prefix$varValue$suffix"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 9d: %var(name)% is replaced with variable value from plotScope
    //              when not in localScope — Req 5.5
    // -----------------------------------------------------------------------

    "Property 9d: %var(name)% is replaced with variable value from plotScope when not in localScope" - {
        "for any varName, varValue, prefix, suffix — result contains varValue from plotScope" {
            // Validates: Requirements 5.5
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbVarValue,
                arbSafe,
                arbSafe
            ) { varName, varValue, prefix, suffix ->
                // localScope is empty; variable only in plotScope
                val ctx = stubContext(plotVars = mapOf(varName to varValue))
                val template = "$prefix%var($varName)%$suffix"

                val result = parser.parse(template, ctx)

                result shouldNotContain "%var($varName)%"
                result shouldBe "$prefix$varValue$suffix"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 9e: %var(name)% resolves to empty string when variable is absent — Req 5.5
    // -----------------------------------------------------------------------

    "Property 9e: %var(name)% resolves to empty string when variable is absent" - {
        "for any varName, template with only the token and empty context resolves to empty string" {
            // Validates: Requirements 5.5
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName
            ) { varName ->
                val ctx = stubContext() // no variables set
                val template = "%var($varName)%"

                val result = parser.parse(template, ctx)

                result shouldBe ""
            }
        }
    }
})
