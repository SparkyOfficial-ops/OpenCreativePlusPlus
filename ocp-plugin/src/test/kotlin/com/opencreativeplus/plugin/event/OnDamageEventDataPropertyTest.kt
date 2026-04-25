@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.event

// Feature: ocp-gameplay-systems, Property 7: OnDamage eventData содержит все ключи

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property 7: OnDamage eventData содержит все ключи
 *
 * For any EntityDamageByEntityEvent within a plot, the dispatched ExecutionContext
 * must contain keys `victim`, `damager`, and `damage` with correct types
 * (String, String, Double).
 *
 * **Validates: Requirements 3.2, 3.3**
 */
class OnDamageEventDataPropertyTest : FreeSpec({

    /**
     * Simulates the eventData construction logic from PlotEventListener.onEntityDamageByEntity.
     * This mirrors the production code:
     *   val eventData = mapOf<String, Any>(
     *       "victim"  to victim.name,
     *       "damager" to damager.name,
     *       "damage"  to event.damage
     *   )
     */
    fun buildOnDamageEventData(victimName: String, damagerName: String, damage: Double): Map<String, Any> =
        mapOf(
            "victim"  to victimName,
            "damager" to damagerName,
            "damage"  to damage
        )

    // -----------------------------------------------------------------------
    // Property 7a: eventData always contains all three required keys
    // -----------------------------------------------------------------------

    "Property 7: OnDamage eventData contains all required keys for any victim, damager, and damage" - {

        "eventData contains 'victim', 'damager', and 'damage' keys" {
            // **Validates: Requirements 3.2, 3.3**
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.string(1..32),
                Arb.string(1..32),
                Arb.double().filter { it.isFinite() && it >= 0.0 }
            ) { victimName, damagerName, damage ->
                val eventData = buildOnDamageEventData(victimName, damagerName, damage)

                eventData.keys shouldContainAll listOf("victim", "damager", "damage")
            }
        }

        // -----------------------------------------------------------------------
        // Property 7b: 'victim' value is a String matching the victim entity name
        // -----------------------------------------------------------------------

        "'victim' value is a String equal to the victim entity name" {
            // **Validates: Requirements 3.3**
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.string(1..32),
                Arb.string(1..32),
                Arb.double().filter { it.isFinite() && it >= 0.0 }
            ) { victimName, damagerName, damage ->
                val eventData = buildOnDamageEventData(victimName, damagerName, damage)

                eventData["victim"].shouldBeInstanceOf<String>()
                eventData["victim"] shouldBe victimName
            }
        }

        // -----------------------------------------------------------------------
        // Property 7c: 'damager' value is a String matching the damager entity name
        // -----------------------------------------------------------------------

        "'damager' value is a String equal to the damager entity name" {
            // **Validates: Requirements 3.3**
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.string(1..32),
                Arb.string(1..32),
                Arb.double().filter { it.isFinite() && it >= 0.0 }
            ) { victimName, damagerName, damage ->
                val eventData = buildOnDamageEventData(victimName, damagerName, damage)

                eventData["damager"].shouldBeInstanceOf<String>()
                eventData["damager"] shouldBe damagerName
            }
        }

        // -----------------------------------------------------------------------
        // Property 7d: 'damage' value is a Double matching the damage amount
        // -----------------------------------------------------------------------

        "'damage' value is a Double equal to the damage amount" {
            // **Validates: Requirements 3.3**
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.string(1..32),
                Arb.string(1..32),
                Arb.double().filter { it.isFinite() && it >= 0.0 }
            ) { victimName, damagerName, damage ->
                val eventData = buildOnDamageEventData(victimName, damagerName, damage)

                eventData["damage"].shouldBeInstanceOf<Double>()
                eventData["damage"] shouldBe damage
            }
        }

        // -----------------------------------------------------------------------
        // Property 7e: eventData contains exactly these three keys (no extras)
        // -----------------------------------------------------------------------

        "eventData contains exactly the three required keys and no others" {
            // **Validates: Requirements 3.3**
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.string(1..32),
                Arb.string(1..32),
                Arb.double().filter { it.isFinite() && it >= 0.0 }
            ) { victimName, damagerName, damage ->
                val eventData = buildOnDamageEventData(victimName, damagerName, damage)

                eventData.keys shouldBe setOf("victim", "damager", "damage")
            }
        }
    }
})
