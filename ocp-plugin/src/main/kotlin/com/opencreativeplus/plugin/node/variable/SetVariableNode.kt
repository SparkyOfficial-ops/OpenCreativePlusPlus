package com.opencreativeplus.plugin.node.variable

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.core.execution.VariableManager

/**
 * Action node that sets a variable in the plot or player scope.
 * Represented by IRON_BLOCK in the coding grid (SET_VARIABLE category).
 *
 * Params:
 *   - "name"  : variable name (supports %player%_ prefix for player-scoped vars)
 *   - "value" : value to assign
 *   - "scope" : "local" | "plot" | "saved" (default: "plot")
 *
 * Req 6.1, 6.2
 */
class SetVariableNode(
    private val params: Map<String, Any>,
    private val variableManager: VariableManager
) : IAction {
    override val nodeId: String = "set_variable"
    override val displayName: String = "Set Variable"

    override suspend fun execute(context: ExecutionContext) {
        val rawName = params["name"]?.toString() ?: return
        val value = params["value"] ?: return
        val scope = params["scope"]?.toString() ?: "plot"

        val playerName = context.player?.name
        val resolvedKey = variableManager.resolveVariableKey(rawName, playerName)

        when (scope) {
            "local" -> context.localScope.set(resolvedKey, value)
            "saved" -> context.savedScope.set(resolvedKey, value)
            else    -> context.plotScope.set(resolvedKey, value)
        }
    }
}
