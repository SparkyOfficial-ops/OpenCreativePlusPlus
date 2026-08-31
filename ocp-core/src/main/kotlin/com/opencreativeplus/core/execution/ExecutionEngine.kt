package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.CancellableEventReference
import com.opencreativeplus.api.execution.EventReference
import com.opencreativeplus.api.execution.NoOpEventReference
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.node.IFunctionCall
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
    private val compiledScriptProvider: ((UUID) -> CompiledScript?)? = null,
    private val functionRegistry: FunctionRegistry? = null
) {
    /** plotId → active jobs for that plot.
     *  Uses a plain ArrayList wrapped in synchronized{} instead of CopyOnWriteArrayList:
     *  COWAL copies the entire array on every add/remove — on Raspberry Pi 3 with hundreds
     *  of events/sec this fills the JVM Eden space and triggers frequent STW GC pauses.
     *  Write contention here is low (add on launch, remove on completion), so synchronized
     *  is faster and GC-friendlier. */
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
     * @param eventReference Reference to the triggering Bukkit event; [NoOpEventReference]
     *                       for non-Cancellable events. gameready-enhancements Req 1.3
     */
    suspend fun executeScript(
        script: CompiledScript,
        plotId: UUID,
        player: Player?,
        eventData: Map<String, Any>,
        eventReference: EventReference = NoOpEventReference
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
            syncDispatcher = coroutineConfig.syncDispatcher,
            memoryTracker = { id, bytes -> watchdog.trackMemoryAllocation(id, bytes) },
            eventReference = eventReference
        )

        val job = coroutineConfig.executionScope.launch {
            val startTime = System.currentTimeMillis()
            // Note: per-plot mutex removed from script lifecycle.
            // Previously, the entire script execution was wrapped in mutex.withLock {},
            // which blocked ALL scripts on the plot if one script had a WaitAction (e.g., 10s pause).
            // Now, variable operations are thread-safe via ConcurrentHashMap in VariableScopeImpl.
            // Scripts that need atomic read-modify-write on shared variables should use explicit
            // synchronization or atomic operations at the variable level, not at the engine level.
            try {
                for ((index, action) in effectiveScript.actions.withIndex()) {
                    watchdog.checkExecution(context)
                    // Trace hook: notify before/at node execution (s: 14.2, 14.3)
                    traceNode(effectiveScript, index, action)

                    // Piston System (Req 8.3): if this action is also a condition, evaluate it
                    // and execute or skip the child branch accordingly.
                    val condition = action as? ICondition
                    if (condition != null) {
                        val conditionResult = condition.evaluate(context)
                        val childBranch = effectiveScript.conditionalBranches[index]
                        if (conditionResult && childBranch != null) {
                            // Req 4.5: condition true → execute then-branch only
                            for (childAction in childBranch) {
                                watchdog.checkExecution(context)
                                executeActionNode(childAction, context)
                                context.operationCount.incrementAndGet()
                            }
                        } else if (!conditionResult) {
                            // Req 4.4: condition false → execute else-branch if present
                            val elseBranch = effectiveScript.elseBranches[index]
                            if (elseBranch != null) {
                                for (elseAction in elseBranch) {
                                    watchdog.checkExecution(context)
                                    executeActionNode(elseAction, context)
                                    context.operationCount.incrementAndGet()
                                }
                            }
                            // Req 4.6: no else-branch → behaviour identical to current (skip silently)
                        }
                    } else if (action is IFunctionCall) {
                        // Function call handling (Req 5.3, 5.4, 5.5, 5.6)
                        executeFunctionCall(action, context)
                    } else {
                        // Req 1.1, 1.2: iterate over every target in context.targets, set currentTarget,
                        // and apply action to each.
                        val targets = context.targets.toList()
                        if (targets.isNotEmpty()) {
                            for (target in targets) {
                                context.currentTarget = target
                                executeActionNode(action, context)
                            }
                        } else {
                            // No targets — execute once with currentTarget = player (may be null for non-player events)
                            // This allows player-targeting actions to use context.player as fallback
                            context.currentTarget = context.player
                            executeActionNode(action, context)
                        }
                    }
                    context.operationCount.incrementAndGet()
                }
            } catch (e: CancellationException) {
                throw e  // always rethrow so the coroutine machinery can clean up
            } catch (e: WatchdogException) {
                logger.warning("[OCP] Script stopped for plot $plotId: ${e.message}")
                val ownerUuid = plotManager?.getPlot(plotId)?.owner
                if (ownerUuid != null) {
                    context.syncContext {
                        Bukkit.getPlayer(ownerUuid)?.sendMessage(
                            "§c[OCP] Ваш скрипт на плоту #${plotId} остановлен (превышен лимит операций)."
                        )
                    }
                }
            } catch (e: ScriptExecutionException) {
                // Node-level error with a meaningful message — show to player + hologram
                val location = e.sourceLocation ?: effectiveScript.sourceLocation
                logger.warning("[OCP] Script execution error on plot $plotId at $location: ${e.message}")
                val ownerUuid = plotManager?.getPlot(plotId)?.owner
                val errorMsg = e.message ?: "Ошибка выполнения скрипта"
                context.syncContext {
                    // Notify the player who triggered the script
                    player?.sendMessage("§c[OCP] §lОшибка скрипта: §r§c$errorMsg")
                    // Also notify the plot owner if different
                    if (ownerUuid != null && ownerUuid != player?.uniqueId) {
                        Bukkit.getPlayer(ownerUuid)?.sendMessage("§c[OCP] Ошибка в скрипте плота: $errorMsg")
                    }
                }
                // Spawn hologram above the offending block via errorReporter
                errorReporter?.invoke(location, errorMsg)
            } catch (e: Exception) {
                logger.warning("[OCP] Script error on plot $plotId at ${effectiveScript.sourceLocation}: ${e.message}")
                if (errorReporter != null) {
                    errorReporter.invoke(effectiveScript.sourceLocation, e.message ?: "Unknown error")
                } else {
                    player?.sendMessage("§c[OCP] Script error: ${e.message ?: "Unknown error"}")
                }
            } finally {
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

        // Track by plot — synchronized ArrayList: low write contention, GC-friendlier than COWAL
        val plotJobs = activeExecutions.getOrPut(plotId) { mutableListOf() }
        synchronized(plotJobs) { plotJobs.add(job) }
        job.invokeOnCompletion {
            activeExecutions[plotId]?.let { list ->
                synchronized(list) {
                    list.remove(job)
                    if (list.isEmpty()) activeExecutions.remove(plotId)
                }
            }
        }

        // Track by player if present
        player?.let {
            val key = playerKey(plotId, it.uniqueId)
            val playerJobs = playerExecutions.getOrPut(key) { mutableListOf() }
            synchronized(playerJobs) { playerJobs.add(job) }
            job.invokeOnCompletion {
                playerExecutions[key]?.let { list ->
                    synchronized(list) {
                        list.remove(job)
                        if (list.isEmpty()) playerExecutions.remove(key)
                    }
                }
            }
        }
    }

    /**
     * Trace hook (s: 14.2, 14.3): report the action at [index] in [script] to the
     * [TraceManager] together with its source-block location and scanned parameters,
     * so tracing players see REDSTONE particles and an ArmorStand overlay above the block.
     * Cheap no-op while nobody has Trace Mode active.
     */
    private fun traceNode(script: CompiledScript, index: Int, action: IAction) {
        val tm = traceManager ?: return
        if (!tm.isAnyoneTracing()) return
        tm.onNodeExecute(
            script.actionLocations.getOrNull(index),
            action.displayName,
            script.actionParams.getOrNull(index) ?: emptyMap()
        )
    }

    /**
     * Execute a single action node, marking the start of the async phase when the
     * first suspending node (e.g. `WaitAction`, nodeId = "wait") is about to run.
     *
     * Once the async phase starts, [CancellableEventReference.cancelEvent] calls are
     * ignored (Bukkit has already applied the event effects by then).
     * gameready-enhancements Req 1.2, 1.5
     */
    private suspend fun executeActionNode(action: IAction, context: ExecutionContextImpl) {
        if (action.nodeId == "wait") {
            (context.eventReference as? CancellableEventReference)?.asyncPhaseStarted = true
        }
        action.execute(context)
    }

    /**
     * Execute a function call action.
     *
     * Looks up the function in [functionRegistry]; if not found — logs a warning and skips (Req 5.4).
     * Creates a new [ExecutionContextImpl] with isolated [localScope] but inherited [targets] (Req 5.5).
     * Checks [callStackSize] < 32; if exceeded — terminates chain + logs "stack overflow" (Req 5.6).
     *
     * Requirements: 5.3, 5.4, 5.5, 5.6
     */
    private suspend fun executeFunctionCall(
        action: IFunctionCall,
        context: ExecutionContextImpl
    ) {
        val registry = functionRegistry
        if (registry == null) {
            logger.warning("[OCP] ExecutionEngine: FunctionRegistry not configured — skipping call_function '${action.targetFunctionName}'")
            return
        }

        // Req 5.4: if function not found — log warning and skip
        val functionScript = registry.get(action.targetFunctionName)
        if (functionScript == null) {
            logger.warning("[OCP] ExecutionEngine: function '${action.targetFunctionName}' not found in registry — skipping")
            return
        }

        // Req 5.6: check call stack depth
        val depth = context.callStackSize.incrementAndGet()
        try {
            if (depth > MAX_CALL_STACK_SIZE) {
                logger.warning("[OCP] ExecutionEngine: stack overflow — call stack exceeded $MAX_CALL_STACK_SIZE for function '${action.targetFunctionName}'")
                notifyStackOverflow(context, action.targetFunctionName)
                return
            }

            // Req 5.5: create new context with isolated localScope but inherited targets
            val functionContext = ExecutionContextImpl(
                plotId = context.plotId,
                player = context.player,
                eventData = context.eventData,
                localScope = variableManager.createLocalScope(),
                plotScope = context.plotScope,
                savedScope = context.savedScope,
                operationCount = context.operationCount,
                syncDispatcher = coroutineConfig.syncDispatcher,
                callStackSize = context.callStackSize,
                targets = context.targets,
                memoryTracker = { id, bytes -> watchdog.trackMemoryAllocation(id, bytes) },
                // Share the triggering-event reference so cancel_event works inside functions.
                // gameready-enhancements Req 1.3
                eventReference = context.eventReference
            )

            // Execute the function's actions
            for ((index, funcAction) in functionScript.actions.withIndex()) {
                watchdog.checkExecution(functionContext)
                traceNode(functionScript, index, funcAction)

                val funcCondition = funcAction as? ICondition
                if (funcCondition != null) {
                    val conditionResult = funcCondition.evaluate(functionContext)
                    val childBranch = functionScript.conditionalBranches[index]
                    if (conditionResult && childBranch != null) {
                        for (childAction in childBranch) {
                            watchdog.checkExecution(functionContext)
                            executeActionNode(childAction, functionContext)
                            functionContext.operationCount.incrementAndGet()
                        }
                    } else if (!conditionResult) {
                        val elseBranch = functionScript.elseBranches[index]
                        if (elseBranch != null) {
                            for (elseAction in elseBranch) {
                                watchdog.checkExecution(functionContext)
                                executeActionNode(elseAction, functionContext)
                                functionContext.operationCount.incrementAndGet()
                            }
                        }
                    }
                } else if (funcAction is IFunctionCall) {
                    // Recursive function call — handled with the same stack depth tracking
                    executeFunctionCall(funcAction, functionContext)
                } else {
                    val targets = functionContext.targets.toList()
                    if (targets.isNotEmpty()) {
                        for (target in targets) {
                            functionContext.currentTarget = target
                            executeActionNode(funcAction, functionContext)
                        }
                    } else {
                        functionContext.currentTarget = functionContext.player
                        executeActionNode(funcAction, functionContext)
                    }
                }
                functionContext.operationCount.incrementAndGet()
            }
        } finally {
            context.callStackSize.decrementAndGet()
        }
    }

    /**
     * Notify the plot owner in red chat that a function-call stack overflow occurred.
     * Mirror of the WatchdogException notification — an infinite mutual recursion
     * (A calls B, B calls A) must be visible to the player, not only in the server log.
     */
    private suspend fun notifyStackOverflow(context: ExecutionContextImpl, functionName: String) {
        val ownerUuid = plotManager?.getPlot(context.plotId)?.owner ?: return
        context.syncContext {
            Bukkit.getPlayer(ownerUuid)?.sendMessage(
                "§c[OCP] Ваш скрипт остановлен: переполнение стека вызовов функций " +
                    "(функция '$functionName', лимит $MAX_CALL_STACK_SIZE)."
            )
        }
    }

    companion object {
        /** Maximum call stack depth for function calls (Req 5.6). */
        const val MAX_CALL_STACK_SIZE = 32
    }

    /**
     * Cancel all scripts currently running on [plotId].
     * Called when a plot switches out of PLAY mode (req 26.2).
     */
    fun cancelAllExecutions(plotId: UUID) {
        activeExecutions.remove(plotId)?.let { list ->
            val snapshot = synchronized(list) { list.toList() }
            snapshot.forEach { it.cancel() }
        }
    }

    /**
     * Cancel all scripts associated with [playerId] on [plotId].
     * Called when a player leaves a plot (req 6.5, 26.1).
     */
    fun cancelPlayerExecutions(plotId: UUID, playerId: UUID) {
        val key = playerKey(plotId, playerId)
        playerExecutions.remove(key)?.let { list ->
            val snapshot = synchronized(list) { list.toList() }
            snapshot.forEach { it.cancel() }
        }
    }

    private fun playerKey(plotId: UUID, playerId: UUID) = "$plotId:$playerId"
}
