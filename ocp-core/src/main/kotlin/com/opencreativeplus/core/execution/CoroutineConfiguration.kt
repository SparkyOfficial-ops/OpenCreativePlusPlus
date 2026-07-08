package com.opencreativeplus.core.execution

import kotlinx.coroutines.*

/**
 * Configures the coroutine infrastructure for script execution.
 *
 * - Uses a fixed thread pool sized to available CPU cores for async execution
 * - SupervisorJob ensures child coroutine failures don't cancel siblings
 * - syncDispatcher delegates to a provided callback so ocp-core stays Bukkit-free
 *
 6.1, 6.2, 28.1, 28.2
 */
class CoroutineConfiguration(
    private val syncRunner: (() -> Unit) -> Unit
) {
    private val threadPool = java.util.concurrent.Executors
        .newFixedThreadPool(Runtime.getRuntime().availableProcessors())

    @OptIn(DelicateCoroutinesApi::class)
    private val dispatcher: CoroutineDispatcher = threadPool.asCoroutineDispatcher()

    /**
     * The main execution scope. SupervisorJob isolates failures so one script
     * crashing does not cancel other running scripts.
     */
    val executionScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * A dispatcher that runs blocks on the Bukkit main thread via [syncRunner],
     * with a fast-path: if the current thread is already the main thread
     * (Bukkit.isPrimaryThread()), the block runs immediately without going
     * through the Bukkit scheduler.  This eliminates the N×dispatch overhead
     * when ExecutionEngine iterates over many targets all calling syncContext
     * in sequence — each subsequent call after the first is essentially free.
     */
    val syncDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (org.bukkit.Bukkit.isPrimaryThread()) {
                // Fast-path: already on the main thread — run inline, no scheduler round-trip
                block.run()
            } else {
                syncRunner { block.run() }
            }
        }
    }

    /** 
     * Cancels the execution scope and closes the underlying thread pool.
     * Call this during plugin shutdown.
     */
    fun close() {
        executionScope.cancel()
        threadPool.shutdown()
    }
}
