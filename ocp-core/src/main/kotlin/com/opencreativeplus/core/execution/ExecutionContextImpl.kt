package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.EventReference
import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.NoOpEventReference
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.model.EntityVariable
import com.opencreativeplus.api.model.PlayerVariable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runtime execution context for a single script invocation.
 *
 * Holds all state needed during execution: the triggering player, event data,
 * variable scopes, and an operation counter used by the watchdog.
 *
 * [syncDispatcher] is injected so ocp-core stays decoupled from Bukkit's
 * scheduler — the plugin layer supplies a dispatcher that posts work to the
 * main thread (see [CoroutineConfiguration.syncDispatcher]).
 *
 24.1, 24.2, 24.3, 24.4, 24.5
 */
class ExecutionContextImpl(
    override val plotId: UUID,
    override val player: Player?,
    override val eventData: Map<String, Any>,
    override val localScope: VariableScope,
    override val plotScope: VariableScope,
    override val savedScope: VariableScope,
    override val operationCount: AtomicInteger,
    private val syncDispatcher: CoroutineDispatcher,
    override val callStackSize: AtomicInteger = AtomicInteger(0),
    override val targets: MutableList<Entity> = if (player != null) mutableListOf(player) else mutableListOf(),
    /** Forwarded to Watchdog.trackMemoryAllocation. No-op by default. Req 29.1 */
    private val memoryTracker: (plotId: UUID, bytes: Long) -> Unit = { _, _ -> },
    /** Reference to the triggering Bukkit event. NoOp for non-Cancellable events. gameready-enhancements Req 1.3 */
    override val eventReference: EventReference = NoOpEventReference
) : ExecutionContext {

    override var currentTarget: Entity? = null

    override suspend fun <T> syncContext(block: () -> T): T =
        withContext(syncDispatcher) { block() }

    /** Forward heap allocation to the Watchdog. Req 29.1 */
    override fun trackMemory(bytes: Long) = memoryTracker(plotId, bytes)

    /**
     * Resolve UUID wrappers to live objects via Bukkit lookups.
     * Returns null (no exception) when the player is offline or the entity
     * has despawned. gameready-enhancements Req 2.3, 2.4, 2.5, 2.6
     */
    override fun resolveValue(raw: Any?): Any? = when (raw) {
        is PlayerVariable -> Bukkit.getPlayer(raw.uuid)
        is EntityVariable -> Bukkit.getEntity(raw.uuid)
        else -> raw
    }
}
