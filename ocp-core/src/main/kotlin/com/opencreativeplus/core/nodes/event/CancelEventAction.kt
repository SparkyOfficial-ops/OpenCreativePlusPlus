package com.opencreativeplus.core.nodes.event

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction

/**
 * Action node that cancels the Bukkit event that triggered the current script.
 *
 * Delegates to [com.opencreativeplus.api.execution.EventReference.cancelEvent] on the
 * context's [com.opencreativeplus.api.execution.EventReference]. Cancellation is only
 * effective during the synchronous phase (before the first `WaitAction`); in the async
 * phase the call is ignored and a warning is logged by the reference itself.
 * For non-Cancellable events the context carries a no-op reference.
 *
 * gameready-enhancements Req 1.4, 1.5, 1.6
 */
class CancelEventAction : IAction {
    override val nodeId: String = "cancel_event"
    override val displayName: String = "Cancel Event"

    override suspend fun execute(context: ExecutionContext) {
        context.eventReference.cancelEvent()
    }
}
