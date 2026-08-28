package com.opencreativeplus.core.watchdog

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.execution.ScriptFrame
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for the Watchdog anti-lag system.
 *
 * Covers:
 * - Legacy checkExecution path (operation limit, TPS, memory)
 * - checkTps() direct TPS threshold checks (Requirements 9.2, 9.3)
 * - areScriptsPaused() state (Requirements 9.2, 9.3)
 * - checkFrame() operation limit + onCancel callback (Requirements 9.1, 9.4)
 * - checkFrame() memory limit + onCancel callback
 * - getStats() / WatchdogStats snapshot (Requirement 9.6)
 *
 * Validates: Requirements 9.1, 9.2, 9.4
 */
class WatchdogTest : FreeSpec({

    // -------------------------------------------------------------------------
    // Shared helpers — each test gets a fresh Watchdog via closure
    // -------------------------------------------------------------------------

    fun newMonitor(): TPSMonitor = mockk {
        every { getCurrentTPS() } returns 20.0
    }

    fun newWatchdog(monitor: TPSMonitor = newMonitor()): Watchdog = Watchdog(monitor)

    fun context(plotId: UUID = UUID.randomUUID(), ops: Int = 0): ExecutionContext =
        mockk<ExecutionContext>(relaxed = true) {
            every { this@mockk.plotId } returns plotId
            every { operationCount } returns AtomicInteger(ops)
        }

    /** Create a minimal fake [ScriptFrame] without needing a running Bukkit server. */
    fun frame(plotId: UUID = UUID.randomUUID(), ops: Int = 0): ScriptFrame =
        ScriptFrame(
            frameId = UUID.randomUUID(),
            plotId = plotId,
            player = null,
            script = mockk<CompiledScript>(relaxed = true),
            context = context(plotId = plotId, ops = ops)
        )

    // =========================================================================
    // Operation limit enforcement  (Req 9.1)
    // =========================================================================

    "Legacy checkExecution — operation limit" - {

        "passes when operation count is below limit" {
            val wd = newWatchdog()
            val ctx = context(ops = Watchdog.MAX_OPERATIONS - 1)
            shouldNotThrowAny { wd.checkExecution(ctx) }
        }

        "throws WatchdogException when operation count equals limit" {
            val wd = newWatchdog()
            val ctx = context(ops = Watchdog.MAX_OPERATIONS)

            val ex = shouldThrow<WatchdogException> { wd.checkExecution(ctx) }
            ex.message!! shouldContain "Operation limit exceeded"
        }

        "throws WatchdogException when operation count exceeds limit" {
            val wd = newWatchdog()
            val ctx = context(ops = Watchdog.MAX_OPERATIONS + 500)
            shouldThrow<WatchdogException> { wd.checkExecution(ctx) }
        }

        "MAX_OPERATIONS constant is 10000" {
            Watchdog.MAX_OPERATIONS shouldBe 10_000
        }
    }

    // =========================================================================
    // TPS pause / resume logic via checkExecution  (Req 9.2, 9.3)
    // =========================================================================

    "Legacy checkExecution — TPS pause / resume" - {

        "passes when TPS is above MIN_TPS" {
            val monitor = newMonitor()
            val wd = newWatchdog(monitor)
            val ctx = context()

            shouldNotThrowAny { wd.checkExecution(ctx) }
            wd.areScriptsPaused() shouldBe false
        }

        "throws and pauses scripts when TPS drops below MIN_TPS" {
            val monitor: TPSMonitor = mockk {
                every { getCurrentTPS() } returns 14.9
            }
            val wd = newWatchdog(monitor)
            val ctx = context()

            val ex = shouldThrow<WatchdogException> { wd.checkExecution(ctx) }
            wd.areScriptsPaused() shouldBe true
            ex.message!! shouldContain "TPS"
        }

        "throws while scripts are paused and TPS has not recovered above RESUME_TPS" {
            val monitor: TPSMonitor = mockk {
                every { getCurrentTPS() } returnsMany listOf(14.0, 17.9)
            }
            val wd = newWatchdog(monitor)
            val ctx = context()

            // Trigger initial pause
            shouldThrow<WatchdogException> { wd.checkExecution(ctx) }
            wd.areScriptsPaused() shouldBe true

            // TPS still below RESUME_TPS — remains paused
            shouldThrow<WatchdogException> { wd.checkExecution(ctx) }
            wd.areScriptsPaused() shouldBe true
        }

        "resumes scripts when TPS recovers above RESUME_TPS" {
            val monitor: TPSMonitor = mockk {
                every { getCurrentTPS() } returnsMany listOf(10.0, 18.1)
            }
            val wd = newWatchdog(monitor)
            val ctx = context()

            // Trigger initial pause
            shouldThrow<WatchdogException> { wd.checkExecution(ctx) }
            wd.areScriptsPaused() shouldBe true

            // TPS recovers — scripts resume
            shouldNotThrowAny { wd.checkExecution(ctx) }
            wd.areScriptsPaused() shouldBe false
        }

        "scripts resume at exactly RESUME_TPS boundary (>= condition)" {
            val monitor: TPSMonitor = mockk {
                every { getCurrentTPS() } returnsMany listOf(10.0, Watchdog.RESUME_TPS)
            }
            val wd = newWatchdog(monitor)
            val ctx = context()

            shouldThrow<WatchdogException> { wd.checkExecution(ctx) }

            shouldNotThrowAny { wd.checkExecution(ctx) }
            wd.areScriptsPaused() shouldBe false
        }

        "TPS constants have correct values" {
            Watchdog.MIN_TPS shouldBe 15.0
            Watchdog.RESUME_TPS shouldBe 18.0
        }
    }

    // =========================================================================
    // Memory limit enforcement  (Req 9.1, 9.4)
    // =========================================================================

    "Legacy checkExecution — memory limit" - {

        "passes when memory usage is below limit" {
            val wd = newWatchdog()
            val plotId = UUID.randomUUID()
            val ctx = context(plotId = plotId)
            wd.trackMemoryAllocation(plotId, Watchdog.MAX_MEMORY_BYTES - 1)

            shouldNotThrowAny { wd.checkExecution(ctx) }
        }

        "throws when memory usage exceeds 50 MB" {
            val wd = newWatchdog()
            val plotId = UUID.randomUUID()
            val ctx = context(plotId = plotId)
            wd.trackMemoryAllocation(plotId, Watchdog.MAX_MEMORY_BYTES + 1)

            val ex = shouldThrow<WatchdogException> { wd.checkExecution(ctx) }
            ex.message!! shouldContain "Memory limit exceeded"
            ex.message!! shouldContain plotId.toString()
        }

        "trackMemoryAllocation accumulates across multiple calls" {
            val wd = newWatchdog()
            val plotId = UUID.randomUUID()
            val chunk = Watchdog.MAX_MEMORY_BYTES / 3

            wd.trackMemoryAllocation(plotId, chunk)
            wd.trackMemoryAllocation(plotId, chunk)
            wd.trackMemoryAllocation(plotId, chunk)

            wd.getMemoryUsage(plotId) shouldBe chunk * 3
        }

        "resetMemoryTracking removes plot memory entry" {
            val wd = newWatchdog()
            val plotId = UUID.randomUUID()
            wd.trackMemoryAllocation(plotId, Watchdog.MAX_MEMORY_BYTES + 1)

            wd.resetMemoryTracking(plotId)

            wd.getMemoryUsage(plotId) shouldBe 0L
            val ctx = context(plotId = plotId)
            shouldNotThrowAny { wd.checkExecution(ctx) }
        }

        "memory tracking is isolated per plot" {
            val wd = newWatchdog()
            val plotId1 = UUID.randomUUID()
            val plotId2 = UUID.randomUUID()

            wd.trackMemoryAllocation(plotId1, Watchdog.MAX_MEMORY_BYTES + 1)
            wd.trackMemoryAllocation(plotId2, 1024L)

            shouldThrow<WatchdogException> { wd.checkExecution(context(plotId = plotId1)) }
            shouldNotThrowAny { wd.checkExecution(context(plotId = plotId2)) }
        }

        "passes for plot with no memory tracked" {
            val wd = newWatchdog()
            val ctx = context()
            shouldNotThrowAny { wd.checkExecution(ctx) }
        }

        "MAX_MEMORY_BYTES constant equals 50 MB" {
            Watchdog.MAX_MEMORY_BYTES shouldBe 50L * 1024 * 1024
        }
    }

    // =========================================================================
    // checkTps() — direct TPS threshold checks  (Requirements 9.2, 9.3)
    // =========================================================================

    "checkTps thresholds" - {

        "does not throw when TPS is above MIN_TPS and scripts are not paused" {
            val wd = newWatchdog()
            shouldNotThrowAny { wd.checkTps() }
            wd.areScriptsPaused() shouldBe false
        }

        "throws and pauses scripts when TPS drops below MIN_TPS" {
            val monitor: TPSMonitor = mockk {
                every { getCurrentTPS() } returns Watchdog.MIN_TPS - 0.1
            }
            val wd = newWatchdog(monitor)

            val ex = shouldThrow<WatchdogException> { wd.checkTps() }
            wd.areScriptsPaused() shouldBe true
            ex.message!! shouldContain "TPS"
        }

        "does not throw when TPS is exactly at MIN_TPS boundary (not below)" {
            val monitor: TPSMonitor = mockk {
                every { getCurrentTPS() } returns Watchdog.MIN_TPS
            }
            val wd = newWatchdog(monitor)

            shouldNotThrowAny { wd.checkTps() }
            wd.areScriptsPaused() shouldBe false
        }

        "areScriptsPaused returns false initially" {
            val wd = newWatchdog()
            wd.areScriptsPaused() shouldBe false
        }

        "keeps scripts paused when TPS is between MIN_TPS and RESUME_TPS" {
            val monitor: TPSMonitor = mockk {
                every { getCurrentTPS() } returnsMany listOf(10.0, Watchdog.RESUME_TPS - 0.1)
            }
            val wd = newWatchdog(monitor)

            shouldThrow<WatchdogException> { wd.checkTps() }
            wd.areScriptsPaused() shouldBe true

            shouldThrow<WatchdogException> { wd.checkTps() }
            wd.areScriptsPaused() shouldBe true
        }

        "resumes scripts when TPS recovers above RESUME_TPS" {
            val monitor: TPSMonitor = mockk {
                every { getCurrentTPS() } returnsMany listOf(10.0, Watchdog.RESUME_TPS + 0.1)
            }
            val wd = newWatchdog(monitor)

            shouldThrow<WatchdogException> { wd.checkTps() }
            wd.areScriptsPaused() shouldBe true

            shouldNotThrowAny { wd.checkTps() }
            wd.areScriptsPaused() shouldBe false
        }

        "resumes scripts when TPS is exactly at RESUME_TPS (>= condition)" {
            val monitor: TPSMonitor = mockk {
                every { getCurrentTPS() } returnsMany listOf(10.0, Watchdog.RESUME_TPS)
            }
            val wd = newWatchdog(monitor)

            shouldThrow<WatchdogException> { wd.checkTps() }

            shouldNotThrowAny { wd.checkTps() }
            wd.areScriptsPaused() shouldBe false
        }

        "areScriptsPaused returns true after TPS drops below MIN_TPS" {
            val monitor: TPSMonitor = mockk {
                every { getCurrentTPS() } returns 5.0
            }
            val wd = newWatchdog(monitor)

            shouldThrow<WatchdogException> { wd.checkTps() }
            wd.areScriptsPaused() shouldBe true
        }

        "areScriptsPaused returns false after TPS recovers above RESUME_TPS" {
            val monitor: TPSMonitor = mockk {
                every { getCurrentTPS() } returnsMany listOf(5.0, 20.0)
            }
            val wd = newWatchdog(monitor)

            shouldThrow<WatchdogException> { wd.checkTps() }
            shouldNotThrowAny { wd.checkTps() }
            wd.areScriptsPaused() shouldBe false
        }
    }

    // =========================================================================
    // checkFrame() — operation limit and onCancel callback  (Requirements 9.1, 9.4)
    // =========================================================================

    "checkFrame" - {

        "does not throw and does not call onCancel when operations are below limit" {
            val wd = newWatchdog()
            val f = frame(ops = Watchdog.MAX_OPERATIONS - 1)
            var cancelled = false

            wd.checkFrame(f) { cancelled = true }

            cancelled shouldBe false
        }

        "throws WatchdogException and calls onCancel when operation count equals MAX_OPERATIONS" {
            val wd = newWatchdog()
            val f = frame(ops = Watchdog.MAX_OPERATIONS)
            var cancelled = false

            val ex = shouldThrow<WatchdogException> { wd.checkFrame(f) { cancelled = true } }

            cancelled shouldBe true
            ex.message!! shouldContain "Operation limit exceeded"
        }

        "throws WatchdogException when operation count exceeds MAX_OPERATIONS" {
            val wd = newWatchdog()
            val f = frame(ops = Watchdog.MAX_OPERATIONS + 100)

            shouldThrow<WatchdogException> { wd.checkFrame(f) {} }
        }

        "calls onCancel with the exact frame that exceeded the limit" {
            val wd = newWatchdog()
            val f = frame(ops = Watchdog.MAX_OPERATIONS)
            var cancelledFrame: ScriptFrame? = null

            shouldThrow<WatchdogException> { wd.checkFrame(f) { cancelledFrame = it } }

            cancelledFrame shouldBe f
        }

        "exception message includes frameId and plotId" {
            val wd = newWatchdog()
            val plotId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            val f = frame(plotId = plotId, ops = Watchdog.MAX_OPERATIONS)

            val ex = shouldThrow<WatchdogException> { wd.checkFrame(f) {} }

            ex.message!! shouldContain f.frameId.toString()
            ex.message!! shouldContain plotId.toString()
        }

        "throws for memory limit exceeded and calls onCancel" {
            val wd = newWatchdog()
            val plotId = UUID.randomUUID()
            val f = frame(plotId = plotId, ops = 0)
            wd.trackMemoryAllocation(plotId, Watchdog.MAX_MEMORY_BYTES + 1)

            var cancelled = false
            val ex = shouldThrow<WatchdogException> { wd.checkFrame(f) { cancelled = true } }

            cancelled shouldBe true
            ex.message!! shouldContain "Memory limit exceeded"
        }

        "does not call onCancel when frame is within both operation and memory limits" {
            val wd = newWatchdog()
            val f = frame(ops = 0)
            var cancelled = false

            wd.checkFrame(f) { cancelled = true }

            cancelled shouldBe false
        }

        "operation limit is checked before memory limit" {
            // When both limits are exceeded, the exception should mention operation limit first
            val wd = newWatchdog()
            val plotId = UUID.randomUUID()
            val f = frame(plotId = plotId, ops = Watchdog.MAX_OPERATIONS)
            wd.trackMemoryAllocation(plotId, Watchdog.MAX_MEMORY_BYTES + 1)

            val ex = shouldThrow<WatchdogException> { wd.checkFrame(f) {} }
            ex.message!! shouldContain "Operation limit exceeded"
        }
    }

    // =========================================================================
    // getStats() / WatchdogStats snapshot  (Requirement 9.6)
    // =========================================================================

    "getStats" - {

        "returns zero values on a fresh Watchdog" {
            val wd = newWatchdog()
            val stats = wd.getStats()

            stats.activeFrames shouldBe 0
            stats.totalCpuMs shouldBe 0L
            stats.trackedMemoryBytes shouldBe 0L
        }

        "activeFrames reflects incrementActiveFrames calls" {
            val wd = newWatchdog()
            wd.incrementActiveFrames()
            wd.incrementActiveFrames()

            wd.getStats().activeFrames shouldBe 2
        }

        "activeFrames reflects decrementActiveFrames calls" {
            val wd = newWatchdog()
            wd.incrementActiveFrames()
            wd.incrementActiveFrames()
            wd.incrementActiveFrames()
            wd.decrementActiveFrames()

            wd.getStats().activeFrames shouldBe 2
        }

        "totalCpuMs reflects recordTickCpu" {
            val wd = newWatchdog()
            wd.recordTickCpu(35L)

            wd.getStats().totalCpuMs shouldBe 35L
        }

        "totalCpuMs is overwritten by subsequent recordTickCpu calls" {
            val wd = newWatchdog()
            wd.recordTickCpu(35L)
            wd.recordTickCpu(12L)

            wd.getStats().totalCpuMs shouldBe 12L
        }

        "trackedMemoryBytes sums all plot allocations" {
            val wd = newWatchdog()
            val plotA = UUID.randomUUID()
            val plotB = UUID.randomUUID()
            wd.trackMemoryAllocation(plotA, 1_024L)
            wd.trackMemoryAllocation(plotB, 2_048L)

            wd.getStats().trackedMemoryBytes shouldBe 3_072L
        }

        "trackedMemoryBytes decreases after resetMemoryTracking" {
            val wd = newWatchdog()
            val plotId = UUID.randomUUID()
            wd.trackMemoryAllocation(plotId, 1_000L)
            wd.getStats().trackedMemoryBytes shouldBe 1_000L

            wd.resetMemoryTracking(plotId)
            wd.getStats().trackedMemoryBytes shouldBe 0L
        }

        "WatchdogStats data class has correct equality semantics" {
            val s1 = WatchdogStats(activeFrames = 3, totalCpuMs = 20L, trackedMemoryBytes = 512L)
            val s2 = WatchdogStats(activeFrames = 3, totalCpuMs = 20L, trackedMemoryBytes = 512L)

            (s1 == s2) shouldBe true
            s1.activeFrames shouldBe 3
            s1.totalCpuMs shouldBe 20L
            s1.trackedMemoryBytes shouldBe 512L
        }

        "returns a snapshot — subsequent mutations do not affect the returned object" {
            val wd = newWatchdog()
            wd.incrementActiveFrames()
            val snapshot = wd.getStats()
            wd.incrementActiveFrames()

            snapshot.activeFrames shouldBe 1
        }
    }
})
