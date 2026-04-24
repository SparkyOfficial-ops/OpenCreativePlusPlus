package com.opencreativeplus.plugin.event

import com.opencreativeplus.core.execution.VariableManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges VariableManager change events to the EventDispatcher.
 *
 * For each active plot, subscribes to [VariableManager.changes] and dispatches
 * a "variable_change" event to [EventDispatcher] whenever a variable changes.
 * The eventData contains:
 * - "var_name"  → name of the changed variable
 * - "new_value" → new value (or empty string if null)
 *
 * Requirements: 7.4 (ocp-manifest-roadmap)
 */
class VariableChangeEventBridge(
    private val variableManager: VariableManager,
    private val eventDispatcher: EventDispatcher,
    private val scope: CoroutineScope
) {
    /** plotId → subscription job */
    private val subscriptions = ConcurrentHashMap<UUID, Job>()

    /**
     * Start listening for variable changes on [plotId].
     * If already subscribed, this is a no-op.
     */
    fun subscribe(plotId: UUID) {
        subscriptions.getOrPut(plotId) {
            scope.launch {
                variableManager.changes(plotId).collect { change ->
                    val eventData = mapOf(
                        "var_name" to change.name,
                        "new_value" to (change.newValue?.toString() ?: "")
                    )
                    eventDispatcher.dispatchEvent(
                        plotId = plotId,
                        eventType = "variable_change",
                        eventData = eventData,
                        player = null
                    )
                }
            }
        }
    }

    /**
     * Stop listening for variable changes on [plotId] and cancel the subscription.
     */
    fun unsubscribe(plotId: UUID) {
        subscriptions.remove(plotId)?.cancel()
    }
}
