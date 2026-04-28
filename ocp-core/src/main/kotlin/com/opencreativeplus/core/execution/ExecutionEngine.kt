package com.opencreativeplus.core.execution

import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.plot.PlotManager
import com.opencreativeplus.core.trace.TraceManager
import com.opencreativeplus.core.watchdog.Watchdog
import com.opencreativeplus.core.watchdog.WatchdogException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

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
    private val coroutineConfig: CoroutineConfiguration,
    private var traceManager: TraceManager? = null,
    private val plotManager: PlotManager? = null,
    private val logger: Logger = Logger.getLogger("ExecutionEngine"),
    private val errorReporter: ((sourceLocation: String, message: String) -> Unit)? = null,
    private val compiledScriptProvider: ((UUID) -> CompiledScript?)? = null
) {
    /** plotId → active jobs for that plot */
    private val activeExecutions = ConcurrentHashMap<UUID, MutableList<Job>>()

    /** "$plotId:$playerId" → active jobs for that player on that plot */
    private val playerExecutions = ConcurrentHashMap<String, MutableList<Job>>()

    /**
     * Inject or replace the [TraceManager] used for debug visualisation.
     * Pass null to disable tracing.
     * s: 14.2, 14.3, 14.5, 14.7
     */
    fun setTraceManager(tm: TraceManager?) {
        traceManager = tm
    }

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
        // Req 13.3: use pre-compiled form if available
        val effectiveScript = compiledScriptProvider?.invoke(plotId) ?: script

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
            val startTime = System.currentTimeMillis()
            try {
                for ((index, action) in effectiveScript.actions.withIndex()) {
                    watchdog.checkExecution(context)
                    // Trace hook: notify before/at node execution (s: 14.2, 14.3)
                    traceManager?.onNodeExecute(null, action.displayName, emptyMap())

                    // Piston System (Req 8.3): if this action is also a condition, evaluate it
                    // and execute or skip the child branch accordingly.
                    val condition = action as? ICondition
                    if (condition != null) {
                        val conditionResult = condition.evaluate(context)
                        val childBranch = effectiveScript.conditionalBranches[index]
                        if (conditionResult && childBranch != null) {
                            for (childAction in childBranch) {
                                watchdog.checkExecution(context)
                                traceManager?.onNodeExecute(null, childAction.displayName, emptyMap())
                                childAction.execute(context)
                                context.operationCount.incrementAndGet()
                            }
                        }
                        // If condition is false, child branch is skipped (Req 8.3)
                    } else {
                        // Req 1.8: iterate over every target in context.targets and apply action to each.
                        // Req 1.9: if targets is empty, the loop does not execute — silent skip, no exception.
                        val targets = context.targets.toList()
                        if (targets.isNotEmpty()) {
                            for (target in targets) {
                                action.execute(context)
                            }
                        }
                        // If targets is empty — silent skip (Req 1.9)
                    }
                    context.operationCount.incrementAndGet()
                }
            } catch (e: CancellationException) {
                throw e  // always rethrow so the coroutine machinery can clean up
            } catch (e: WatchdogException) {
                // 10.1 plotId is available from context; 10.4 use logger.warning instead of System.err
                logger.warning("[OCP] Script stopped for plot $plotId: ${e.message}")
                // 10.2 find plot owner; 10.3 notify via syncContext
                val ownerUuid = plotManager?.getPlot(plotId)?.owner
                if (ownerUuid != null) {
                    context.syncContext {
                        Bukkit.getPlayer(ownerUuid)?.sendMessage(
                            "§c[OCP] Ваш скрипт на плоту #${plotId} остановлен (превышен лимит операций)."
                        )
                    }
                }
            } catch (e: Exception) {
                // Isolate the failure to this script only — log and continue (req 38.1)
                logger.warning("[OCP] Script error on plot $plotId at ${effectiveScript.sourceLocation}: ${e.message}")
                if (errorReporter != null) {
                    // Hologram will be shown — suppress chat message to avoid spam (Req 12.5)
                    errorReporter.invoke(effectiveScript.sourceLocation, e.message ?: "Unknown error")
                } else {
                    // No hologram reporter configured — fall back to chat notification
                    player?.sendMessage("§c[OCP] Script error: ${e.message ?: "Unknown error"}")
                }
            } finally {
                // Trace hook: execution complete summary (s: 14.7)
                player?.let { p ->
                    if (traceManager?.isTracing(p.uniqueId) == true) {
                        val durationMs = System.currentTimeMillis() - startTime
                        traceManager?.onExecutionComplete(
                            p.uniqueId,
                            context.operationCount.get(),
                            durationMs
                        )
                    }
                }
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
