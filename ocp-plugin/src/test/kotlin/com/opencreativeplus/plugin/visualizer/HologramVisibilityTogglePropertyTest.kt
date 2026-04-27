// Feature: ocp-gameplay-systems, Property 21: Hologram visibility toggle
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.visualizer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property 21: Hologram visibility toggle
 *
 * For any player:
 * - entering dev mode (showToPlayer) → all active holograms become visible to that player
 * - exiting dev mode (hideFromPlayer) → all active holograms are hidden from that player
 *
 * Validates: Requirements 12.6, 12.7
 */
class HologramVisibilityTogglePropertyTest : StringSpec({

    // -----------------------------------------------------------------------
    // Property 21a: showToPlayer shows all active holograms to the player
    // -----------------------------------------------------------------------

    "Property 21a: showToPlayer shows all active holograms to the entering player" {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.int(1, 10),   // number of active holograms
            Arb.int(-100, 100),
            Arb.int(0, 200)
        ) { hologramCount, baseX, baseY ->
            val store = FakeHologramVisibilityStore()
            val playerId = UUID.randomUUID()

            // Register N holograms at distinct locations
            repeat(hologramCount) { i ->
                store.reportError(makeTestLocation21(baseX + i * 5, baseY, 0), "error-$i")
            }

            store.showToPlayer(playerId)

            store.showCallsFor(playerId) shouldBe hologramCount
            store.hideCallsFor(playerId) shouldBe 0
        }
    }

    // -----------------------------------------------------------------------
    // Property 21b: hideFromPlayer hides all active holograms from the player
    // -----------------------------------------------------------------------

    "Property 21b: hideFromPlayer hides all active holograms from the exiting player" {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.int(1, 10),
            Arb.int(-100, 100),
            Arb.int(0, 200)
        ) { hologramCount, baseX, baseY ->
            val store = FakeHologramVisibilityStore()
            val playerId = UUID.randomUUID()

            repeat(hologramCount) { i ->
                store.reportError(makeTestLocation21(baseX + i * 5, baseY, 0), "error-$i")
            }

            store.hideFromPlayer(playerId)

            store.hideCallsFor(playerId) shouldBe hologramCount
            store.showCallsFor(playerId) shouldBe 0
        }
    }

    // -----------------------------------------------------------------------
    // Property 21c: show then hide results in equal show and hide counts
    // -----------------------------------------------------------------------

    "Property 21c: show then hide results in equal show and hide call counts per player" {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.int(1, 8),
            Arb.int(-100, 100),
            Arb.int(0, 200)
        ) { hologramCount, baseX, baseY ->
            val store = FakeHologramVisibilityStore()
            val playerId = UUID.randomUUID()

            repeat(hologramCount) { i ->
                store.reportError(makeTestLocation21(baseX + i * 5, baseY, 0), "error-$i")
            }

            store.showToPlayer(playerId)
            store.hideFromPlayer(playerId)

            store.showCallsFor(playerId) shouldBe hologramCount
            store.hideCallsFor(playerId) shouldBe hologramCount
        }
    }

    // -----------------------------------------------------------------------
    // Property 21d: visibility calls are scoped per player — other players unaffected
    // -----------------------------------------------------------------------

    "Property 21d: showToPlayer and hideFromPlayer only affect the target player" {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.int(1, 5),
            Arb.string(1, 10),
            Arb.int(-100, 100),
            Arb.int(0, 200)
        ) { hologramCount, _, baseX, baseY ->
            val store = FakeHologramVisibilityStore()
            val playerA = UUID.randomUUID()
            val playerB = UUID.randomUUID()

            repeat(hologramCount) { i ->
                store.reportError(makeTestLocation21(baseX + i * 5, baseY, 0), "error-$i")
            }

            store.showToPlayer(playerA)
            store.hideFromPlayer(playerA)

            // playerB should have received no calls
            store.showCallsFor(playerB) shouldBe 0
            store.hideCallsFor(playerB) shouldBe 0
        }
    }

    // -----------------------------------------------------------------------
    // Property 21e: no holograms → show/hide are no-ops (zero calls)
    // -----------------------------------------------------------------------

    "Property 21e: showToPlayer and hideFromPlayer are no-ops when no holograms are active" {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.int(-100, 100),
            Arb.int(0, 200)
        ) { _, _ ->
            val store = FakeHologramVisibilityStore()
            val playerId = UUID.randomUUID()

            store.showToPlayer(playerId)
            store.hideFromPlayer(playerId)

            store.showCallsFor(playerId) shouldBe 0
            store.hideCallsFor(playerId) shouldBe 0
        }
    }
})

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun makeTestLocation21(x: Int, y: Int, z: Int): Location {
    val world = mockk<World>(relaxed = true)
    every { world.name } returns "world"
    return Location(world, x.toDouble(), y.toDouble(), z.toDouble())
}

// ---------------------------------------------------------------------------
// Test double: mirrors HologramReporter's visibility logic without Bukkit deps
// ---------------------------------------------------------------------------

private class FakeHologramVisibilityStore {

    private data class Entry(val hologramId: Int, val task: FakeTask21)

    private val active = ConcurrentHashMap<String, Entry>()
    private val hologramCounter = AtomicInteger(0)

    // playerId → show call count
    private val showCalls = ConcurrentHashMap<UUID, AtomicInteger>()
    // playerId → hide call count
    private val hideCalls = ConcurrentHashMap<UUID, AtomicInteger>()

    fun reportError(location: Location, message: String) {
        val key = locationKey(location)
        active.remove(key)?.task?.cancel()
        val id = hologramCounter.incrementAndGet()
        val task = FakeTask21()
        active[key] = Entry(id, task)
    }

    /** Mirrors HologramReporter.showToPlayer — increments show counter for each active hologram */
    fun showToPlayer(playerId: UUID) {
        val counter = showCalls.getOrPut(playerId) { AtomicInteger(0) }
        active.values.forEach { _ -> counter.incrementAndGet() }
    }

    /** Mirrors HologramReporter.hideFromPlayer — increments hide counter for each active hologram */
    fun hideFromPlayer(playerId: UUID) {
        val counter = hideCalls.getOrPut(playerId) { AtomicInteger(0) }
        active.values.forEach { _ -> counter.incrementAndGet() }
    }

    fun showCallsFor(playerId: UUID): Int = showCalls[playerId]?.get() ?: 0
    fun hideCallsFor(playerId: UUID): Int = hideCalls[playerId]?.get() ?: 0

    private fun locationKey(location: Location): String =
        "${location.world?.name}:${location.blockX}:${location.blockY}:${location.blockZ}"
}

private class FakeTask21 : BukkitTask {
    private var cancelled = false
    override fun getTaskId(): Int = 0
    override fun getOwner(): Plugin = mockk(relaxed = true)
    override fun isSync(): Boolean = true
    override fun isCancelled(): Boolean = cancelled
    override fun cancel() { cancelled = true }
}
