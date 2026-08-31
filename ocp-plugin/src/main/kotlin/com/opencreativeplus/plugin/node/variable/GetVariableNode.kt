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
) : IValue<Any?> {
    override val nodeId: String = "get_variable"
    override val displayName: String = "Get Variable"

    override suspend fun compute(context: ExecutionContext): Any? {
        val rawName = params["name"]?.toString() ?: return null
        val playerName = context.player?.name
        val resolvedKey = variableManager.resolveVariableKey(rawName, playerName)

        val raw = context.localScope.get(resolvedKey)
            ?: context.plotScope.get(resolvedKey)
            ?: context.savedScope.get(resolvedKey)
            ?: return null

        // gameready-enhancements Req 2.3, 2.4: UUID wrappers are resolved back
        // to Player/Entity (null if offline/despawned); other values pass through.
        return context.resolveValue(raw)
    }
}
