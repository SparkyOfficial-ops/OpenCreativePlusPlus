package com.opencreativeplus.api.execution

import org.bukkit.event.Cancellable
import java.util.logging.Logger

/**
 * Wrapper around the Bukkit event that triggered the current script execution.
 * Lives inside [ExecutionContext] and exposes event cancellation to script nodes
 * (e.g. `CancelEventAction`).
 *
 * gameready-enhancements Req 1.3, 1.6
 */
interface EventReference {
    /**
     * Cancels the underlying event if it is [Cancellable] and the script is still
     * in the synchronous phase (before the first `WaitAction`). Calls made in the
     * async phase are ignored and logged as a warning.
     */
    fun cancelEvent()

    /** true if [cancelEvent] was successfully applied to the underlying event. */
    val isCancelled: Boolean
}

/**
 * [EventReference] implementation for [Cancellable] Bukkit events.
 *
 * Cancellation is only effective during the synchronous phase — i.e. before the
 * script suspends at the first `WaitAction`. Two signals mark the async phase:
 * - [asyncPhaseStarted] — set by the ExecutionEngine when the first suspending
 *   node is about to execute (gameready-enhancements Req 1.2, 1.5)
 * - [isAsyncPhase] — externally supplied checker (e.g. bound to script-frame state)
 *
 * gameready-enhancements Req 1.4, 1.5
 */
class CancellableEventReference(
    private val event: Cancellable,
    private val isAsyncPhase: () -> Boolean = { false },
    private val logger: Logger = Logger.getLogger("EventReference")
) : EventReference {

    /**
     * Becomes `true` when the ExecutionEngine detects the first suspending node
     * (e.g. `WaitAction`) for this execution. After this point [cancelEvent] is a
     * logged no-op, because Bukkit has already applied the event effects.
     */
    @Volatile
    var asyncPhaseStarted: Boolean = false

    override var isCancelled: Boolean = false
        private set

    override fun cancelEvent() {
        if (asyncPhaseStarted || isAsyncPhase()) {
            logger.warning("[OCP] cancelEvent() вызван в async-фазе — игнорируется")
            return
        }
        event.isCancelled = true
        isCancelled = true
    }
}

/**
 * No-op [EventReference] for non-[Cancellable] events (and executions not triggered
 * by a Bukkit event at all). [cancelEvent] does nothing, [isCancelled] is always false.
 *
 * gameready-enhancements Req 1.6
 */
object NoOpEventReference : EventReference {
    override fun cancelEvent() { /* no-op */ }
    override val isCancelled: Boolean = false
}
