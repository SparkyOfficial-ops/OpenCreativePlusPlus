// Feature: ocp-mvp2-core-systems, Property 9: PlaceholderParser заменяет токены
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.core.execution.PlaceholderParserImpl
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
 * Property 9 (integration): ItemVariableResolver applies PlaceholderParser to DataContainer.Text
 *
 * Sub-properties 9f and 9g verify that [ItemVariableResolver] correctly wires
 * [PlaceholderParserImpl] into [DataContainer.Text] resolution.
 *
 * Sub-properties 9a–9e (pure PlaceholderParserImpl) are in
 * `ocp-core/.../PlaceholderTokenPropertyTest.kt`.
 *
 * **Validates: Requirements 5.1, 5.2**
 */
class PlaceholderTokenResolverPropertyTest : FreeSpec({

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
    // Property 9f: ItemVariableResolver.resolveDataContainer applies
    //              PlaceholderParser to DataContainer.Text — Req 5.1, 5.2
    // -----------------------------------------------------------------------

    "Property 9f: ItemVariableResolver.resolveDataContainer applies PlaceholderParser to DataContainer.Text" - {

        "DataContainer.Text(\"%player%\") with a player resolves to the player name" {
            // Validates: Requirements 5.1, 5.2
            checkAll(
                PropTestConfig(iterations = 100),
                arbPlayerName
            ) { playerName ->
                val player = stubPlayer(playerName)
                val ctx = stubContext(player = player)
                val resolver = ItemVariableResolver(ctx, placeholderParser = PlaceholderParserImpl())

                val result = resolver.resolveDataContainer(DataContainer.Text("%player%"))

                result shouldBe playerName
            }
        }

        "DataContainer.Text(\"%var(name)%\") with variable set resolves to the variable value" {
            // Validates: Requirements 5.1, 5.2
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbVarValue
            ) { varName, varValue ->
                val ctx = stubContext(localVars = mapOf(varName to varValue))
                val resolver = ItemVariableResolver(ctx, placeholderParser = PlaceholderParserImpl())

                val result = resolver.resolveDataContainer(DataContainer.Text("%var($varName)%"))

                result shouldBe varValue
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 9g: ItemVariableResolver.resolveDataContainer returns raw text
    //              when no PlaceholderParser is provided — Req 5.2 (parser is optional)
    // -----------------------------------------------------------------------

    "Property 9g: ItemVariableResolver.resolveDataContainer returns raw text when no PlaceholderParser" - {

        "DataContainer.Text(\"%player%\") without a parser resolves to the literal string \"%player%\"" {
            // Validates: Requirements 5.2
            checkAll(
                PropTestConfig(iterations = 100),
                arbPlayerName
            ) { playerName ->
                val player = stubPlayer(playerName)
                val ctx = stubContext(player = player)
                // No parser wired in — placeholderParser defaults to null
                val resolver = ItemVariableResolver(ctx, placeholderParser = null)

                val result = resolver.resolveDataContainer(DataContainer.Text("%player%"))

                result shouldBe "%player%"
            }
        }
    }
})
