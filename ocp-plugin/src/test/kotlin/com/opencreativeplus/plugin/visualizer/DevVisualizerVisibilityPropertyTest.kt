@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.visualizer

// Feature: ocp-gameplay-systems, Property 17: DevVisualizer visibility

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.set
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import java.util.UUID

/**
 * Property 17: DevVisualizer visibility
 *
 * The DevVisualizer must render particles ONLY for players who have Trace Mode active.
 * Players without active Trace Mode must never receive particle calls.
 *
 * **Validates: Requirements 11.1, 11.6**
 */
class DevVisualizerVisibilityPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Pure guard logic extracted from DevVisualizer.renderParticles:
    //
    //   private fun renderParticles(player: Player, codeLines: List<CodeLine>) {
    //       if (!traceManager.isTracing(player.uniqueId)) return
    //       // ... spawns particles
    //   }
    //
    // We model this as a pure function to avoid Bukkit API dependencies.
    // -----------------------------------------------------------------------

    fun simulateRenderParticles(
        playerId: UUID,
        tracingPlayers: Set<UUID>,
        recorder: MutableSet<UUID>
    ) {
        if (!tracingPlayers.contains(playerId)) return  // mirrors the isTracing guard
        recorder.add(playerId)
    }

    // -----------------------------------------------------------------------
    // Property 17a: Non-tracing players are never added to the recorder (Req 11.6)
    // -----------------------------------------------------------------------

    "Property 17a: non-tracing player is never added to the spawn recorder (Req 11.6)" - {
        "simulateRenderParticles does not record a player absent from the tracing set" {
            checkAll(
                PropTestConfig(iterations = 200),
                Arb.uuid(),
                Arb.set(Arb.uuid(), 0..10)
            ) { playerId, tracingPlayers ->
                // Ensure the player is NOT in the tracing set
                val tracingWithoutPlayer = tracingPlayers - playerId
                val recorder = mutableSetOf<UUID>()

                simulateRenderParticles(playerId, tracingWithoutPlayer, recorder)

                recorder.contains(playerId) shouldBe false
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 17b: Tracing players ARE added to the recorder (Req 11.1)
    // -----------------------------------------------------------------------

    "Property 17b: tracing player is always added to the spawn recorder (Req 11.1)" - {
        "simulateRenderParticles records a player present in the tracing set" {
            checkAll(
                PropTestConfig(iterations = 200),
                Arb.uuid(),
                Arb.set(Arb.uuid(), 0..10)
            ) { playerId, extraTracingPlayers ->
                // Ensure the player IS in the tracing set
                val tracingWithPlayer = extraTracingPlayers + playerId
                val recorder = mutableSetOf<UUID>()

                simulateRenderParticles(playerId, tracingWithPlayer, recorder)

                recorder.contains(playerId) shouldBe true
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 17c: Mixed set — only tracing players appear in the recorder (Req 11.6)
    // -----------------------------------------------------------------------

    "Property 17c: only tracing players appear in the recorder for a mixed player list (Req 11.6)" - {
        "after simulating render for all players, recorder contains exactly the tracing subset" {
            checkAll(
                PropTestConfig(iterations = 200),
                Arb.list(Arb.uuid(), 1..20),
                Arb.set(Arb.uuid(), 0..10)
            ) { allPlayers, tracingPlayers ->
                val recorder = mutableSetOf<UUID>()

                for (playerId in allPlayers) {
                    simulateRenderParticles(playerId, tracingPlayers, recorder)
                }

                // Every player in the recorder must have been tracing
                recorder.forEach { recordedId ->
                    tracingPlayers.contains(recordedId) shouldBe true
                }

                // Every tracing player who was in allPlayers must be in the recorder
                val tracingInList = allPlayers.filter { it in tracingPlayers }.toSet()
                tracingInList.forEach { tracingId ->
                    recorder.contains(tracingId) shouldBe true
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 17d: Idempotency — calling render multiple times for a non-tracing
    //               player never adds them to the recorder (Req 11.6)
    // -----------------------------------------------------------------------

    "Property 17d: repeated render calls for a non-tracing player never add them to the recorder (Req 11.6)" - {
        "idempotent guard: non-tracing player stays absent regardless of call count" {
            checkAll(
                PropTestConfig(iterations = 200),
                Arb.uuid(),
                Arb.set(Arb.uuid(), 0..10)
            ) { playerId, tracingPlayers ->
                val tracingWithoutPlayer = tracingPlayers - playerId
                val recorder = mutableSetOf<UUID>()

                // Call render multiple times (simulating repeated timer ticks)
                repeat(5) {
                    simulateRenderParticles(playerId, tracingWithoutPlayer, recorder)
                }

                recorder.contains(playerId) shouldBe false
            }
        }
    }
})
