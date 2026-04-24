package com.opencreativeplus.plugin.serialization

import com.opencreativeplus.plugin.scanner.CodeLine
import com.opencreativeplus.plugin.scanner.ScannedNode
import org.bson.Document
import org.bukkit.Location
import org.bukkit.Material
import java.util.logging.Logger

/**
 * Serializes and deserializes [CodeLine] objects (including nested children) to/from MongoDB [Document].
 *
 * Lives in `ocp-plugin` because [CodeLine] and [ScannedNode] are plugin-layer types
 * (ocp-core has no dependency on ocp-plugin).
 *
 * Supports the Piston System round-trip property (Requirement 8.5, Property 8):
 * FOR ALL valid nested CodeLine structures, serializing then deserializing SHALL produce
 * an equivalent structure.
 *
 * Feature: ocp-manifest-roadmap, Property 8: Piston System round-trip
 */
class CodeLineSerializer(
    private val logger: Logger = Logger.getLogger(CodeLineSerializer::class.java.name)
) {

    /**
     * Serialize a [CodeLine] (including nested children) to a MongoDB [Document].
     *
     * Document structure:
     * - `startLocation`: `"worldName:x:y:z"` string
     * - `nodes`: list of node documents, each with `nodeId`, `blockType`, `location`, `parameters`
     * - `children`: list of recursively serialized child [CodeLine] documents
     */
    fun serializeCodeLine(codeLine: CodeLine): Document {
        val nodesDoc = codeLine.nodes.map { node ->
            Document("blockType", node.blockType.name)
                .append("location", locationToString(node.location))
                .append("parameters", Document(node.parameters.mapValues { it.value.toString() }))
                .append("nodeId", node.nodeId)
        }
        val childrenDoc = codeLine.children.map { serializeCodeLine(it) }
        return Document("startLocation", locationToString(codeLine.startLocation))
            .append("nodes", nodesDoc)
            .append("children", childrenDoc)
    }

    /**
     * Deserialize a [CodeLine] (including nested children) from a MongoDB [Document].
     * Returns `null` if the document is malformed (missing required fields or unparseable values).
     *
     * Note: [Location.world] will be `null` after deserialization — no Bukkit [World] reference
     * is available in the serialization layer.
     */
    fun deserializeCodeLine(doc: Document): CodeLine? {
        val startLocationStr = doc.getString("startLocation") ?: run {
            logger.warning("CodeLineSerializer: missing 'startLocation' field")
            return null
        }
        val startLocation = stringToLocation(startLocationStr) ?: run {
            logger.warning("CodeLineSerializer: malformed startLocation '$startLocationStr'")
            return null
        }

        @Suppress("UNCHECKED_CAST")
        val nodesDocs = doc.get("nodes") as? List<Document> ?: emptyList()
        val nodes = nodesDocs.mapNotNull { nodeDoc -> deserializeNode(nodeDoc) }

        @Suppress("UNCHECKED_CAST")
        val childrenDocs = doc.get("children") as? List<Document> ?: emptyList()
        val children = childrenDocs.mapNotNull { deserializeCodeLine(it) }

        return CodeLine(startLocation = startLocation, nodes = nodes, children = children)
    }

    // --- private helpers ---

    private fun deserializeNode(nodeDoc: Document): ScannedNode? {
        val blockTypeStr = nodeDoc.getString("blockType") ?: run {
            logger.warning("CodeLineSerializer: node document missing 'blockType'")
            return null
        }
        val blockType = try {
            Material.valueOf(blockTypeStr)
        } catch (e: IllegalArgumentException) {
            logger.warning("CodeLineSerializer: unknown Material '$blockTypeStr'")
            return null
        }
        val locationStr = nodeDoc.getString("location") ?: run {
            logger.warning("CodeLineSerializer: node document missing 'location'")
            return null
        }
        val location = stringToLocation(locationStr) ?: run {
            logger.warning("CodeLineSerializer: malformed node location '$locationStr'")
            return null
        }
        val parametersDoc = nodeDoc.get("parameters", Document::class.java)
        val parameters: Map<String, Any> = parametersDoc
            ?.toMap()
            ?.filterValues { it != null }
            ?.mapValues { it.value as Any }
            ?: emptyMap()
        val nodeId = nodeDoc.getString("nodeId")
        return ScannedNode(blockType = blockType, location = location, parameters = parameters, nodeId = nodeId)
    }

    private fun locationToString(location: Location): String =
        "${location.world?.name ?: "null"}:${location.blockX}:${location.blockY}:${location.blockZ}"

    private fun stringToLocation(str: String): Location? {
        val parts = str.split(":")
        if (parts.size < 4) return null
        val x = parts[1].toIntOrNull() ?: return null
        val y = parts[2].toIntOrNull() ?: return null
        val z = parts[3].toIntOrNull() ?: return null
        // World is null — no Bukkit World available in the serialization context
        return Location(null, x.toDouble(), y.toDouble(), z.toDouble())
    }
}
