package com.opencreativeplus.plugin.node.variable

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IValue
import com.opencreativeplus.core.execution.VariableManager

/**
 * Value node that reads a variable from the appropriate scope.
 * Resolves player-scoped variables via [VariableManager.resolveVariableKey].
 *
 * Params:
 *   - "name" : variable name (supports %player%_ prefix)
 *
 * Resolution order: local → plot → saved
 *
 * Req 6.1, 6.2
 */
class GetVariableNode(
    private val params: Map<String, Any>,
    private val variableManager: VariableManager
) : IValue {
    override val nodeId: String = "get_variable"
    override val displayName: String = "Get Variable"

    override suspend fun evaluate(context: ExecutionContext): Any? {
        val rawName = params["name"]?.toString() ?: return null
        val playerName = context.player?.name
        val resolvedKey = variableManager.resolveVariableKey(rawName, playerName)

        return context.localScope.get(resolvedKey)
            ?: context.plotScope.get(resolvedKey)
            ?: context.savedScope.get(resolvedKey)
    }
}
