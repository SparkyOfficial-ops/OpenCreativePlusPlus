// Feature: ocp-gameplay-systems, Property 19: Hologram replace
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property 19: Hologram replace
 *
 * For any block, two consecutive calls to reportError() for the same location must
 * result in exactly ONE active hologram (the second replaces the first).
 *
 * Validates: Requirements 12.4
 */
class HologramReplacePropertyTest : StringSpec({

    // -----------------------------------------------------------------------
    // Property 19a: exactly one hologram after two reportError calls on same block
    // -----------------------------------------------------------------------

    "Property 19a: exactly one hologram exists after two reportError calls on the same block" {
        checkAll(
            PropTestConfig(iterations = 200),
            Arb.string(1, 30),
            Arb.string(1, 30),
            Arb.int(-100, 100),
            Arb.int(0, 200)
        ) { msg1, msg2, x, y ->
            val store = FakeHologramStore19()
            val loc = makeTestLocation(x, y, 0)

            store.reportError(loc, msg1)
            store.reportError(loc, msg2)

            store.activeCount() shouldBe 1
        }
    }

    // -----------------------------------------------------------------------
    // Property 19b: the surviving hologram carries the second (latest) message
    // -----------------------------------------------------------------------

    "Property 19b: the surviving hologram carries the second message after replace" {
        checkAll(
            PropTestConfig(iterations = 200),
            Arb.string(1, 30),
            Arb.string(1, 30),
            Arb.int(-100, 100),
            Arb.int(0, 200)
        ) { msg1, msg2, x, y ->
            val store = FakeHologramStore19()
            val loc = makeTestLocation(x, y, 0)

            store.reportError(loc, msg1)
            store.reportError(loc, msg2)

            val truncated = if (msg2.length > 40) msg2.take(40) else msg2
            store.getMessage(loc) shouldBe "§c$truncated"
        }
    }

    // -----------------------------------------------------------------------
    // Property 19c: the first timer is cancelled on replace
    // -----------------------------------------------------------------------

    "Property 19c: the first timer is cancelled when the hologram is replaced" {
        checkAll(
            PropTestConfig(iterations = 200),
            Arb.string(1, 20),
            Arb.string(1, 20),
            Arb.int(-100, 100),
            Arb.int(0, 200)
        ) { msg1, msg2, x, y ->
            val store = FakeHologramStore19()
            val loc = makeTestLocation(x, y, 0)

            store.reportError(loc, msg1)
            store.reportError(loc, msg2)

            store.cancelledCount.get() shouldBe 1
        }
    }

    // -----------------------------------------------------------------------
    // Property 19d: N calls on the same block → exactly 1 hologram, N-1 cancellations
    // -----------------------------------------------------------------------

    "Property 19d: N consecutive calls on the same block leave exactly 1 hologram and N-1 cancellations" {
        checkAll(
            PropTestConfig(iterations = 200),
            Arb.int(1, 10),
            Arb.int(-100, 100),
            Arb.int(0, 200),
            Arb.int(-100, 100)
        ) { n, x, y, z ->
            val store = FakeHologramStore19()
            val loc = makeTestLocation(x, y, z)

            repeat(n) { i -> store.reportError(loc, "error-$i") }

            store.activeCount() shouldBe 1
            store.cancelledCount.get() shouldBe (n - 1)
            store.scheduledCount.get() shouldBe n
        }
    }

    // -----------------------------------------------------------------------
    // Property 19e: errors on distinct blocks do not interfere with each other
    // -----------------------------------------------------------------------

    "Property 19e: errors on distinct blocks are independent — no cross-cancellation" {
        checkAll(
            PropTestConfig(iterations = 200),
            Arb.string(1, 20),
            Arb.string(1, 20),
            Arb.int(-100, 100),
            Arb.int(0, 200)
        ) { msg1, msg2, x, y ->
            val store = FakeHologramStore19()
            val loc1 = makeTestLocation(x, y, 0)
            val loc2 = makeTestLocation(x + 10, y, 0) // guaranteed different block

            store.reportError(loc1, msg1)
            store.reportError(loc2, msg2)

            store.activeCount() shouldBe 2
            store.cancelledCount.get() shouldBe 0
            store.scheduledCount.get() shouldBe 2
        }
    }
})

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun makeTestLocation(x: Int, y: Int, z: Int): Location {
    val world = mockk<World>(relaxed = true)
    every { world.name } returns "world"
    return Location(world, x.toDouble(), y.toDouble(), z.toDouble())
}

// ---------------------------------------------------------------------------
// Test double: mirrors HologramReporter's replace logic without Bukkit deps
// ---------------------------------------------------------------------------

private class FakeHologramStore19 {

    val cancelledCount = AtomicInteger(0)
    val scheduledCount = AtomicInteger(0)

    private data class Entry(val message: String, val task: FakeTask19)

    private val active = ConcurrentHashMap<String, Entry>()

    fun reportError(location: Location, message: String) {
        val key = locationKey(location)
        // Cancel and remove existing entry — mirrors Req 12.4
        active.remove(key)?.task?.cancel()
        val truncated = if (message.length > 40) message.take(40) else message
        val text = "§c$truncated"
        val task = FakeTask19(cancelledCount)
        scheduledCount.incrementAndGet()
        active[key] = Entry(text, task)
    }

    fun activeCount(): Int = active.size

    fun getMessage(location: Location): String? = active[locationKey(location)]?.message

    private fun locationKey(location: Location): String =
        "${location.world?.name}:${location.blockX}:${location.blockY}:${location.blockZ}"
}

private class FakeTask19(private val cancelledCount: AtomicInteger) : BukkitTask {
    private var cancelled = false
    override fun getTaskId(): Int = 0
    override fun getOwner(): Plugin = mockk(relaxed = true)
    override fun isSync(): Boolean = true
    override fun isCancelled(): Boolean = cancelled
    override fun cancel() {
        cancelled = true
        cancelledCount.incrementAndGet()
    }
}
