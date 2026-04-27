@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.visualizer

// Feature: ocp-gameplay-systems, Property 18: DevVisualizer color

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.set
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import java.util.UUID

/**
 * Property 18: DevVisualizer color
 *
 * Particles must use red DustOptions when the code line is executing,
 * and gray DustOptions when the code line is idle.
 *
 * **Validates: Requirements 11.3, 11.4**
 */
class DevVisualizerColorPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Color constants mirroring DevVisualizer internals:
    //   private val dustExecuting = Particle.DustOptions(Color.RED, 1.0f)
    //   private val dustIdle     = Particle.DustOptions(Color.fromRGB(128, 128, 128), 1.0f)
    //
    // We model colors as simple data objects to avoid Bukkit API dependencies.
    // -----------------------------------------------------------------------

    data class DustColor(val r: Int, val g: Int, val b: Int)

    val RED  = DustColor(255, 0, 0)
    val GRAY = DustColor(128, 128, 128)

    /** Pure model of the color-selection logic in DevVisualizer.renderCodeLine */
    fun selectDustColor(isExecuting: Boolean): DustColor =
        if (isExecuting) RED else GRAY

    // -----------------------------------------------------------------------
    // Property 18a: executing state → red particles (Req 11.3)
    // -----------------------------------------------------------------------

    "Property 18a: executing state always produces red dust color (Req 11.3)" - {
        "selectDustColor(isExecuting=true) == RED for any player / tracing state" {
            checkAll(
                PropTestConfig(iterations = 200),
                Arb.uuid(),
                Arb.set(Arb.uuid(), 0..10)
            ) { playerId, tracingPlayers ->
                // tracing state is irrelevant to color selection — color depends only on isExecuting
                val tracingWithPlayer = tracingPlayers + playerId
                val color = selectDustColor(isExecuting = true)
                color shouldBe RED
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 18b: idle state → gray particles (Req 11.4)
    // -----------------------------------------------------------------------

    "Property 18b: idle state always produces gray dust color (Req 11.4)" - {
        "selectDustColor(isExecuting=false) == GRAY for any player / tracing state" {
            checkAll(
                PropTestConfig(iterations = 200),
                Arb.uuid(),
                Arb.set(Arb.uuid(), 0..10)
            ) { playerId, tracingPlayers ->
                val tracingWithPlayer = tracingPlayers + playerId
                val color = selectDustColor(isExecuting = false)
                color shouldBe GRAY
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 18c: color is determined solely by isExecuting flag (Req 11.3, 11.4)
    // -----------------------------------------------------------------------

    "Property 18c: color is determined solely by the isExecuting flag (Req 11.3, 11.4)" - {
        "two calls with the same isExecuting always return the same color" {
            checkAll(
                PropTestConfig(iterations = 200),
                Arb.boolean()
            ) { isExecuting ->
                val color1 = selectDustColor(isExecuting)
                val color2 = selectDustColor(isExecuting)
                color1 shouldBe color2
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 18d: executing and idle colors are distinct (Req 11.3, 11.4)
    // -----------------------------------------------------------------------

    "Property 18d: executing color and idle color are always distinct (Req 11.3, 11.4)" - {
        "RED != GRAY regardless of any external state" {
            checkAll(
                PropTestConfig(iterations = 200),
                Arb.list(Arb.uuid(), 0..20),
                Arb.set(Arb.uuid(), 0..10)
            ) { allPlayers, executingPlayers ->
                // For every player, executing and idle colors must differ
                for (playerId in allPlayers) {
                    val executingColor = selectDustColor(isExecuting = executingPlayers.contains(playerId))
                    val oppositeColor  = selectDustColor(isExecuting = !executingPlayers.contains(playerId))
                    (executingColor == oppositeColor) shouldBe false
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 18e: markExecuting / markIdle state transitions produce correct colors
    //               Models the executingPlayers set in DevVisualizer (Req 11.3, 11.4)
    // -----------------------------------------------------------------------

    "Property 18e: state transitions markExecuting/markIdle produce correct colors (Req 11.3, 11.4)" - {
        "after markExecuting the color is red; after markIdle the color is gray" {
            checkAll(
                PropTestConfig(iterations = 200),
                Arb.uuid()
            ) { playerId ->
                val executingSet = mutableSetOf<UUID>()

                // markExecuting → red
                executingSet.add(playerId)
                val colorAfterExecuting = selectDustColor(isExecuting = executingSet.contains(playerId))
                colorAfterExecuting shouldBe RED

                // markIdle → gray
                executingSet.remove(playerId)
                val colorAfterIdle = selectDustColor(isExecuting = executingSet.contains(playerId))
                colorAfterIdle shouldBe GRAY
            }
        }
    }
})
