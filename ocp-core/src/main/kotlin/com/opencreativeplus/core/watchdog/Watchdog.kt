package com.opencreativeplus.core.watchdog

import com.opencreativeplus.api.execution.ExecutionContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
 * s: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 29.1, 29.2, 29.3, 29.4
 */
class Watchdog(private val tpsMonitor: TPSMonitor) {

    /** Memory allocated per plot (bytes), tracked via [trackMemoryAllocation]. */
    private val plotMemoryUsage = ConcurrentHashMap<UUID, AtomicLong>()

    /** Whether scripts are currently paused due to low TPS. */
    private val scriptsPaused = AtomicBoolean(false)

    /**
     * Check whether the given [context] is allowed to continue executing.
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

    private fun checkTps() {
        val currentTps = tpsMonitor.getCurrentTPS()

        if (scriptsPaused.get()) {
            if (currentTps >= RESUME_TPS) {
                scriptsPaused.set(false)
            } else {
                throw WatchdogException(
                    "Scripts paused – TPS is ${String.format("%.1f", currentTps)}, waiting for recovery above $RESUME_TPS"
                )
            }
        } else if (currentTps < MIN_TPS) {
            scriptsPaused.set(true)
            throw WatchdogException(
                "TPS dropped below $MIN_TPS (current: ${String.format("%.1f", currentTps)}), pausing scripts"
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

    /**
     * Record [bytes] of memory allocated by [plotId]'s scripts.
     * Accumulates until [resetMemoryTracking] is called.
     *
     * s: 29.1
     */
    fun trackMemoryAllocation(plotId: UUID, bytes: Long) {
        plotMemoryUsage.getOrPut(plotId) { AtomicLong(0L) }.addAndGet(bytes)
    }

    /**
     * Reset memory tracking for [plotId].
     * Should be called when a plot switches to BUILD or DEV mode.
     *
     * s: 29.4
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
        /** Maximum operations allowed per single script execution. s: 8.2 */
        const val MAX_OPERATIONS = 10_000

        /** TPS threshold below which all scripts are paused. s: 8.3 */
        const val MIN_TPS = 15.0

        /** TPS threshold above which paused scripts resume. s: 8.4 */
        const val RESUME_TPS = 18.0

        /** Maximum memory per plot in bytes (50 MB). s: 29.2 */
        const val MAX_MEMORY_BYTES = 50L * 1024 * 1024
    }
}

/**
 * Thrown by [Watchdog.checkExecution] when a script must be terminated.
 */
class WatchdogException(message: String) : Exception(message)
