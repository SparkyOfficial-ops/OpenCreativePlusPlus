package com.opencreativeplus.plugin.node.variable

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.model.toStorable
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

        // gameready-enhancements Req 2.1, 2.2: Player/Entity are stored as
        // UUID wrappers (PlayerVariable/EntityVariable), never as live objects.
        val storable = value.toStorable()

        when (scope) {
            "local" -> context.localScope.set(resolvedKey, storable)
            "saved" -> context.savedScope.set(resolvedKey, storable)
            else    -> context.plotScope.set(resolvedKey, storable)
        }

        // Track heap for Watchdog memory limit. Req 29.1
        val bytes: Long = when (storable) {
            is String -> (storable.length * 2 + 40).toLong()
            is List<*> -> (storable.size * 8 + 48).toLong()
            else -> 16L
        }
        context.trackMemory(bytes)
    }
}
