// Feature: ocp-visual-programming-platform, Requirements 8.1, 8.4

package com.opencreativeplus.core.trace

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
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
 * Unit tests for [TraceManager] toggle behaviour.
 *
 * Validates: Requirements 8.1, 8.4
 */
class TraceManagerToggleTest : FreeSpec({

    // ── Helpers ──────────────────────────────────────────────────────────────

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

    // ── Tests ─────────────────────────────────────────────────────────────────

    "toggle ON — isTracing returns true and returns true" {
        val manager = TraceManager(fakePlugin())
        val player = fakePlayer()

        val result = manager.toggle(player)

        result shouldBe true
        manager.isTracing(player.uniqueId) shouldBe true
    }

    "toggle OFF — isTracing returns false and returns false" {
        val manager = TraceManager(fakePlugin())
        val player = fakePlayer()

        manager.toggle(player)           // ON
        val result = manager.toggle(player) // OFF

        result shouldBe false
        manager.isTracing(player.uniqueId) shouldBe false
    }

    "toggle ON — sends 'Trace Mode ON' message to player" {
        val manager = TraceManager(fakePlugin())
        val player = fakePlayer()

        manager.toggle(player)

        verify(exactly = 1) { player.sendMessage("§aTrace Mode ON") }
    }

    "toggle OFF — sends 'Trace Mode OFF' message to player" {
        val manager = TraceManager(fakePlugin())
        val player = fakePlayer()

        manager.toggle(player) // ON
        manager.toggle(player) // OFF

        verify(exactly = 1) { player.sendMessage("§cTrace Mode OFF") }
    }

    "toggle OFF — no particles spawned after deactivation" {
        val manager = TraceManager(fakePlugin())
        val player = fakePlayer()
        val location = fakeLocation()

        mockkStatic(Bukkit::class)
        try {
            every { Bukkit.getPlayer(any<UUID>()) } returns null
            every { Bukkit.getPlayer(player.uniqueId) } returns player

            manager.toggle(player) // ON
            manager.toggle(player) // OFF

            manager.onNodeExecute(location)

            verify(exactly = 0) { player.spawnParticle(any(), any<Location>(), any()) }
            verify(exactly = 0) {
                player.spawnParticle(any(), any<Location>(), any(), any<Particle.DustOptions>())
            }
        } finally {
            unmockkStatic(Bukkit::class)
        }
    }

    "toggle ON — particles spawned on onNodeExecute" {
        val manager = TraceManager(fakePlugin())
        val player = fakePlayer()
        val location = fakeLocation()

        mockkStatic(Bukkit::class)
        try {
            every { Bukkit.getPlayer(any<UUID>()) } returns null
            every { Bukkit.getPlayer(player.uniqueId) } returns player

            manager.toggle(player) // ON

            manager.onNodeExecute(location)

            verify(atLeast = 1) {
                player.spawnParticle(
                    Particle.REDSTONE,
                    any<Location>(),
                    any(),
                    any<Particle.DustOptions>()
                )
            }
        } finally {
            unmockkStatic(Bukkit::class)
        }
    }

    "multiple toggles — state alternates correctly" {
        val manager = TraceManager(fakePlugin())
        val player = fakePlayer()

        val s1 = manager.toggle(player) // ON
        val s2 = manager.toggle(player) // OFF
        val s3 = manager.toggle(player) // ON
        val s4 = manager.toggle(player) // OFF

        s1 shouldBe true
        s2 shouldBe false
        s3 shouldBe true
        s4 shouldBe false
    }
})
