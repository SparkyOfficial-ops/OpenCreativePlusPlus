package com.opencreativeplus.plugin.node.action

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction

/**
 * Action node that sends a message to the player.
 * Represented by PAPER in the coding grid.
 * Supports variable references in the message using $varname syntax.
 * Requirements: 7.2, 7.5
 */
class SendMessageAction(private val params: Map<String, Any>) : IAction {
    override val nodeId: String = "send_message"
    override val displayName: String = "Send Message"

    override suspend fun execute(context: ExecutionContext) {
        val player = context.player ?: return
        val rawMessage = params["message"]?.toString() ?: return

        val resolvedMessage = resolveVariables(rawMessage, context)
        player.sendMessage(resolvedMessage)
    }

    /**
     * Resolve variable references in the message string.
     * Variables are referenced as $varname and resolved from local → plot → saved scope.
     */
    private fun resolveVariables(message: String, context: ExecutionContext): String {
        return message.replace(Regex("\\$([a-zA-Z_][a-zA-Z0-9_]*)")) { match ->
            val varName = match.groupValues[1]
            val value = context.localScope.get(varName)
                ?: context.plotScope.get(varName)
                ?: context.savedScope.get(varName)
            value?.toString() ?: match.value
        }
    }
}
