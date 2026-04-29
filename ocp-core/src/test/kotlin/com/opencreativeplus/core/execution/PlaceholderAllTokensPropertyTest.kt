// Feature: ocp-visual-programming-platform, Property 12: PlaceholderParser заменяет все известные токены
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import io.kotest.core.spec.style.FreeSpec
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
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.Server
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property 12: PlaceholderParser заменяет все известные токены
 *
 * For any string containing one or more of the known placeholder tokens
 * (%player%, %online%, %loc_x%, %loc_y%, %loc_z%, %var(name)%),
 * after processing by [PlaceholderParserImpl] none of those tokens should
 * remain in the resulting string.
 *
 * **Validates: Requirements 6.1, 6.2, 6.5, 6.6, 6.7**
 */
class PlaceholderAllTokensPropertyTest : FreeSpec({

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

    fun stubPlayer(name: String, x: Double = 0.0, y: Double = 64.0, z: Double = 0.0, onlineCount: Int = 1): Player {
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
        Arb.string(0..30).filter { '%' !in it && '\n' !in it }

    /** Valid variable names: non-empty, starts with letter, only [a-zA-Z0-9_]. */
    val arbVarName: Arb<String> =
        Arb.string(1..16).map { s ->
            val cleaned = s.filter { it.isLetterOrDigit() || it == '_' }
            if (cleaned.isEmpty() || !cleaned[0].isLetter()) "v$cleaned" else cleaned
        }.filter { it.isNotEmpty() }

    /** Non-empty variable values without %. */
    val arbVarValue: Arb<String> =
        Arb.string(1..20).filter { '%' !in it && it.isNotBlank() }

    // -----------------------------------------------------------------------
    // Property 12a: %player% is always consumed
    // -----------------------------------------------------------------------

    "Property 12a: %player% token is never present in the output" - {
        "with a real player, %player% is replaced by the player name" {
            // Validates: Requirements 6.1, 6.2
            checkAll(PropTestConfig(iterations = 100), arbSafe, arbSafe) { prefix, suffix ->
                val player = stubPlayer("SomePlayer")
                val template = "$prefix%player%$suffix"
                val ctx = stubContext(player = player)

                val result = parser.parse(template, ctx)

                result shouldNotContain "%player%"
            }
        }

        "with no player, %player% is replaced by empty string" {
            // Validates: Requirements 6.1, 6.2
            checkAll(PropTestConfig(iterations = 100), arbSafe, arbSafe) { prefix, suffix ->
                val template = "$prefix%player%$suffix"
                val ctx = stubContext(player = null)

                val result = parser.parse(template, ctx)

                result shouldNotContain "%player%"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 12b: %online% is always consumed
    // -----------------------------------------------------------------------

    "Property 12b: %online% token is never present in the output" - {
        "with a player present, %online% is replaced by the online count" {
            // Validates: Requirements 6.1, 6.5
            checkAll(PropTestConfig(iterations = 100), arbSafe, arbSafe) { prefix, suffix ->
                val player = stubPlayer("Player1", onlineCount = 3)
                val template = "$prefix%online%$suffix"
                val ctx = stubContext(player = player)

                val result = parser.parse(template, ctx)

                result shouldNotContain "%online%"
            }
        }

        "with no player, %online% is replaced by empty string" {
            // Validates: Requirements 6.1, 6.5
            checkAll(PropTestConfig(iterations = 100), arbSafe, arbSafe) { prefix, suffix ->
                val template = "$prefix%online%$suffix"
                val ctx = stubContext(player = null)

                val result = parser.parse(template, ctx)

                result shouldNotContain "%online%"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 12c: %loc_x%, %loc_y%, %loc_z% are always consumed
    // -----------------------------------------------------------------------

    "Property 12c: location tokens are never present in the output" - {
        "with a player, %loc_x% is replaced by the X coordinate" {
            // Validates: Requirements 6.1, 6.6
            checkAll(PropTestConfig(iterations = 100), arbSafe, arbSafe) { prefix, suffix ->
                val player = stubPlayer("P", x = 42.5)
                val template = "$prefix%loc_x%$suffix"
                val ctx = stubContext(player = player)

                val result = parser.parse(template, ctx)

                result shouldNotContain "%loc_x%"
            }
        }

        "with a player, %loc_y% is replaced by the Y coordinate" {
            // Validates: Requirements 6.1, 6.6
            checkAll(PropTestConfig(iterations = 100), arbSafe, arbSafe) { prefix, suffix ->
                val player = stubPlayer("P", y = 100.0)
                val template = "$prefix%loc_y%$suffix"
                val ctx = stubContext(player = player)

                val result = parser.parse(template, ctx)

                result shouldNotContain "%loc_y%"
            }
        }

        "with a player, %loc_z% is replaced by the Z coordinate" {
            // Validates: Requirements 6.1, 6.6
            checkAll(PropTestConfig(iterations = 100), arbSafe, arbSafe) { prefix, suffix ->
                val player = stubPlayer("P", z = -300.0)
                val template = "$prefix%loc_z%$suffix"
                val ctx = stubContext(player = player)

                val result = parser.parse(template, ctx)

                result shouldNotContain "%loc_z%"
            }
        }

        "with no player, all location tokens are replaced by empty string" {
            // Validates: Requirements 6.1, 6.6
            checkAll(PropTestConfig(iterations = 100), arbSafe) { middle ->
                val template = "%loc_x%$middle%loc_y%$middle%loc_z%"
                val ctx = stubContext(player = null)

                val result = parser.parse(template, ctx)

                result shouldNotContain "%loc_x%"
                result shouldNotContain "%loc_y%"
                result shouldNotContain "%loc_z%"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 12d: %var(name)% is always consumed
    // -----------------------------------------------------------------------

    "Property 12d: %var(name)% token is never present in the output" - {
        "with a matching variable in local scope, %var(name)% is replaced" {
            // Validates: Requirements 6.1, 6.7
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbVarValue,
                arbSafe,
                arbSafe
            ) { varName, varValue, prefix, suffix ->
                val template = "$prefix%var($varName)%$suffix"
                val ctx = stubContext(localVars = mapOf(varName to varValue))

                val result = parser.parse(template, ctx)

                result shouldNotContain "%var($varName)%"
            }
        }

        "with no matching variable, %var(name)% is replaced by empty string" {
            // Validates: Requirements 6.1, 6.7
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbSafe,
                arbSafe
            ) { varName, prefix, suffix ->
                val template = "$prefix%var($varName)%$suffix"
                val ctx = stubContext()

                val result = parser.parse(template, ctx)

                result shouldNotContain "%var($varName)%"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 12e: all tokens together in one template are all consumed
    // -----------------------------------------------------------------------

    "Property 12e: all known tokens in a single template are all replaced" - {
        "combined template with all tokens leaves no placeholder literals" {
            // Validates: Requirements 6.1, 6.2, 6.5, 6.6, 6.7
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbVarValue
            ) { varName, varValue ->
                val player = stubPlayer("Hero", x = 1.0, y = 2.0, z = 3.0, onlineCount = 5)
                val template = "%player% online:%online% pos:%loc_x%,%loc_y%,%loc_z% var:%var($varName)%"
                val ctx = stubContext(
                    player = player,
                    localVars = mapOf(varName to varValue)
                )

                val result = parser.parse(template, ctx)

                result shouldNotContain "%player%"
                result shouldNotContain "%online%"
                result shouldNotContain "%loc_x%"
                result shouldNotContain "%loc_y%"
                result shouldNotContain "%loc_z%"
                result shouldNotContain "%var($varName)%"
            }
        }
    }
})
