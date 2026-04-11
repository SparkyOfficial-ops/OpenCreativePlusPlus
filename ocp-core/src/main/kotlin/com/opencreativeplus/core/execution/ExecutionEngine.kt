package com.opencreativeplus.core.execution

import com.opencreativeplus.core.watchdog.Watchdog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Launches and manages coroutine-based script executions.
 *
 * Each call to [executeScript] spawns a new coroutine under [CoroutineConfiguration.executionScope].
 * The [Watchdog] is consulted before every action node to enforce operation, TPS, and memory limits.
 * Active jobs are tracked in two maps so they can be cancelled by plot or by player.
 *
 6.1, 6.2, 6.3, 6.4, 6.5, 26.1, 26.2, 26.3, 26.4, 26.5, 38.1
 */
class ExecutionEngine(
    private val watchdog: Watchdog,
    private val variableManager: VariableManager,
    private val coroutineConfig: CoroutineConfiguration
) {
    /** plotId → active jobs for that plot */
    private val activeExecutions = ConcurrentHashMap<UUID, MutableList<Job>>()

    /** "$plotId:$playerId" → active jobs for that player on that plot */
    private val playerExecutions = ConcurrentHashMap<String, MutableList<Job>>()

    /**
     * Execute [script] in a new coroutine, building a fresh [ExecutionContextImpl] for this run.
     *
     * The coroutine is tracked so it can be cancelled via [cancelAllExecutions] or
     * [cancelPlayerExecutions]. Any non-cancellation exception is caught and logged so
     * that a single failing script cannot affect other running scripts (req 38.1).
     *
     * @param script   The compiled script to run.
     * @param plotId   The plot on which the script is executing.
     * @param player   The player that triggered the event, or null for non-player events.
     * @param eventData Key/value pairs from the triggering Minecraft event.
     */
    suspend fun executeScript(
        script: CompiledScript,
        plotId: UUID,
        player: Player?,
        eventData: Map<String, Any>
    ) {
        val context = ExecutionContextImpl(
            plotId = plotId,
            player = player,
            eventData = eventData,
            localScope = variableManager.createLocalScope(),
            plotScope = variableManager.getPlotScope(plotId),
            savedScope = variableManager.getSavedScope(plotId),
            operationCount = AtomicInteger(0),
            syncDispatcher = coroutineConfig.syncDispatcher
        )

        val job = coroutineConfig.executionScope.launch {
            try {
                for (action in script.actions) {
                    watchdog.checkExecution(context)
                    action.execute(context)
                    context.operationCount.incrementAndGet()
                }
            } catch (e: CancellationException) {
                throw e  // always rethrow so the coroutine machinery can clean up
            } catch (e: Exception) {
                // Isolate the failure to this script only — log and continue (req 38.1)
                System.err.println(
                    "[OCP] Script error on plot $plotId at ${script.sourceLocation}: ${e.message}"
                )
            } finally {
                context.localScope.clear()
            }
        }

        // Track by plot
        activeExecutions.getOrPut(plotId) { mutableListOf() }.add(job)
        job.invokeOnCompletion { activeExecutions[plotId]?.remove(job) }

        // Track by player if present
        player?.let {
            val key = playerKey(plotId, it.uniqueId)
            playerExecutions.getOrPut(key) { mutableListOf() }.add(job)
            job.invokeOnCompletion { playerExecutions[key]?.remove(job) }
        }
    }

    /**
     * Cancel all scripts currently running on [plotId].
     * Called when a plot switches out of PLAY mode (req 26.2).
     */
    fun cancelAllExecutions(plotId: UUID) {
        activeExecutions.remove(plotId)?.forEach { it.cancel() }
    }

    /**
     * Cancel all scripts associated with [playerId] on [plotId].
     * Called when a player leaves a plot (req 6.5, 26.1).
     */
    fun cancelPlayerExecutions(plotId: UUID, playerId: UUID) {
        val key = playerKey(plotId, playerId)
        playerExecutions.remove(key)?.forEach { it.cancel() }
    }

    private fun playerKey(plotId: UUID, playerId: UUID) = "$plotId:$playerId"
}
