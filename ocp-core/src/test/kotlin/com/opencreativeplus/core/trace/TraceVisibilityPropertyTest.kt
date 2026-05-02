@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-visual-programming-platform, Property 16: Трассировка видна только активировавшему игроку

package com.opencreativeplus.core.trace

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.mockk.clearAllMocks
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

class TraceVisibilityPropertyTest : FreeSpec({

    fun fakePlugin(): Plugin = mockk(relaxed = true)

    fun fakePlayer(uuid: UUID = UUID.randomUUID()): Player {
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns uuid
        return player
    }

    fun fakeLocation(): Location {
        val world = mockk<World>(relaxed = true)
        return Location(world, 10.0, 64.0, 10.0)
    }

    fun setupBukkitGetPlayer(players: List<Player>) {
        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(any<UUID>()) } returns null
        players.forEach { player ->
            every { Bukkit.getPlayer(player.uniqueId) } returns player
        }
    }

    "Property 16: Трассировка видна только активировавшему игроку" - {

        "onNodeExecute — particles sent only to tracing player, not to bystanders" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1, 5)
            ) { bystanderCount ->
                val plugin = fakePlugin()
                val traceManager = TraceManager(plugin)
                val tracingPlayer = fakePlayer()
                val bystanders = (1..bystanderCount).map { fakePlayer() }
                setupBukkitGetPlayer(listOf(tracingPlayer) + bystanders)
                try {
                    traceManager.toggle(tracingPlayer)
                    val location = fakeLocation()
                    traceManager.onNodeExecute(location)
                    verify(atLeast = 1) {
                        tracingPlayer.spawnParticle(
                            Particle.REDSTONE, any<Location>(), any(), any<Particle.DustOptions>()
                        )
                    }
                    bystanders.forEach { bystander ->
                        verify(exactly = 0) { bystander.spawnParticle(any(), any<Location>(), any()) }
                        verify(exactly = 0) { bystander.spawnParticle(any(), any<Location>(), any(), any<Particle.DustOptions>()) }
                    }
                } finally {
                    unmockkStatic(Bukkit::class)
                    clearAllMocks()
                }
            }
        }

        "highlightPath — particles sent only to tracing player, not to bystanders" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1, 5)
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
                    verify(atLeast = 1) {
                        tracingPlayer.spawnParticle(Particle.ELECTRIC_SPARK, any<Location>(), any())
                    }
                    bystanders.forEach { bystander ->
                        verify(exactly = 0) { bystander.spawnParticle(any(), any<Location>(), any()) }
                        verify(exactly = 0) { bystander.spawnParticle(any(), any<Location>(), any(), any<Particle.DustOptions>()) }
                    }
                } finally {
                    unmockkStatic(Bukkit::class)
                    clearAllMocks()
                }
            }
        }

        "multiple tracing players — each receives particles independently" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(2, 4)
            ) { playerCount ->
                val plugin = fakePlugin()
                val traceManager = TraceManager(plugin)
                val players = (1..playerCount).map { fakePlayer() }
                setupBukkitGetPlayer(players)
                try {
                    players.forEach { traceManager.toggle(it) }
                    val location = fakeLocation()
                    traceManager.onNodeExecute(location)
                    players.forEach { player ->
                        verify(atLeast = 1) {
                            player.spawnParticle(
                                Particle.REDSTONE, any<Location>(), any(), any<Particle.DustOptions>()
                            )
                        }
                    }
                } finally {
                    unmockkStatic(Bukkit::class)
                    clearAllMocks()
                }
            }
        }

        "toggle OFF — no particles sent to player after deactivation" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(0, 3)
            ) { bystanderCount ->
                val plugin = fakePlugin()
                val traceManager = TraceManager(plugin)
                val player = fakePlayer()
                val bystanders = (1..bystanderCount).map { fakePlayer() }
                setupBukkitGetPlayer(listOf(player) + bystanders)
                try {
                    traceManager.toggle(player)
                    traceManager.toggle(player)
                    val location = fakeLocation()
                    traceManager.onNodeExecute(location)
                    verify(exactly = 0) { player.spawnParticle(any(), any<Location>(), any()) }
                    verify(exactly = 0) { player.spawnParticle(any(), any<Location>(), any(), any<Particle.DustOptions>()) }
                    bystanders.forEach { bystander ->
                        verify(exactly = 0) { bystander.spawnParticle(any(), any<Location>(), any()) }
                    }
                } finally {
                    unmockkStatic(Bukkit::class)
                    clearAllMocks()
                }
            }
        }

        "tracing player A is unaffected by player B toggling" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1, 3)
            ) { extraPlayers ->
                val plugin = fakePlugin()
                val traceManager = TraceManager(plugin)
                val playerA = fakePlayer()
                val otherPlayers = (1..extraPlayers).map { fakePlayer() }
                setupBukkitGetPlayer(listOf(playerA) + otherPlayers)
                try {
                    traceManager.toggle(playerA)
                    otherPlayers.forEach { p ->
                        traceManager.toggle(p)
                        traceManager.toggle(p)
                    }
                    val location = fakeLocation()
                    traceManager.onNodeExecute(location)
                    verify(atLeast = 1) {
                        playerA.spawnParticle(
                            Particle.REDSTONE, any<Location>(), any(), any<Particle.DustOptions>()
                        )
                    }
                    otherPlayers.forEach { p ->
                        verify(exactly = 0) { p.spawnParticle(any(), any<Location>(), any()) }
                        verify(exactly = 0) { p.spawnParticle(any(), any<Location>(), any(), any<Particle.DustOptions>()) }
                    }
                } finally {
                    unmockkStatic(Bukkit::class)
                    clearAllMocks()
                }
            }
        }
    }
})
