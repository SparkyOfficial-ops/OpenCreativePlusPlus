package com.opencreativeplus.core.serialization

import com.opencreativeplus.api.node.INode
import com.opencreativeplus.api.registry.NodeRegistry
import org.bson.Document
import org.bukkit.Material
import java.util.logging.Logger

/**
 * Serializes and deserializes AST nodes (INode) to/from MongoDB [Document].
 *
 * Each document stores:
 * - `nodeId`: the Material name used as the registry key (e.g. "PAPER")
 * - `params`: a sub-document of the node's construction parameters
 *
 * Bug 2 fix: uses [NodeRegistry] to look up factories by Material key during deserialization,
 * and [NodeRegistry.getMaterialForNode] to find the Material key during serialization.
 *
 * Validates: Requirements 2.2, 3.2
 */
class ASTSerializer(
    private val registry: NodeRegistry,
    private val logger: Logger = Logger.getLogger(ASTSerializer::class.java.name)
) {

    /**
     * Deserialize a single node from a MongoDB [Document].
     *
     * Returns `null` if:
     * - the document has no `nodeId` field
     * - the `nodeId` is not a valid [Material] name
     * - no factory is registered for that [Material]
     *
     * Property 3: For any serialized script from DB, `deserialize` SHALL return non-null AST
     * where each node is found in [NodeRegistry] by `nodeId`.
     */
    fun deserialize(doc: Document): INode? {
        val nodeIdStr = doc.getString("nodeId") ?: run {
            logger.warning("ASTSerializer.deserialize: document has no 'nodeId' field")
            return null
        }

        val material = try {
            Material.valueOf(nodeIdStr)
        } catch (e: IllegalArgumentException) {
            logger.warning("ASTSerializer.deserialize: unknown Material '$nodeIdStr'")
            return null
        }

        @Suppress("UNCHECKED_CAST")
        val params: Map<String, Any> = doc.get("params", Document::class.java)
            ?.toMap()
            ?.filterValues { it != null }
            ?.mapValues { it.value as Any }
            ?: emptyMap()

        // Try action factory first, then condition, then value
        registry.getActionFactory(material)?.let { factory ->
            return factory(params)
        }
        registry.getConditionFactory(material)?.let { factory ->
            return factory(params)
        }
        registry.getValueFactory(material)?.let { factory ->
            return factory(params)
        }

        logger.warning("ASTSerializer.deserialize: no factory registered for Material '$nodeIdStr'")
        return null
    }

    /**
     * Serialize a single [INode] to a MongoDB [Document].
     *
     * Stores the Material name as `nodeId` and the node's [INode.getParams] as `params`.
     * Returns `null` if the node's type is not registered in the registry.
     *
     * Property 4: For any correct AST, `serialize(deserialize(serialize(ast))) == serialize(ast)`.
     */
    fun serialize(node: INode): Document? {
        val material = registry.getMaterialForNode(node) ?: run {
            logger.warning("ASTSerializer.serialize: node '${node.nodeId}' not found in registry")
            return null
        }

        val params = Document(node.getParams())
        return Document("nodeId", material.name).append("params", params)
    }
}
