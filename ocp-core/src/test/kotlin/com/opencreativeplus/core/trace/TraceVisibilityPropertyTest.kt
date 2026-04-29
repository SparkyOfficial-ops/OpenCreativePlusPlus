@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-visual-programming-platform, Property 16: Трассировка видна только активировавшему игроку

package com.opencreativeplus.core.trace

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * Property-based test for TraceManager particle visibility isolation.
 *
 * **Validates: Requirements 8.5**
 *
 * Property 16: When multiple players are present on a plot, particle effects spawned by
 * [TraceManager.onNodeExecute] and [TraceManager.highlightPath] are sent ONLY to the player
 * who has trace mode active — never to other players on the same plot.
 * After toggling trace mode off, no particles are sent to that player either.
 */
class TraceVisibilityPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builds a fake [Plugin] that satisfies TraceManager's constructor. */
    fun fakePlugin(): Plugin = mockk(relaxed = true)

    /**
     * Builds a mock [Player] with a fixed [uuid].
     * [spawnParticle] calls are tracked by mockk.
     */
    fun fakePlayer(uuid: UUID = UUID.randomUUID()): Player {
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns uuid
        return player
    }

    /**
     * Builds a mock [Location] in a fake [World].
     * The world's [World.spawn] is relaxed so ArmorStand creation doesn't crash.
     */
    fun fakeLocation(): Location {
        val world = mockk<World>(relaxed = true)
        return Location(world, 10.0, 64.0, 10.0)
    }

    /**
     * Sets up [Bukkit.getPlayer] static mock so that each player in [players]
     * is returned when queried by their UUID. All other UUIDs return null.
     */
    fun setupBukkitGetPlayer(players: List<Player>) {
        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(any<UUID>()) } returns null
        players.forEach { player ->
            every { Bukkit.getPlayer(player.uniqueId) } returns player
        }
    }

    // -----------------------------------------------------------------------
    // Property 16a — only the tracing player receives particles from onNodeExecute
    // -----------------------------------------------------------------------

    "Property 16: Трассировка видна только активировавшему игроку" - {

        /**
         * For any number of bystander players (1..10), when only one player has trace mode ON,
         * [TraceManager.onNodeExecute] must call [Player.spawnParticle] on the tracing player
         * and NEVER on any bystander.
         *
         * **Validates: Requirements 8.5**
         */
        "onNodeExecute — particles sent only to tracing player, not to bystanders" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 10)
            ) { bystanderCount ->
                val plugin = fakePlugin()
                val traceManager = TraceManager(plugin)

                val tracingPlayer = fakePlayer()
                val bystanders = (1..bystanderCount).map { fakePlayer() }

                setupBukkitGetPlayer(listOf(tracingPlayer) + bystanders)

                try {
                    // Only the tracing player activates trace mode
                    traceManager.toggle(tracingPlayer)

                    val location = fakeLocation()
                    traceManager.onNodeExecute(location)

                    // Tracing player must have received at least one spawnParticle call
                    verify(atLeast = 1) {
                        tracingPlayer.spawnParticle(
                            Particle.REDSTONE,
                            any<Location>(),
                            any(),
                            any<Particle.DustOptions>()
                        )
                    }

                    // No bystander should have received any spawnParticle call
                    bystanders.forEach { bystander ->
                        verify(exactly = 0) {
                            bystander.spawnParticle(any(), any<Location>(), any())
                        }
                        verify(exactly = 0) {
                            bystander.spawnParticle(any(), any<Location>(), any(), any<Particle.DustOptions>())
                        }
                    }
                } finally {
                    unmockkStatic(Bukkit::class)
                }
            }
        }

        /**
         * For any number of bystander players (1..10), when only one player has trace mode ON,
         * [TraceManager.highlightPath] must call [Player.spawnParticle] on the tracing player
         * and NEVER on any bystander.
         *
         * **Validates: Requirements 8.5**
         */
        "highlightPath — particles sent only to tracing player, not to bystanders" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 10)
            ) { bystanderCount ->
                val plugin = fakePlugin()
                val traceManager = TraceManager(plugin)

                val tracingPlayer = fakePlayer()
                val bystanders = (1..bystanderCount).map { fakePlayer() }

                setupBukkitGetPlayer(listOf(tracingPlayer) + bystanders)

                try {
                    traceManager.toggle(tracingPlayer)

                    val world = mockk<World>(relaxed = true)
                    val from = Location(world, 0.0, 64.0, 0.0)
                    val to = Location(world, 5.0, 64.0, 0.0)

                    traceManager.highlightPath(from, to)

                    // Tracing player must have received at least one ELECTRIC_SPARK call
                    verify(atLeast = 1) {
                        tracingPlayer.spawnParticle(Particle.ELECTRIC_SPARK, any<Location>(), any())
                    }

                    // No bystander should have received any spawnParticle call
                    bystanders.forEach { bystander ->
                        verify(exactly = 0) {
                            bystander.spawnParticle(any(), any<Location>(), any())
                        }
                        verify(exactly = 0) {
                            bystander.spawnParticle(any(), any<Location>(), any(), any<Particle.DustOptions>())
                        }
                    }
                } finally {
                    unmockkStatic(Bukkit::class)
                }
            }
        }

        /**
         * When multiple players each activate trace mode independently,
         * each player receives particles from [TraceManager.onNodeExecute].
         *
         * **Validates: Requirements 8.5**
         */
        "multiple tracing players — each receives particles independently" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(2, 5)
            ) { playerCount ->
                val plugin = fakePlugin()
                val traceManager = TraceManager(plugin)

                val players = (1..playerCount).map { fakePlayer() }

                setupBukkitGetPlayer(players)

                try {
                    // All players activate trace mode
                    players.forEach { traceManager.toggle(it) }

                    val location = fakeLocation()
                    traceManager.onNodeExecute(location)

                    // Every tracing player must receive particles
                    players.forEach { player ->
                        verify(atLeast = 1) {
                            player.spawnParticle(
                                Particle.REDSTONE,
                                any<Location>(),
                                any(),
                                any<Particle.DustOptions>()
                            )
                        }
                    }
                } finally {
                    unmockkStatic(Bukkit::class)
                }
            }
        }

        /**
         * Toggle behavior: after toggling trace mode OFF, no particles are sent to that player.
         * For any number of bystanders (0..5), the toggled-off player receives no particles.
         *
         * **Validates: Requirements 8.5**
         */
        "toggle OFF — no particles sent to player after deactivation" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(0, 5)
            ) { bystanderCount ->
                val plugin = fakePlugin()
                val traceManager = TraceManager(plugin)

                val player = fakePlayer()
                val bystanders = (1..bystanderCount).map { fakePlayer() }

                setupBukkitGetPlayer(listOf(player) + bystanders)

                try {
                    // Toggle ON then immediately OFF
                    traceManager.toggle(player)
                    traceManager.toggle(player)

                    val location = fakeLocation()
                    traceManager.onNodeExecute(location)

                    // Player toggled off must receive NO particles
                    verify(exactly = 0) {
                        player.spawnParticle(any(), any<Location>(), any())
                    }
                    verify(exactly = 0) {
                        player.spawnParticle(any(), any<Location>(), any(), any<Particle.DustOptions>())
                    }

                    // Bystanders also receive no particles (none are tracing)
                    bystanders.forEach { bystander ->
                        verify(exactly = 0) {
                            bystander.spawnParticle(any(), any<Location>(), any())
                        }
                    }
                } finally {
                    unmockkStatic(Bukkit::class)
                }
            }
        }

        /**
         * Isolation: when player A is tracing and player B is not,
         * toggling player B's trace mode ON/OFF does not affect player A's particle reception.
         *
         * **Validates: Requirements 8.5**
         */
        "tracing player A is unaffected by player B toggling" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 5)
            ) { extraPlayers ->
                val plugin = fakePlugin()
                val traceManager = TraceManager(plugin)

                val playerA = fakePlayer()
                val otherPlayers = (1..extraPlayers).map { fakePlayer() }

                setupBukkitGetPlayer(listOf(playerA) + otherPlayers)

                try {
                    // Player A activates trace mode
                    traceManager.toggle(playerA)

                    // Other players toggle ON then OFF (net: not tracing)
                    otherPlayers.forEach { p ->
                        traceManager.toggle(p)
                        traceManager.toggle(p)
                    }

                    val location = fakeLocation()
                    traceManager.onNodeExecute(location)

                    // Player A still receives particles
                    verify(atLeast = 1) {
                        playerA.spawnParticle(
                            Particle.REDSTONE,
                            any<Location>(),
                            any(),
                            any<Particle.DustOptions>()
                        )
                    }

                    // Other players (toggled off) receive no particles
                    otherPlayers.forEach { p ->
                        verify(exactly = 0) {
                            p.spawnParticle(any(), any<Location>(), any())
                        }
                        verify(exactly = 0) {
                            p.spawnParticle(any(), any<Location>(), any(), any<Particle.DustOptions>())
                        }
                    }
                } finally {
                    unmockkStatic(Bukkit::class)
                }
            }
        }
    }
})
