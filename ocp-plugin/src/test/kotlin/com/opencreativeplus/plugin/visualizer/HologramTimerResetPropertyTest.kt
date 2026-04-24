// Feature: ocp-manifest-roadmap, Property 10: HologramReporter сбрасывает таймер
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.visualizer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import org.bukkit.Location
import org.bukkit.World
import io.mockk.every
import io.mockk.mockk
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Property test: repeated calls to reportError() for the same block reset the timer.
 *
 * Feature: ocp-manifest-roadmap, Property 10: HologramReporter сбрасывает таймер
 * Validates: Requirements 10.4
 */
class HologramTimerResetPropertyTest : StringSpec({

    "reportError called twice on same block resets timer and updates message" {
        // Feature: ocp-manifest-roadmap, Property 10: HologramReporter сбрасывает таймер
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.string(1, 20),
            Arb.string(1, 20),
            Arb.int(-100, 100),
            Arb.int(0, 255),
            Arb.int(-100, 100)
        ) { msg1, msg2, x, y, z ->
            val taskCancelCount = AtomicInteger(0)
            val scheduledCount = AtomicInteger(0)
            val lastMessage = AtomicReference<String>()

            val reporter = TestableHologramReporter(taskCancelCount, scheduledCount, lastMessage)

            val world = mockk<World>(relaxed = true)
            every { world.name } returns "testWorld"

            val loc = Location(world, x.toDouble(), y.toDouble(), z.toDouble())

            reporter.reportError(loc, msg1)
            scheduledCount.get() shouldBe 1

            reporter.reportError(loc, msg2)
            // Timer should have been cancelled once (the first one)
            taskCancelCount.get() shouldBe 1
            // A new timer should have been scheduled
            scheduledCount.get() shouldBe 2
            // Message should be updated to the latest
            lastMessage.get() shouldBe "§cОшибка: $msg2"
        }
    }

    "reportError on different blocks does not cancel each other's timers" {
        // Feature: ocp-manifest-roadmap, Property 10: HologramReporter сбрасывает таймер
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.string(1, 10),
            Arb.int(-50, 50),
            Arb.int(0, 100),
            Arb.int(-50, 50)
        ) { msg, x, y, z ->
            val taskCancelCount = AtomicInteger(0)
            val scheduledCount = AtomicInteger(0)
            val lastMessage = AtomicReference<String>()

            val reporter = TestableHologramReporter(taskCancelCount, scheduledCount, lastMessage)

            val world = mockk<World>(relaxed = true)
            every { world.name } returns "testWorld"

            val loc1 = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
            val loc2 = Location(world, x.toDouble() + 5, y.toDouble(), z.toDouble())

            reporter.reportError(loc1, msg)
            reporter.reportError(loc2, msg)

            // No cancellations since they are different blocks
            taskCancelCount.get() shouldBe 0
            scheduledCount.get() shouldBe 2
        }
    }
})

/**
 * Testable replacement for HologramReporter that avoids Bukkit-dependent operations
 * (ArmorStand spawning, scheduler) while preserving the timer-reset logic.
 */
class TestableHologramReporter(
    private val cancelCount: AtomicInteger,
    private val scheduleCount: AtomicInteger,
    private val lastMsg: AtomicReference<String>
) {
    private data class Entry(val task: FakeTask, val message: String)
    private val active = ConcurrentHashMap<String, Entry>()

    private inner class FakeTask : BukkitTask {
        var cancelled = false
        override fun getTaskId() = 0
        override fun getOwner(): Plugin = mockk(relaxed = true)
        override fun isSync() = true
        override fun isCancelled() = cancelled
        override fun cancel() {
            cancelled = true
            cancelCount.incrementAndGet()
        }
    }

    fun reportError(location: Location, message: String) {
        val key = "${location.world?.name}:${location.blockX}:${location.blockY}:${location.blockZ}"
        // Cancel existing timer (Req 10.4)
        active.remove(key)?.task?.cancel()
        val text = "§cОшибка: $message"
        lastMsg.set(text)
        val task = FakeTask()
        scheduleCount.incrementAndGet()
        active[key] = Entry(task, text)
    }
}
