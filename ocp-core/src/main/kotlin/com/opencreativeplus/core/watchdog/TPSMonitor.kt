package com.opencreativeplus.core.watchdog

import java.util.concurrent.atomic.AtomicLong

/
 * Monitors server TPS by recording tick timestamps and computing a rolling average.
 *
 * Each call to [tick] records the current time and derives the instantaneous TPS
 * from the elapsed time since the previous tick. The rolling average is maintained
 * over the last 10 measurements (≈ 10 seconds when called every server tick).
 *
 34.1, 34.2
 */
class TPSMonitor {

    private val history = ArrayDeque<Double>(MAX_HISTORY)
    private val lastTickTime = AtomicLong(0L)

    /
     * Record a server tick. Should be called once per Bukkit tick (every ~50 ms).
     * Computes instantaneous TPS from elapsed time and appends it to the rolling window.
     */
    fun tick() {
        val now = System.currentTimeMillis()
        val previous = lastTickTime.getAndSet(now)

        if (previous == 0L) return // first tick – no delta yet

        val delta = now - previous
        if (delta <= 0) return

        val instantTps = (1000.0 / delta).coerceAtMost(MAX_TPS)

        synchronized(history) {
            history.addLast(instantTps)
            if (history.size > MAX_HISTORY) {
                history.removeFirst()
            }
        }
    }

    /
     * Returns the rolling average TPS over the last [MAX_HISTORY] ticks.
     * Returns [MAX_TPS] when no measurements have been recorded yet.
     */
    fun getCurrentTPS(): Double {
        synchronized(history) {
            return if (history.isEmpty()) MAX_TPS else history.average()
        }
    }

    companion object {
        / Maximum number of TPS samples kept in the rolling window (≈ 10 seconds). */
        const val MAX_HISTORY = 10

        / TPS is capped at 20 to avoid inflated readings on fast hardware. */
        const val MAX_TPS = 20.0
    }
}
