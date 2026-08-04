package com.opencreativeplus.core.watchdog

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.core.execution.ScriptFrame
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Anti-lag system that enforces operation, TPS, and memory limits on script execution.
 *
 * Three independent checks are performed before each action node executes:
 * 1. Operation limit – terminates scripts that exceed [MAX_OPERATIONS] per execution.
 * 2. TPS guard – pauses all scripts when TPS drops below [MIN_TPS] and resumes
 *    only after TPS recovers above [RESUME_TPS].
 * 3. Memory limit – terminates all scripts for a plot when its tracked allocation
 *    exceeds [MAX_MEMORY_BYTES].
 *
 * Requirements: 9.1, 9.2, 9.3, 9.4, 9.6
 */
class Watchdog(private val tpsMonitor: TPSMonitor) {

    /** Memory allocated per plot (bytes), tracked via [trackMemoryAllocation]. */
    private val plotMemoryUsage = ConcurrentHashMap<UUID, AtomicLong>()

    /** Whether scripts are currently paused due to low TPS. */
    private val scriptsPaused = AtomicBoolean(false)

    // -------------------------------------------------------------------------
    // Task 3.1 — WatchdogStats state
    // -------------------------------------------------------------------------

    /**
     * Number of [ScriptFrame]s currently tracked by the Ticker.
     * Incremented/decremented via [incrementActiveFrames]/[decrementActiveFrames].
     *
     * Requirements: 9.6
     */
    private val activeFrameCount = AtomicInteger(0)

    /**
     * Total CPU time (ms) consumed by all script frames in the most recent tick.
     * Written by the Ticker via [recordTickCpu].
     *
     * Requirements: 9.6
     */
    private val lastTickCpuMs = AtomicLong(0L)

    // -------------------------------------------------------------------------
    // Task 3.1 — Stats API
    // -------------------------------------------------------------------------

    /**
     * Notify the Watchdog that a [ScriptFrame] has been enqueued in the Ticker.
     * Should be called from [Ticker.enqueue].
     *
     * Requirements: 9.6
     */
    fun incrementActiveFrames() {
        activeFrameCount.incrementAndGet()
    }

    /**
     * Notify the Watchdog that a [ScriptFrame] has been removed from the Ticker.
     * Should be called from [Ticker.remove].
     *
     * Requirements: 9.6
     */
    fun decrementActiveFrames() {
        activeFrameCount.decrementAndGet()
    }

    /**
     * Record the total CPU time (ms) consumed by all frames in the most recent tick.
     * Called by the Ticker at the end of [Ticker.onTick].
     *
     * Requirements: 9.6
     */
    fun recordTickCpu(ms: Long) {
        lastTickCpuMs.set(ms)
    }

    /**
     * Returns a snapshot of current Watchdog metrics:
     * - [WatchdogStats.activeFrames] — number of frames currently in the Ticker queue
     * - [WatchdogStats.totalCpuMs] — CPU ms consumed by all frames in the last tick
     * - [WatchdogStats.trackedMemoryBytes] — sum of all per-plot tracked allocations
     *
     * Requirements: 9.6
     */
    fun getStats(): WatchdogStats {
        val trackedMemory = plotMemoryUsage.values.sumOf { it.get() }
        return WatchdogStats(
            activeFrames = activeFrameCount.get(),
            totalCpuMs = lastTickCpuMs.get(),
            trackedMemoryBytes = trackedMemory
        )
    }

    // -------------------------------------------------------------------------
    // Task 3.2 — checkFrame
    // -------------------------------------------------------------------------

