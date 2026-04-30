// Feature: ocp-mvp2-core-systems, Property 8: Идемпотентность PlaceholderParser
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.core.execution

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
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property 8: Идемпотентность PlaceholderParser
 *
 * For any `DataContainer.Text` string, applying `PlaceholderParser.parse` twice in a row
 * must give the same result as applying it once:
 *   `parse(parse(template, ctx), ctx) == parse(template, ctx)`
 *
 * **Validates: Requirements 5.3 and 5.6**
 */
class DataContainerTextIdempotencePropertyTest : FreeSpec({

    val parser = PlaceholderParserImpl()

    // -----------------------------------------------------------------------
    // Helpers
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

    /** Safe strings: no % or newline characters (0..80 chars). */
    val arbSafe: Arb<String> =
        Arb.string(0..80).filter { '%' !in it && '\n' !in it }

    /** Valid variable names: non-empty, starts with letter, only [a-zA-Z0-9_]. */
    val arbVarName: Arb<String> =
        Arb.string(1..16).map { s ->
            val cleaned = s.filter { it.isLetterOrDigit() || it == '_' }
            if (cleaned.isEmpty() || !cleaned[0].isLetter()) "v$cleaned" else cleaned
        }.filter { it.isNotEmpty() }

    /** Non-empty variable values without % so they don't introduce new placeholders. */
    val arbVarValue: Arb<String> =
        Arb.string(1..20).filter { '%' !in it && it.isNotBlank() }

    // -----------------------------------------------------------------------
    // Property 8a: plain text (no tokens) is returned unchanged — Req 5.3
    // -----------------------------------------------------------------------

    "Property 8a: plain DataContainer.Text with no tokens is returned unchanged" - {
        "first parse of plain text returns the string unchanged" {
            // Validates: Requirements 5.3
            checkAll(PropTestConfig(iterations = 100), arbSafe) { plain ->
                val ctx = stubContext()

                val firstPass = parser.parse(plain, ctx)

                firstPass shouldBe plain
            }
        }

        "second parse of plain text also returns the string unchanged" {
            // Validates: Requirements 5.3
            checkAll(PropTestConfig(iterations = 100), arbSafe) { plain ->
                val ctx = stubContext()

                val secondPass = parser.parse(parser.parse(plain, ctx), ctx)

                secondPass shouldBe plain
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8b: idempotency for DataContainer.Text with %player% token — Req 5.6
    // -----------------------------------------------------------------------

    "Property 8b: idempotency for DataContainer.Text with %player% token" - {
        "parse(parse(template, ctx), ctx) == parse(template, ctx) for %player% token" {
            // Validates: Requirements 5.6
            checkAll(
                PropTestConfig(iterations = 100),
                arbSafe,
                arbSafe,
                arbSafe  // player name without %
            ) { prefix, suffix, playerName ->
                val player = stubPlayer(playerName)
                val ctx = stubContext(player = player)
                val template = "$prefix%player%$suffix"

                val firstPass = parser.parse(template, ctx)
                val secondPass = parser.parse(firstPass, ctx)

                secondPass shouldBe firstPass
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8c: idempotency for DataContainer.Text with %var(name)% token — Req 5.6
    // -----------------------------------------------------------------------

    "Property 8c: idempotency for DataContainer.Text with %var(name)% token" - {
        "parse is idempotent when variable is present in localScope" {
            // Validates: Requirements 5.6
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

        "parse is idempotent when variable is absent (resolves to empty string)" {
            // Validates: Requirements 5.6
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
    // Property 8d: idempotency for DataContainer.Text with absent player — Req 5.6
    // -----------------------------------------------------------------------

    "Property 8d: idempotency for DataContainer.Text with absent player" - {
        "first pass replaces %player% with empty string when player is null" {
            // Validates: Requirements 5.6
            checkAll(
                PropTestConfig(iterations = 100),
                arbSafe,
                arbSafe
            ) { prefix, suffix ->
                val ctx = stubContext(player = null)
                val template = "$prefix%player%$suffix"

                val firstPass = parser.parse(template, ctx)

                firstPass shouldBe "$prefix$suffix"
            }
        }

        "second pass equals first pass when player is null" {
            // Validates: Requirements 5.6
            checkAll(
                PropTestConfig(iterations = 100),
                arbSafe,
                arbSafe
            ) { prefix, suffix ->
                val ctx = stubContext(player = null)
                val template = "$prefix%player%$suffix"

                val firstPass = parser.parse(template, ctx)
                val secondPass = parser.parse(firstPass, ctx)

                secondPass shouldBe firstPass
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8e: idempotency for combined tokens in DataContainer.Text — Req 5.3, 5.6
    // -----------------------------------------------------------------------

    "Property 8e: idempotency for DataContainer.Text combining %player%, %var(name)%, and plain text" - {
        "parse(parse(template, ctx), ctx) == parse(template, ctx) for combined tokens" {
            // Validates: Requirements 5.3, 5.6
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbVarValue,
                arbSafe,
                arbSafe
            ) { varName, varValue, prefix, suffix ->
                val player = stubPlayer("Hero", x = 1.0, y = 2.0, z = 3.0, onlineCount = 5)
                val ctx = stubContext(
                    player = player,
                    localVars = mapOf(varName to varValue)
                )
                // Template simulating a DataContainer.Text value with multiple tokens
                val template = "$prefix%player% var:%var($varName)%$suffix"

                val firstPass = parser.parse(template, ctx)
                val secondPass = parser.parse(firstPass, ctx)

                secondPass shouldBe firstPass
            }
        }
    }
})
