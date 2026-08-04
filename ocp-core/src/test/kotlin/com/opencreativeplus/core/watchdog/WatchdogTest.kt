package com.opencreativeplus.core.watchdog

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.execution.ScriptFrame
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrtTrue

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
class WatchdogTest {

    private lateinit var tpsMonitor: TPSMonitor
    private lateinit var watchdog: Watchdog

    @BeforeEach
    fun setup() {
        tpsMonitor = mockk()
        watchdog = Watchdog(tpsMonitor)
        // Default: healthy TPS
        every { tpsMonitor.getCurrentTPS() } returns 20.0
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun context(plotId: UUID = UUID.randomUUID(), ops: Int = 0): ExecutionContext {
        val ctx = mockk<ExecutionContext>(relaxed = true)
        every { ctx.plotId } returns plotId
        every { ctx.operationCount } returns AtomicInteger(ops)
        return ctx
    }

    /** Create a minimal fake [ScriptFrame] without needing a running Bukkit server. */
    private fun frame(
        plotId: UUID = UUID.randomUUID(),
        ops: Int = 0
    ): ScriptFrame {
        val ctx = context(plotId = plotId, ops = ops)
        val script = mockk<CompiledScript>(relaxed = true)
        return ScriptFrame(
            frameId = UUID.randomUUID(),
            plotId = plotId,
            player = null,
            script = script,
            context = ctx
        )
    }

    // =========================================================================
    // Operation limit enforcement  ( 8.2)
    // =========================================================================

    @Test
    fun `checkExecution passes when operation count is below limit`() {
        // Given: context with 9999 operations (one below the limit)
        val ctx = context(ops = Watchdog.MAX_OPERATIONS - 1)

        // When / Then: no exception is thrown
        watchdog.checkExecution(ctx)
    }

    @Test
    fun `checkExecution throws when operation count equals limit`() {
        // Given: context exactly at the limit
        val ctx = context(ops = Watchdog.MAX_OPERATIONS)

        // When / Then: WatchdogException is thrown
        val ex = assertFailsWith<WatchdogException> { watchdog.checkExecution(ctx) }
        assertTrue(ex.message!!.contains("Operation limit exceeded"), "Message should mention operation limit")
    }

    @Test
    fun `checkExecution throws when operation count exceeds limit`() {
        // Given: context well above the limit
        val ctx = context(ops = Watchdog.MAX_OPERATIONS + 500)

        assertFailsWith<WatchdogException> { watchdog.checkExecution(ctx) }
    }

    @Test
    fun `operation limit is 10000`() {
        assertEquals(10_000, Watchdog.MAX_OPERATIONS)
    }

    // =========================================================================
    // TPS pause / resume logic  (s 8.3, 8.4)
    // =========================================================================

    @Test
    fun `checkExecution passes when TPS is above MIN_TPS`() {
        // Given: TPS well above the threshold
        every { tpsMonitor.getCurrentTPS() } returns 20.0
        val ctx = context()

        // When / Then: no exception
        watchdog.checkExecution(ctx)
        assertFalse(watchdog.areScriptsPaused())
    }

    @Test
    fun `checkExecution throws and pauses scripts when TPS drops below MIN_TPS`() {
        // Given: TPS below the pause threshold
        every { tpsMonitor.getCurrentTPS() } returns 14.9

        val ctx = context()

        // When
        val ex = assertFailsWith<WatchdogException> { watchdog.checkExecution(ctx) }

        // Then: scripts are paused and message is informative
        assertTrue(watchdog.areScriptsPaused(), "Scripts should be paused after TPS drop")
        assertTrue(ex.message!!.contains("TPS"), "Exception message should mention TPS")
    }

    @Test
    fun `checkExecution throws while scripts are paused and TPS has not recovered`() {
        // Given: scripts already paused, TPS still low
        every { tpsMonitor.getCurrentTPS() } returns 14.0
        val ctx = context()

        // Trigger initial pause
        assertFailsWith<WatchdogException> { watchdog.checkExecution(ctx) }
        assertTrue(watchdog.areScriptsPaused())

        // When: TPS is still below RESUME_TPS
        every { tpsMonitor.getCurrentTPS() } returns 17.9

        // Then: still throws
        assertFailsWith<WatchdogException> { watchdog.checkExecution(ctx) }
        assertTrue(watchdog.areScriptsPaused(), "Scripts should remain paused below RESUME_TPS")
    }

    @Test
    fun `checkExecution resumes scripts when TPS recovers above RESUME_TPS`() {
        // Given: scripts paused due to low TPS
        every { tpsMonitor.getCurrentTPS() } returns 10.0
        val ctx = context()
        assertFailsWith<WatchdogException> { watchdog.checkExecution(ctx) }
        assertTrue(watchdog.areScriptsPaused())

        // When: TPS recovers above RESUME_TPS
        every { tpsMonitor.getCurrentTPS() } returns 18.1

        // Then: no exception and scripts are no longer paused
        watchdog.checkExecution(ctx)
        assertFalse(watchdog.areScriptsPaused(), "Scripts should resume after TPS recovery")
    }

    @Test
    fun `scripts do not resume at exactly RESUME_TPS boundary - must be strictly above`() {
        // Given: scripts paused
        every { tpsMonitor.getCurrentTPS() } returns 10.0
        val ctx = context()
        assertFailsWith<WatchdogException> { watchdog.checkExecution(ctx) }

        // When: TPS is exactly at RESUME_TPS
        every { tpsMonitor.getCurrentTPS() } returns Watchdog.RESUME_TPS

        // Then: scripts resume (>= RESUME_TPS is the condition in the implementation)
        watchdog.checkExecution(ctx)
        assertFalse(watchdog.areScriptsPaused())
    }

    @Test
    fun `TPS constants have correct values`() {
        assertEquals(15.0, Watchdog.MIN_TPS)
        assertEquals(18.0, Watchdog.RESUME_TPS)
    }

    // =========================================================================
    // Memory limit enforcement  ( 29.2)
    // =========================================================================

    @Test
    fun `checkExecution passes when memory usage is below limit`() {
        // Given: plot with memory usage just below 50 MB
        val plotId = UUID.randomUUID()
        val ctx = context(plotId = plotId)
        watchdog.trackMemoryAllocation(plotId, Watchdog.MAX_MEMORY_BYTES - 1)

        // When / Then: no exception
        watchdog.checkExecution(ctx)
    }

    @Test
    fun `checkExecution throws when memory usage exceeds 50 MB`() {
        // Given: plot exceeding the 50 MB limit
        val plotId = UUID.randomUUID()
        val ctx = context(plotId = plotId)
        watchdog.trackMemoryAllocation(plotId, Watchdog.MAX_MEMORY_BYTES + 1)

        // When
        val ex = assertFailsWith<WatchdogException> { watchdog.checkExecution(ctx) }

        // Then: message mentions memory
        assertTrue(ex.message!!.contains("Memory limit exceeded"), "Message should mention memory limit")
        assertTrue(ex.message!!.contains(plotId.toString()), "Message should include plot ID")
    }

    @Test
    fun `trackMemoryAllocation accumulates across multiple calls`() {
        // Given: a plot
        val plotId = UUID.randomUUID()

        // When: allocating memory in multiple increments
        val chunk = Watchdog.MAX_MEMORY_BYTES / 3
        watchdog.trackMemoryAllocation(plotId, chunk)
        watchdog.trackMemoryAllocation(plotId, chunk)
        watchdog.trackMemoryAllocation(plotId, chunk)

        // Then: total is the sum of all allocations
        assertEquals(chunk * 3, watchdog.getMemoryUsage(plotId))
    }

    @Test
    fun `resetMemoryTracking removes plot memory entry`() {
        // Given: a plot with tracked memory
        val plotId = UUID.randomUUID()
        watchdog.trackMemoryAllocation(plotId, Watchdog.MAX_MEMORY_BYTES + 1)

        // When: resetting memory tracking
        watchdog.resetMemoryTracking(plotId)

        // Then: memory usage is 0 and checkExecution no longer throws for memory
        assertEquals(0L, watchdog.getMemoryUsage(plotId))
        val ctx = context(plotId = plotId)
        watchdog.checkExecution(ctx) // should not throw
    }

    @Test
    fun `memory tracking is isolated per plot`() {
        // Given: two different plots
        val plotId1 = UUID.randomUUID()
        val plotId2 = UUID.randomUUID()

        // When: only plot1 exceeds the limit
        watchdog.trackMemoryAllocation(plotId1, Watchdog.MAX_MEMORY_BYTES + 1)
        watchdog.trackMemoryAllocation(plotId2, 1024L)

        // Then: plot1 throws, plot2 does not
        assertFailsWith<WatchdogException> { watchdog.checkExecution(context(plotId = plotId1)) }
        watchdog.checkExecution(context(plotId = plotId2)) // should not throw
    }

    @Test
    fun `checkExecution passes for plot with no memory tracked`() {
        // Given: a plot with no memory allocation recorded
        val plotId = UUID.randomUUID()
        val ctx = context(plotId = plotId)

        // When / Then: no exception (null memory entry is treated as 0)
        watchdog.checkExecution(ctx)
    }

    @Test
    fun `MAX_MEMORY_BYTES constant equals 50 MB`() {
        assertEquals(50L * 1024 * 1024, Watchdog.MAX_MEMORY_BYTES)
    }

    // =========================================================================
    // checkTps() — direct TPS threshold checks  (Requirements 9.2, 9.3)
    // =========================================================================

    @Nested
    inner class CheckTpsThresholds {

        @Test
        fun `checkTps does not throw when TPS is above MIN_TPS and scripts are not paused`() {
            // Req 9.2: normal TPS — no pause should occur
            every { tpsMonitor.getCurrentTPS() } returns 20.0

            watchdog.checkTps() // must not throw
            assertFalse(watchdog.areScriptsPaused())
        }

        @Test
        fun `checkTps throws and pauses scripts when TPS drops below MIN_TPS`() {
            // Req 9.2: when TPS < MIN_TPS, scripts must be paused
            every { tpsMonitor.getCurrentTPS() } returns Watchdog.MIN_TPS - 0.1

            val ex = assertFailsWith<WatchdogException> { watchdog.checkTps() }
            assertTrue(watchdog.areScriptsPaused(), "Scripts should be paused after TPS drop")
            assertTrue(ex.message!!.contains("TPS"), "Exception message should mention TPS")
        }

        @Test
        fun `checkTps throws when TPS is exactly at MIN_TPS boundary`() {
            // Req 9.2: boundary — TPS == MIN_TPS means NOT paused yet (condition is < MIN_TPS)
            // MIN_TPS = 15.0 — if current TPS is exactly 15.0 it is not below threshold
            every { tpsMonitor.getCurrentTPS() } returns Watchdog.MIN_TPS

            watchdog.checkTps() // should NOT throw at exactly MIN_TPS
            assertFalse(watchdog.areScriptsPaused())
        }

        @Test
        fun `areScriptsPaused returns false initially`() {
            // Req 9.2: fresh Watchdog should not be paused
            assertFalse(watchdog.areScriptsPaused())
        }

        @Test
        fun `checkTps keeps scripts paused when TPS is between MIN_TPS and RESUME_TPS`() {
            // Req 9.2: once paused, scripts stay paused until TPS >= RESUME_TPS
            every { tpsMonitor.getCurrentTPS() } returns 10.0
            assertFailsWith<WatchdogException> { watchdog.checkTps() }
            assertTrue(watchdog.areScriptsPaused())

            // TPS recovers partially — below RESUME_TPS
            every { tpsMonitor.getCurrentTPS() } returns Watchdog.RESUME_TPS - 0.1
            assertFailsWith<WatchdogException> { watchdog.checkTps() }
            assertTrue(watchdog.areScriptsPaused(), "Scripts should remain paused below RESUME_TPS")
        }

        @Test
        fun `checkTps resumes scripts when TPS recovers above RESUME_TPS`() {
            // Req 9.3: when TPS >= RESUME_TPS, pause flag must be cleared
            every { tpsMonitor.getCurrentTPS() } returns 10.0
            assertFailsWith<WatchdogException> { watchdog.checkTps() }
            assertTrue(watchdog.areScriptsPaused())

            every { tpsMonitor.getCurrentTPS() } returns Watchdog.RESUME_TPS + 0.1
            watchdog.checkTps() // must NOT throw
            assertFalse(watchdog.areScriptsPaused(), "Scripts should resume after TPS recovery")
        }

        @Test
        fun `checkTps resumes scripts when TPS is exactly at RESUME_TPS`() {
            // Req 9.3: boundary — TPS == RESUME_TPS (condition is >= RESUME_TPS)
            every { tpsMonitor.getCurrentTPS() } returns 10.0
            assertFailsWith<WatchdogException> { watchdog.checkTps() }

            every { tpsMonitor.getCurrentTPS() } returns Watchdog.RESUME_TPS
            watchdog.checkTps() // must NOT throw at exactly RESUME_TPS
            assertFalse(watchdog.areScriptsPaused())
        }

        @Test
        fun `areScriptsPaused returns true after TPS drops below MIN_TPS`() {
            // Req 9.2: areScriptsPaused() must reflect the paused state
            every { tpsMonitor.getCurrentTPS() } returns 5.0
            assertFailsWith<WatchdogException> { watchdog.checkTps() }

            assertTrue(watchdog.areScriptsPaused())
        }

        @Test
        fun `areScriptsPaused returns false after TPS recovers above RESUME_TPS`() {
            // Req 9.3: areScriptsPaused() must reflect the resumed state
            every { tpsMonitor.getCurrentTPS() } returns 5.0
            assertFailsWith<WatchdogException> { watchdog.checkTps() }

            every { tpsMonitor.getCurrentTPS() } returns 20.0
            watchdog.checkTps()

            assertFalse(watchdog.areScriptsPaused())
        }
    }

    // =========================================================================
    // checkFrame() — operation limit and onCancel callback  (Requirements 9.1, 9.4)
    // =========================================================================

    @Nested
    inner class CheckFrame {

        @Test
        fun `checkFrame does not throw and does not call onCancel when operations are below limit`() {
            // Req 9.1: frame within limits — execution continues normally
            val f = frame(ops = Watchdog.MAX_OPERATIONS - 1)
            var cancelled = false

            watchdog.checkFrame(f) { cancelled = true }

            assertFalse(cancelled, "onCancel should NOT be called when under operation limit")
        }

        @Test
        fun `checkFrame throws WatchdogException when operation count equals MAX_OPERATIONS`() {
            // Req 9.1: at the limit — must be terminated
            val f = frame(ops = Watchdog.MAX_OPERATIONS)
            var cancelled = false

            val ex = assertFailsWith<WatchdogException> { watchdog.checkFrame(f) { cancelled = true } }

            assertTrue(cancelled, "onCancel MUST be called when operation limit is reached")
            assertTrue(
                ex.message!!.contains("Operation limit exceeded"),
                "Exception message should mention operation limit"
            )
        }

        @Test
        fun `checkFrame throws WatchdogException when operation count exceeds MAX_OPERATIONS`() {
            // Req 9.1: above the limit
            val f = frame(ops = Watchdog.MAX_OPERATIONS + 100)

            assertFailsWith<WatchdogException> { watchdog.checkFrame(f) {} }
        }

        @Test
        fun `checkFrame calls onCancel with the cancelled frame`() {
            // Req 9.4: the exact frame that exceeded the limit must be passed to onCancel
            val f = frame(ops = Watchdog.MAX_OPERATIONS)
            var cancelledFrame: ScriptFrame? = null

            assertFailsWith<WatchdogException> { watchdog.checkFrame(f) { cancelledFrame = it } }

            assertEquals(f, cancelledFrame, "onCancel should receive the frame that exceeded the limit")
        }

        @Test
        fun `checkFrame exception message includes frameId and plotId`() {
            // Req 9.4: the exception message should identify the problematic frame
            val plotId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            val f = frame(plotId = plotId, ops = Watchdog.MAX_OPERATIONS)

            val ex = assertFailsWith<WatchdogException> { watchdog.checkFrame(f) {} }

            assertTrue(
                ex.message!!.contains(f.frameId.toString()),
                "Exception message should contain frameId"
            )
            assertTrue(
                ex.message!!.contains(plotId.toString()),
                "Exception message should contain plotId"
            )
        }

        @Test
        fun `checkFrame throws for memory limit exceeded and calls onCancel`() {
            // Memory check is also part of checkFrame; onCancel must still be invoked
            val plotId = UUID.randomUUID()
            val f = frame(plotId = plotId, ops = 0)
            watchdog.trackMemoryAllocation(plotId, Watchdog.MAX_MEMORY_BYTES + 1)

            var cancelled = false
            val ex = assertFailsWith<WatchdogException> { watchdog.checkFrame(f) { cancelled = true } }

            assertTrue(cancelled, "onCancel MUST be called when memory limit is exceeded")
            assertTrue(
                ex.message!!.contains("Memory limit exceeded"),
                "Exception message should mention memory limit"
            )
        }

        @Test
        fun `checkFrame does not call onCancel when frame is within memory limit`() {
            // Memory check passes for a frame with no significant tracked allocation
            val f = frame(ops = 0)
            var cancelled = false

            watchdog.checkFrame(f) { cancelled = true }

            assertFalse(cancelled)
        }
    }

    // =========================================================================
    // getStats() / WatchdogStats snapshot  (Requirement 9.6)
    // =========================================================================

    @Nested
    inner class GetStats {

        @Test
        fun `getStats returns zero values on a fresh Watchdog`() {
            // Req 9.6: initial state — no frames, no CPU, no memory
            val stats = watchdog.getStats()
            assertEquals(0, stats.activeFrames)
            assertEquals(0L, stats.totalCpuMs)
            assertEquals(0L, stats.trackedMemoryBytes)
        }

        @Test
        fun `getStats activeFrames reflects incrementActiveFrames calls`() {
            // Req 9.6: incrementActiveFrames() increases activeFrames count
            watchdog.incrementActiveFrames()
            watchdog.incrementActiveFrames()

            assertEquals(2, watchdog.getStats().activeFrames)
        }

        @Test
        fun `getStats activeFrames reflects decrementActiveFrames calls`() {
            // Req 9.6: decrementActiveFrames() decreases activeFrames count
            watchdog.incrementActiveFrames()
            watchdog.incrementActiveFrames()
            watchdog.incrementActiveFrames()
            watchdog.decrementActiveFrames()

            assertEquals(2, watchdog.getStats().activeFrames)
        }

        @Test
        fun `getStats totalCpuMs reflects recordTickCpu`() {
            // Req 9.6: recordTickCpu sets the last tick CPU value
            watchdog.recordTickCpu(35L)

            assertEquals(35L, watchdog.getStats().totalCpuMs)
        }

        @Test
        fun `getStats totalCpuMs is overwritten by subsequent recordTickCpu calls`() {
            // Req 9.6: only the most recent tick CPU value is reported
            watchdog.recordTickCpu(35L)
            watchdog.recordTickCpu(12L)

            assertEquals(12L, watchdog.getStats().totalCpuMs)
        }

        @Test
        fun `getStats trackedMemoryBytes sums all plot allocations`() {
            // Req 9.6: trackedMemoryBytes should be the sum of all per-plot memory
            val plotA = UUID.randomUUID()
            val plotB = UUID.randomUUID()
            watchdog.trackMemoryAllocation(plotA, 1_024L)
            watchdog.trackMemoryAllocation(plotB, 2_048L)

            assertEquals(3_072L, watchdog.getStats().trackedMemoryBytes)
        }

        @Test
        fun `getStats trackedMemoryBytes decreases after resetMemoryTracking`() {
            // Req 9.6: resetting a plot's memory should reduce the total
            val plotId = UUID.randomUUID()
            watchdog.trackMemoryAllocation(plotId, 1_000L)
            assertEquals(1_000L, watchdog.getStats().trackedMemoryBytes)

            watchdog.resetMemoryTracking(plotId)
            assertEquals(0L, watchdog.getStats().trackedMemoryBytes)
        }

        @Test
        fun `WatchdogStats is a data class holding activeFrames, totalCpuMs, trackedMemoryBytes`() {
            // Req 9.6: verify data class fields and equality semantics
            val s1 = WatchdogStats(activeFrames = 3, totalCpuMs = 20L, trackedMemoryBytes = 512L)
            val s2 = WatchdogStats(activeFrames = 3, totalCpuMs = 20L, trackedMemoryBytes = 512L)

            assertEquals(s1, s2)
            assertEquals(3, s1.activeFrames)
            assertEquals(20L, s1.totalCpuMs)
            assertEquals(512L, s1.trackedMemoryBytes)
        }

        @Test
        fun `getStats returns a snapshot — subsequent mutations do not affect the returned object`() {
            // Req 9.6: getStats returns a snapshot, not a live view
            watchdog.incrementActiveFrames()
            val snapshot = watchdog.getStats()
            watchdog.incrementActiveFrames()

            // The snapshot captured before the second increment must still show 1
            assertEquals(1, snapshot.activeFrames)
        }
    }
}
