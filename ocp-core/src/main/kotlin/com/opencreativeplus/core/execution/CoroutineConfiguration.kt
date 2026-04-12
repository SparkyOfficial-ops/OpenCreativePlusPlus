package com.opencreativeplus.core.execution

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

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
    private val dispatcher = newFixedThreadPoolContext(
        nThreads = Runtime.getRuntime().availableProcessors(),
        name = "ocp-execution"
    )

    /**
     * The main execution scope. SupervisorJob isolates failures so one script
     * crashing does not cancel other running scripts.
     */
    val executionScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * A dispatcher that runs blocks on the Bukkit main thread via the injected
     * [syncRunner] callback. The plugin layer supplies something like:
     *   `{ block -> Bukkit.getScheduler().runTask(plugin) { block() } }`
     */
    val syncDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            syncRunner { block.run() }
        }
    }

    /**
     * Cancels the execution scope and closes the underlying thread pool.
     * Call this during plugin shutdown.
     */
    fun close() {
        executionScope.cancel()
        dispatcher.close()
    }
}
