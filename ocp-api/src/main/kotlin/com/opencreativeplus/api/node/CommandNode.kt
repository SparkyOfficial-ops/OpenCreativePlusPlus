package com.opencreativeplus.api.node

/**
 * Represents a single scripting node in the CommandNode pattern.
 *
 * This data class replaces the dispersed [IAction]/[ICondition]/[IValue] hierarchy for
 * in-engine dispatch. Execution is delegated based on [type] using a `when(node.type)`
 * expression rather than `instanceof` / `as?` casts.
 *
 * **Serialization note:** [CommandNode] lives in ocp-api which has no BSON dependency.
 * Serialization targets a plain `Map<String, Any>` compatible with
 * `org.bson.Document.toMap()` so that ocp-core can wrap/unwrap it without any
 * conversion loss. The ocp-core `ASTSerializer` is responsible for the final
 * BSON ↔ Map translation.
 *
 * Requirements: 8.2, 8.5
 */
data class CommandNode(
    /** Category that drives dispatch in the ExecutionEngine. */
    val type: NodeType,
    /** Unique identifier matching the block/node registration in [NodeRegistry]. */
    val nodeId: String,
    /** Arbitrary key-value parameters. Values must be MongoDB-compatible primitives,
     *  nested [Map]s, or [List]s. */
    val params: Map<String, Any> = emptyMap()
) {
    companion object {
        /** Map key used to store the serialized [NodeType] name. */
        private const val KEY_TYPE = "type"
        /** Map key used to store the [nodeId] string. */
        private const val KEY_NODE_ID = "nodeId"
        /** Map key used to store the [params] sub-map. */
        private const val KEY_PARAMS = "params"

        /**
         * Deserializes a [CommandNode] from a plain [Map] as produced by
         * `org.bson.Document.toMap()` or [CommandNode.toMap].
         *
         * @param map A map with string keys `"type"`, `"nodeId"`, and optionally `"params"`.
         * @return The reconstructed [CommandNode].
         * @throws IllegalArgumentException if required keys are missing or [NodeType] name is invalid.
         */
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any>): CommandNode {
            val typeName = requireNotNull(map[KEY_TYPE] as? String) {
                "CommandNode map is missing required key '$KEY_TYPE'"
            }
            val nodeId = requireNotNull(map[KEY_NODE_ID] as? String) {
                "CommandNode map is missing required key '$KEY_NODE_ID'"
            }
            val type = NodeType.valueOf(typeName)
            val params = (map[KEY_PARAMS] as? Map<String, Any>) ?: emptyMap()
            return CommandNode(type = type, nodeId = nodeId, params = params)
        }
    }
}

/**
 * Serializes this [CommandNode] to a plain [Map] suitable for storage in MongoDB via
 * `org.bson.Document(map)` in ocp-core.
 *
 * Round-trip guarantee: `CommandNode.fromMap(node.toMap()) == node` for any [CommandNode].
 */
fun CommandNode.toMap(): Map<String, Any> = buildMap {
    put("type", type.name)
    put("nodeId", nodeId)
    put("params", params)
}
