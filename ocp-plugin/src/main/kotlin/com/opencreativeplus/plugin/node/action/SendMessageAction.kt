package com.opencreativeplus.plugin.node.action

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.core.execution.PlaceholderParser
import com.opencreativeplus.core.execution.PlaceholderParserImpl
import com.opencreativeplus.core.execution.VariableManager
import org.bukkit.entity.Player

/**
 * Action node that sends a message to the player.
 * Represented by PAPER in the coding grid.
 * Supports variable references in the message using $varname syntax and
 * placeholder substitution via [PlaceholderParser] (e.g. %player%, %var(name)%).
 7.2, 7.5
 */
class SendMessageAction(
    private val params: Map<String, Any>,
    private val placeholderParser: PlaceholderParser = PlaceholderParserImpl(),
    private val variableManager: VariableManager? = null
) : IAction {
    override val nodeId: String = "send_message"
    override val displayName: String = "Send Message"

    override suspend fun execute(context: ExecutionContext) {
        val player = context.currentTarget as? Player ?: return
        val rawMessage = params["message"]?.toString() ?: return

        // First resolve %placeholder% syntax, then legacy $varname syntax
        val parsedMessage = placeholderParser.parse(rawMessage, context)
        val resolvedMessage = resolveVariables(parsedMessage, context)
        player.sendMessage(resolvedMessage)
    }

    /**
     * Resolve legacy variable references in the message string.
     * Variables are referenced as $varname and resolved from local → plot → saved scope.
     */
    private fun resolveVariables(message: String, context: ExecutionContext): String {
        return message.replace(Regex("\\$([a-zA-Z_%][a-zA-Z0-9_%]*)")) { match ->
            val rawName = match.groupValues[1]
            val playerName = context.player?.name
            val resolvedKey = variableManager?.resolveVariableKey(rawName, playerName) ?: rawName
            val value = context.localScope.get(resolvedKey)
                ?: context.plotScope.get(resolvedKey)
                ?: context.savedScope.get(resolvedKey)
            value?.toString() ?: match.value
        }
    }
}
