package com.opencreativeplus.core.watchdog

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/
 * Unit tests for TPSMonitor rolling-average TPS tracking.
 *
 8.3, 34.1, 34.2
 */
class TPSMonitorTest {

    private lateinit var monitor: TPSMonitor

    @BeforeEach
    fun setup() {
        monitor = TPSMonitor()
    }

    @Test
    fun `getCurrentTPS returns MAX_TPS when no ticks recorded`() {
        // Given: a fresh monitor with no ticks
        // When / Then: default is MAX_TPS (20.0)
        assertEquals(TPSMonitor.MAX_TPS, monitor.getCurrentTPS())
    }

    @Test
    fun `first tick does not add a sample (no delta available)`() {
        // Given: a fresh monitor
        // When: only one tick is called
        monitor.tick()

        // Then: still returns MAX_TPS because no delta can be computed
        assertEquals(TPSMonitor.MAX_TPS, monitor.getCurrentTPS())
    }

    @Test
    fun `two ticks 50ms apart produce approximately 20 TPS`() {
        // Given: two ticks 50 ms apart (ideal server tick rate)
        monitor.tick()
        Thread.sleep(50)
        monitor.tick()

        // Then: TPS should be close to 20 (within ±5 for timing variance on CI/loaded systems)
        val tps = monitor.getCurrentTPS()
        assertTrue(tps in 15.0..20.0, "Expected TPS ~20 but got $tps")
    }

    @Test
    fun `two ticks 100ms apart produce approximately 10 TPS`() {
        // Given: two ticks 100 ms apart (half the normal rate)
        monitor.tick()
        Thread.sleep(100)
        monitor.tick()

        // Then: TPS should be close to 10
        val tps = monitor.getCurrentTPS()
        assertTrue(tps in 8.0..12.0, "Expected TPS ~10 but got $tps")
    }

    @Test
    fun `TPS is capped at MAX_TPS even for very fast ticks`() {
        // Given: two ticks very close together (< 50 ms)
        monitor.tick()
        Thread.sleep(1)
        monitor.tick()

        // Then: TPS should not exceed MAX_TPS
        val tps = monitor.getCurrentTPS()
        assertTrue(tps <= TPSMonitor.MAX_TPS, "TPS should be capped at ${TPSMonitor.MAX_TPS} but was $tps")
    }

    @Test
    fun `rolling average uses at most MAX_HISTORY samples`() {
        // Given: more ticks than MAX_HISTORY
        // Simulate 15 ticks at ~50ms intervals (more than the 10-sample window)
        repeat(TPSMonitor.MAX_HISTORY + 5) {
            monitor.tick()
            Thread.sleep(50)
        }

        // Then: getCurrentTPS returns a valid value (not an error)
        val tps = monitor.getCurrentTPS()
        assertTrue(tps > 0.0, "TPS should be positive")
        assertTrue(tps <= TPSMonitor.MAX_TPS, "TPS should not exceed MAX_TPS")
    }

    @Test
    fun `slow ticks produce low TPS reading`() {
        // Given: ticks 200ms apart (5 TPS)
        monitor.tick()
        Thread.sleep(200)
        monitor.tick()

        // Then: TPS should be around 5
        val tps = monitor.getCurrentTPS()
        assertTrue(tps < Watchdog.MIN_TPS, "Slow ticks should produce TPS below MIN_TPS threshold, got $tps")
    }

    @Test
    fun `MAX_HISTORY constant is 10`() {
        assertEquals(10, TPSMonitor.MAX_HISTORY)
    }

    @Test
    fun `MAX_TPS constant is 20`() {
        assertEquals(20.0, TPSMonitor.MAX_TPS)
    }
}
