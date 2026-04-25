@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.event

// Feature: ocp-gameplay-systems, Property 8: Placeholder parser event data

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.core.execution.PlaceholderParserImpl
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
 * Property 8: Placeholder parser event data
 *
 * For any template string and any of the supported event placeholders
 * (%victim%, %damager%, %killer%, %player%), PlaceholderParserImpl.parse()
 * must replace the placeholder with the corresponding value from eventData,
 * and for absent values use empty string or "none" for %killer%.
 *
 * **Validates: Requirements 3.4, 3.5, 4.4, 5.4, 5.5**
 */
class PlaceholderParserEventDataPropertyTest : FreeSpec({

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

    fun stubContext(
        player: Player? = null,
        eventData: Map<String, Any> = emptyMap()
    ): ExecutionContext {
        val ctx = mockk<ExecutionContext>(relaxed = true)
        every { ctx.player } returns player
        every { ctx.eventData } returns eventData
        every { ctx.localScope } returns stubScope()
        every { ctx.plotScope } returns stubScope()
        every { ctx.savedScope } returns stubScope()
        every { ctx.plotId } returns UUID.randomUUID()
        every { ctx.operationCount } returns AtomicInteger(0)
        every { ctx.callStackSize } returns AtomicInteger(0)
        return ctx
    }

    /** Strings that contain no % characters (safe prefix/suffix). */
    val arbSafeString: Arb<String> =
        Arb.string(0..40).filter { '%' !in it && '\n' !in it }

    /** Non-empty entity/player name strings without %. */
    val arbName: Arb<String> =
        Arb.string(1..32).filter { '%' !in it && it.isNotBlank() }

    // -----------------------------------------------------------------------
    // Property 8a: %victim% is replaced with eventData["victim"]
    // -----------------------------------------------------------------------

    "Property 8a: %victim% is replaced with the victim name from eventData" - {
        "victim placeholder resolves to eventData victim value" {
            // Validates: Requirements 3.4, 5.4
            checkAll(
                PropTestConfig(iterations = 500),
                arbName,
                arbSafeString,
                arbSafeString
            ) { victimName, prefix, suffix ->
                val template = "$prefix%victim%$suffix"
                val ctx = stubContext(eventData = mapOf("victim" to victimName))

                val result = parser.parse(template, ctx)

                result shouldBe "$prefix$victimName$suffix"
            }
        }

        "absent victim produces empty string for %victim%" {
            // Validates: Requirements 3.4
            checkAll(PropTestConfig(iterations = 500), arbSafeString) { suffix ->
                val template = "%victim%$suffix"
                val ctx = stubContext(eventData = emptyMap())

                val result = parser.parse(template, ctx)

                result shouldBe suffix
                result shouldNotContain "%victim%"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8b: %damager% is replaced with eventData["damager"]
    // -----------------------------------------------------------------------

    "Property 8b: %damager% is replaced with the damager name from eventData" - {
        "damager placeholder resolves to eventData damager value" {
            // Validates: Requirements 3.5
            checkAll(
                PropTestConfig(iterations = 500),
                arbName,
                arbSafeString,
                arbSafeString
            ) { damagerName, prefix, suffix ->
                val template = "$prefix%damager%$suffix"
                val ctx = stubContext(eventData = mapOf("damager" to damagerName))

                val result = parser.parse(template, ctx)

                result shouldBe "$prefix$damagerName$suffix"
            }
        }

        "absent damager produces empty string for %damager%" {
            // Validates: Requirements 3.5
            checkAll(PropTestConfig(iterations = 500), arbSafeString) { suffix ->
                val template = "%damager%$suffix"
                val ctx = stubContext(eventData = emptyMap())

                val result = parser.parse(template, ctx)

                result shouldBe suffix
                result shouldNotContain "%damager%"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8c: %killer% is replaced with eventData["killer"] or "none"
    // -----------------------------------------------------------------------

    "Property 8c: %killer% is replaced with killer name or 'none' when absent" - {
        "killer placeholder resolves to eventData killer value when present" {
            // Validates: Requirements 5.5
            checkAll(
                PropTestConfig(iterations = 500),
                arbName,
                arbSafeString,
                arbSafeString
            ) { killerName, prefix, suffix ->
                val template = "$prefix%killer%$suffix"
                val ctx = stubContext(eventData = mapOf("killer" to killerName))

                val result = parser.parse(template, ctx)

                result shouldBe "$prefix$killerName$suffix"
            }
        }

        "absent killer produces 'none' for %killer%" {
            // Validates: Requirements 5.5
            checkAll(
                PropTestConfig(iterations = 500),
                arbSafeString,
                arbSafeString
            ) { prefix, suffix ->
                val template = "$prefix%killer%$suffix"
                val ctx = stubContext(eventData = emptyMap())

                val result = parser.parse(template, ctx)

                result shouldBe "${prefix}none$suffix"
                result shouldNotContain "%killer%"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8d: %player% is replaced with the player name from context
    // -----------------------------------------------------------------------

    "Property 8d: %player% is replaced with the player name from context" - {
        "player placeholder resolves to player name" {
            // Validates: Requirements 4.4
            checkAll(
                PropTestConfig(iterations = 500),
                arbName,
                arbSafeString,
                arbSafeString
            ) { playerName, prefix, suffix ->
                val player = mockk<Player>(relaxed = true)
                every { player.name } returns playerName

                val template = "$prefix%player%$suffix"
                val ctx = stubContext(player = player)

                val result = parser.parse(template, ctx)

                result shouldBe "$prefix$playerName$suffix"
            }
        }

        "absent player produces empty string for %player%" {
            // Validates: Requirements 4.4
            checkAll(
                PropTestConfig(iterations = 500),
                arbSafeString,
                arbSafeString
            ) { prefix, suffix ->
                val template = "$prefix%player%$suffix"
                val ctx = stubContext(player = null)

                val result = parser.parse(template, ctx)

                result shouldBe "$prefix$suffix"
                result shouldNotContain "%player%"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8e: multiple placeholders in one template are all replaced
    // -----------------------------------------------------------------------

    "Property 8e: multiple event placeholders in one template are all replaced" - {
        "victim, damager, and killer are all substituted in a single template" {
            // Validates: Requirements 3.4, 3.5, 5.4, 5.5
            checkAll(
                PropTestConfig(iterations = 500),
                arbName,
                arbName,
                arbName
            ) { victimName, damagerName, killerName ->
                val template = "%victim% was killed by %damager%, killer: %killer%"
                val ctx = stubContext(
                    eventData = mapOf(
                        "victim"  to victimName,
                        "damager" to damagerName,
                        "killer"  to killerName
                    )
                )

                val result = parser.parse(template, ctx)

                result shouldContain victimName
                result shouldContain damagerName
                result shouldContain killerName
                result shouldNotContain "%victim%"
                result shouldNotContain "%damager%"
                result shouldNotContain "%killer%"
            }
        }

        "player and victim together are both substituted" {
            // Validates: Requirements 4.4, 5.4
            checkAll(
                PropTestConfig(iterations = 500),
                arbName,
                arbName
            ) { playerName, victimName ->
                val player = mockk<Player>(relaxed = true)
                every { player.name } returns playerName

                val template = "%player% witnessed %victim%"
                val ctx = stubContext(
                    player = player,
                    eventData = mapOf("victim" to victimName)
                )

                val result = parser.parse(template, ctx)

                result shouldBe "$playerName witnessed $victimName"
            }
        }
    }
})
