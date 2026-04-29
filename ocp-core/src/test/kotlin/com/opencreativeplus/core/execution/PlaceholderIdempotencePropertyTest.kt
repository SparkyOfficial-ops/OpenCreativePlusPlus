// Feature: ocp-visual-programming-platform, Property 13: Round-trip PlaceholderParser (идемпотентность)
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
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
 * Property 13: Round-trip PlaceholderParser (идемпотентность)
 *
 * For any string containing placeholder tokens and a fixed [ExecutionContext],
 * calling `parse` a second time on the already-resolved string must produce
 * an identical result (idempotency).
 *
 * In other words: `parse(parse(template, ctx), ctx) == parse(template, ctx)`
 *
 * **Validates: Requirements 6.9**
 */
class PlaceholderIdempotencePropertyTest : FreeSpec({

    val parser = PlaceholderParserImpl()

    // -----------------------------------------------------------------------
    // Helpers (same pattern as PlaceholderAllTokensPropertyTest)
    // -----------------------------------------------------------------------

    fun stubScope(vars: Map<String, Any> = emptyMap()): VariableScope {
        val scope = mockk<VariableScope>(relaxed = true)
        every { scope.get(any()) } answers { vars[firstArg()] }
        every { scope.has(any()) } answers { vars.containsKey(firstArg()) }
        return scope
    }

    fun stubPlayer(
        name: String,
        x: Double = 0.0,
        y: Double = 64.0,
        z: Double = 0.0,
        onlineCount: Int = 1
    ): Player {
        val world = mockk<World>(relaxed = true)
        val location = Location(world, x, y, z)
        val server = mockk<Server>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        every { player.name } returns name
        every { player.location } returns location
        every { server.onlinePlayers } returns (1..onlineCount).map { mockk<Player>(relaxed = true) }.toMutableList()
        every { player.server } returns server
        return player
    }

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

    /** Safe strings: no % or newline characters. */
    val arbSafe: Arb<String> =
        Arb.string(0..20).filter { '%' !in it && '\n' !in it }

    /** Valid variable names: non-empty, starts with letter, only [a-zA-Z0-9_]. */
    val arbVarName: Arb<String> =
        Arb.string(1..16).map { s ->
            val cleaned = s.filter { it.isLetterOrDigit() || it == '_' }
            if (cleaned.isEmpty() || !cleaned[0].isLetter()) "v$cleaned" else cleaned
        }.filter { it.isNotEmpty() }

    /** Non-empty variable values without % so they don't introduce new placeholders. */
    val arbVarValue: Arb<String> =
        Arb.string(1..20).filter { '%' !in it && it.isNotBlank() }

    /** Arbitrary known placeholder token strings. */
    val arbToken: Arb<String> = Arb.choice(
        Arb.constant("%player%"),
        Arb.constant("%online%"),
        Arb.constant("%loc_x%"),
        Arb.constant("%loc_y%"),
        Arb.constant("%loc_z%"),
        Arb.constant("%victim%"),
        Arb.constant("%damager%")
    )

    // -----------------------------------------------------------------------
    // Property 13a: idempotency for simple known tokens
    // -----------------------------------------------------------------------

    "Property 13a: parse is idempotent for strings containing known placeholder tokens" - {
        "second parse call on already-resolved string returns the same result" {
            // Validates: Requirements 6.9
            checkAll(
                PropTestConfig(iterations = 100),
                arbToken,
                arbSafe,
                arbSafe
            ) { token, prefix, suffix ->
                val player = stubPlayer("Hero", x = 10.0, y = 64.0, z = -5.0, onlineCount = 3)
                val ctx = stubContext(
                    player = player,
                    eventData = mapOf("victim" to "VictimPlayer", "damager" to "DamagerPlayer")
                )
                val template = "$prefix$token$suffix"

                val firstPass = parser.parse(template, ctx)
                val secondPass = parser.parse(firstPass, ctx)

                secondPass shouldBe firstPass
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 13b: idempotency for %var(name)% tokens
    // -----------------------------------------------------------------------

    "Property 13b: parse is idempotent for strings containing %var(name)% tokens" - {
        "second parse on resolved %var(name)% string returns the same result" {
            // Validates: Requirements 6.9
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbVarValue,
                arbSafe,
                arbSafe
            ) { varName, varValue, prefix, suffix ->
                val ctx = stubContext(localVars = mapOf(varName to varValue))
                val template = "$prefix%var($varName)%$suffix"

                val firstPass = parser.parse(template, ctx)
                val secondPass = parser.parse(firstPass, ctx)

                secondPass shouldBe firstPass
            }
        }

        "second parse on resolved %var(name)% with absent variable returns the same result" {
            // Validates: Requirements 6.9
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbSafe,
                arbSafe
            ) { varName, prefix, suffix ->
                val ctx = stubContext() // no variables set
                val template = "$prefix%var($varName)%$suffix"

                val firstPass = parser.parse(template, ctx)
                val secondPass = parser.parse(firstPass, ctx)

                secondPass shouldBe firstPass
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 13c: idempotency for combined templates with all tokens
    // -----------------------------------------------------------------------

    "Property 13c: parse is idempotent for templates combining multiple placeholder tokens" - {
        "combined template with all known tokens is idempotent" {
            // Validates: Requirements 6.9
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbVarValue
            ) { varName, varValue ->
                val player = stubPlayer("Hero", x = 1.0, y = 2.0, z = 3.0, onlineCount = 5)
                val ctx = stubContext(
                    player = player,
                    eventData = mapOf("victim" to "VictimPlayer", "damager" to "DamagerPlayer"),
                    localVars = mapOf(varName to varValue)
                )
                val template =
                    "%player% online:%online% pos:%loc_x%,%loc_y%,%loc_z% " +
                    "victim:%victim% damager:%damager% var:%var($varName)%"

                val firstPass = parser.parse(template, ctx)
                val secondPass = parser.parse(firstPass, ctx)

                secondPass shouldBe firstPass
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 13d: idempotency for plain strings (no placeholders)
    // -----------------------------------------------------------------------

    "Property 13d: parse is idempotent for plain strings without any placeholder tokens" - {
        "plain string is returned unchanged on both passes" {
            // Validates: Requirements 6.9
            checkAll(PropTestConfig(iterations = 100), arbSafe) { plain ->
                val ctx = stubContext()

                val firstPass = parser.parse(plain, ctx)
                val secondPass = parser.parse(firstPass, ctx)

                firstPass shouldBe plain
                secondPass shouldBe plain
            }
        }
    }
})
