package com.opencreativeplus.plugin.event

import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.execution.ExecutionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Indexes compiled scripts by event type and dispatches Minecraft events to matching scripts.
 *
 * Scripts are indexed per-plot so that only scripts belonging to the active plot are executed.
 * Each matching script is launched in a separate coroutine for isolation (req 16.3).
 *
 16.1, 16.2, 16.3, 16.4, 16.5, 28.1
 */
class EventDispatcher(
    private val executionEngine: ExecutionEngine,
    private val executionScope: CoroutineScope
) {
    /** plotId → (eventType → scripts) */
    private val scriptsByEvent = ConcurrentHashMap<UUID, Map<String, List<CompiledScript>>>()

    /**
     * Index [scripts] for [plotId] by their event type.
     * Replaces any previously registered scripts for this plot.
     16.1, 16.2
     */
    fun registerScripts(plotId: UUID, scripts: List<CompiledScript>) {
        scriptsByEvent[plotId] = scripts.groupBy { it.event.eventType }
    }

    /**
     * Remove all scripts registered for [plotId].
     16.2
     */
    fun unregisterScripts(plotId: UUID) {
        scriptsByEvent.remove(plotId)
    }

    /**
     * Return all compiled scripts registered for [plotId], across all event types.
     */
    fun getCompiledScripts(plotId: UUID): List<CompiledScript> {
        return scriptsByEvent[plotId]?.values?.flatten() ?: emptyList()
    }

    /**
     * Dispatch [eventType] to all matching scripts for [plotId].
     * Each script is launched in its own coroutine so failures are isolated (req 16.5).
     16.2, 16.3, 16.4, 28.1
     */
    fun dispatchEvent(
        plotId: UUID,
        eventType: String,
        eventData: Map<String, Any>,
        player: Player?
    ) {
        val scripts = scriptsByEvent[plotId]?.get(eventType) ?: return

        for (script in scripts) {
            executionScope.launch {
                try {
                    executionEngine.executeScript(script, plotId, player, eventData)
                } catch (e: Exception) {
                    // Isolate failures — log and continue (req 16.5)
                    System.err.println("[OCP] Event dispatch error for $eventType on plot $plotId: ${e.message}")
                }
            }
        }
    }
}