    /**
     * Validate whether [frame] is allowed to continue executing.
     *
     * Checks (in order):
     * 1. Operation count — if [frame.context.operationCount] >= [MAX_OPERATIONS], calls
     *    [onCancel] and throws [WatchdogException].
     * 2. Memory — delegates to the existing [checkMemory] logic; if exceeded, calls
     *    [onCancel] and rethrows.
     *
     * The [onCancel] callback is provided by the Ticker so it can:
     * - Remove the frame from its active-frames list.
     * - Notify the plot owner's player (if online) — Bukkit code stays in the Ticker,
     *   NOT here, keeping Watchdog decoupled from Bukkit APIs.
     *
     * Requirements: 9.1, 9.4
     */
    fun checkFrame(frame: ScriptFrame, onCancel: (ScriptFrame) -> Unit) {
        // 1. Operation count check
        if (frame.context.operationCount.get() >= MAX_OPERATIONS) {
            onCancel(frame)
            throw WatchdogException(
                "Operation limit exceeded for frame ${frame.frameId} on plot ${frame.plotId}"
            )
        }

        // 2. Memory check
        val usedBytes = plotMemoryUsage[frame.plotId]?.get() ?: 0L
        if (usedBytes > MAX_MEMORY_BYTES) {
            onCancel(frame)
            val usedMb = usedBytes / (1024 * 1024)
            throw WatchdogException(
                "Memory limit exceeded for frame ${frame.frameId} on plot ${frame.plotId} " +
                    "(${usedMb}MB > ${MAX_MEMORY_BYTES / (1024 * 1024)}MB)"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Task 3.3 — public checkTps
    // -------------------------------------------------------------------------

    /**
     * Check the current server TPS and update the pause state accordingly.
     *
     * Semantics:
     * - If scripts are paused and TPS >= [RESUME_TPS] → clear the pause flag, return normally.
     * - If scripts are paused and TPS < [RESUME_TPS] → throw [WatchdogException].
     * - If scripts are NOT paused and TPS < [MIN_TPS] → set pause flag, throw [WatchdogException].
     * - Otherwise → return normally.
     *
     * Made public so the Ticker can call it directly at the start of each tick.
     *
     * Requirements: 9.2, 9.3
     */
    fun checkTps() {
        val currentTps = tpsMonitor.getCurrentTPS()

        if (scriptsPaused.get()) {
            if (currentTps >= RESUME_TPS) {
                scriptsPaused.set(false)
            } else {
                throw WatchdogException(
                    "Scripts paused – TPS is ${String.format("%.1f", currentTps)}, " +
                        "waiting for recovery above $RESUME_TPS"
                )
            }
        } else if (currentTps < MIN_TPS) {
            scriptsPaused.set(true)
            throw WatchdogException(
                "TPS dropped below $MIN_TPS (current: ${String.format("%.1f", currentTps)}), pausing scripts"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Legacy API — checkExecution (kept for backward compatibility)
    // -------------------------------------------------------------------------

    /**
     * Check whether the given [context] is allowed to continue executing.
     * Used by the legacy coroutine-based ExecutionEngine path.
     *
     * @throws WatchdogException if any limit is exceeded.
     */
    fun checkExecution(context: ExecutionContext) {
        checkOperationLimit(context)
        checkTps()
        checkMemory(context.plotId)
    }

    private fun checkOperationLimit(context: ExecutionContext) {
        if (context.operationCount.get() >= MAX_OPERATIONS) {
            throw WatchdogException(
                "Operation limit exceeded (${context.operationCount.get()} >= $MAX_OPERATIONS operations)"
            )
        }
    }

    private fun checkMemory(plotId: UUID) {
        val usedBytes = plotMemoryUsage[plotId]?.get() ?: return
        if (usedBytes > MAX_MEMORY_BYTES) {
            val usedMb = usedBytes / (1024 * 1024)
            throw WatchdogException(
                "Memory limit exceeded for plot $plotId (${usedMb}MB > ${MAX_MEMORY_BYTES / (1024 * 1024)}MB)"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Memory tracking API (unchanged)
    // -------------------------------------------------------------------------

    /**
     * Record [bytes] of memory allocated by [plotId]'s scripts.
     * Accumulates until [resetMemoryTracking] is called.
     */
    fun trackMemoryAllocation(plotId: UUID, bytes: Long) {
        plotMemoryUsage.getOrPut(plotId) { AtomicLong(0L) }.addAndGet(bytes)
    }

    /**
     * Reset memory tracking for [plotId].
     * Should be called when a plot switches to BUILD or DEV mode.
     */
    fun resetMemoryTracking(plotId: UUID) {
        plotMemoryUsage.remove(plotId)
    }

    /**
     * Returns the current memory usage in bytes for [plotId], or 0 if not tracked.
     */
    fun getMemoryUsage(plotId: UUID): Long = plotMemoryUsage[plotId]?.get() ?: 0L

    /**
     * Returns true if scripts are currently paused due to low TPS.
     */
    fun areScriptsPaused(): Boolean = scriptsPaused.get()

    companion object {
        /** Maximum operations allowed per single script execution. */
        const val MAX_OPERATIONS = 10_000

        /** TPS threshold below which all scripts are paused. */
        const val MIN_TPS = 15.0

        /** TPS threshold above which paused scripts resume. */
        const val RESUME_TPS = 18.0

        /** Maximum memory per plot in bytes (50 MB). */
        const val MAX_MEMORY_BYTES = 50L * 1024 * 1024
    }
}

// -------------------------------------------------------------------------
// WatchdogStats — Task 3.1
// -------------------------------------------------------------------------

/**
 * Snapshot of Watchdog metrics, returned by [Watchdog.getStats].
 *
 * @property activeFrames        Number of [ScriptFrame]s currently in the Ticker queue.
 * @property totalCpuMs          Total CPU ms consumed by all frames in the last tick.
 * @property trackedMemoryBytes  Sum of all per-plot tracked memory allocations in bytes.
 *
 * Requirements: 9.6
 */
data class WatchdogStats(
    val activeFrames: Int,
    val totalCpuMs: Long,
    val trackedMemoryBytes: Long
)

/**
 * Thrown by [Watchdog.checkExecution] or [Watchdog.checkFrame] when a script must be terminated.
 */
class WatchdogException(message: String) : Exception(message)
