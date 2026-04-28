package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
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
    override val targets: MutableList<Entity> = if (player != null) mutableListOf(player) else mutableListOf()
) : ExecutionContext {

    /**
     * Switches to [syncDispatcher] (the Bukkit main thread) for the duration
     * of [block], then resumes on the calling coroutine's dispatcher.
     */
    override suspend fun <T> syncContext(block: () -> T): T =
        withContext(syncDispatcher) { block() }
}
