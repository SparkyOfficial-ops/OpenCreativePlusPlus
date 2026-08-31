package com.opencreativeplus.api.execution

import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Execution context for script execution.
 * Contains all runtime state including variables, player reference, and event data.
 */
interface ExecutionContext {
    /**
     * The UUID of the plot where this script is executing
     */
    val plotId: UUID
    
    /**
     * The player associated with this execution (may be null for non-player events)
     */
    val player: Player?

    /**
     * Mutable list of target entities for this execution.
     * Initialized with the primary player when present, otherwise empty.
     */
    val targets: MutableList<Entity>
    
    /**
     * Event data passed from the triggering Minecraft event
     */
    val eventData: Map<String, Any>

    /**
     * Reference to the triggering Bukkit event, providing cancelEvent() for
     * Cancellable events. Defaults to [NoOpEventReference] for non-Cancellable
     * events and executions not triggered by a Bukkit event.
     * Overridden by ExecutionContextImpl when the event is Cancellable.
     * gameready-enhancements Req 1.3, 1.6
     */
    val eventReference: EventReference
        get() = NoOpEventReference
    
    /**
     * Local scope variables (cleared after execution completes)
     */
    val localScope: VariableScope
    
    /**
     * Plot scope variables (shared across all players on the plot)
     */
    val plotScope: VariableScope
    
    /**
     * Saved scope variables (persisted across server restarts)
     */
    val savedScope: VariableScope
    
    /**
     * Counter for operations performed (used by watchdog)
     */
    val operationCount: AtomicInteger

    /**
     * Counter for current function call stack depth.
     * Stored in ExecutionContext (not ThreadLocal) so it survives coroutine thread switches.
     */
    val callStackSize: AtomicInteger

    /**
     * The current target entity being processed in the targets iteration loop.
     * Set by ExecutionEngine before each action.execute(context) call.
     * Null outside of the iteration loop (e.g., when targets is empty).
     * Req 1.1, 1.2
     */
    var currentTarget: Entity?

    /**
     * Report [bytes] of memory allocated by this script execution to the Watchdog.
     * Nodes that allocate significant heap (list append, string assign) must call
     * this so the Watchdog can stop runaway scripts before OOM.
     * Default is a no-op — overridden by ExecutionContextImpl.
     * Req 29.1
     */
    fun trackMemory(bytes: Long) { /* no-op by default */ }

    /**
     * Resolve a raw stored variable value back to a runtime object.
     * UUID wrappers are resolved via Bukkit lookups (null if the player is
     * offline or the entity despawned); all other values pass through unchanged.
     * Default is identity — overridden by ExecutionContextImpl.
     * gameready-enhancements Req 2.3, 2.4, 2.5, 2.6
     */
    fun resolveValue(raw: Any?): Any? = raw

    /**
     * Execute a block of code on the Bukkit main thread.
     * Used for operations that require sync context (e.g., teleportation, world manipulation).
     *
     * @param block The code to execute on the main thread
     * @return The result of the block execution
     */
    suspend fun <T> syncContext(block: () -> T): T
}
