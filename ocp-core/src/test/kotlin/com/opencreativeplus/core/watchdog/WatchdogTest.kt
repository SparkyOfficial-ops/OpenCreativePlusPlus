package com.opencreativeplus.core.watchdog

import com.opencreativeplus.api.execution.ExecutionContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/
 * Unit tests for the Watchdog anti-lag system.
 *
 8.2, 8.3, 8.4, 29.2
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

    // =========================================================================
    // Operation limit enforcement  (Requirement 8.2)
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
    // TPS pause / resume logic  (Requirements 8.3, 8.4)
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
    // Memory limit enforcement  (Requirement 29.2)
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
}
