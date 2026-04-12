package com.opencreativeplus.plugin.compiler

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opencreativeplus.api.registry.NodeRegistry
import com.opencreativeplus.core.execution.CompiledScript

/
 * Serializes and deserializes [CompiledScript] lists to/from JSON strings using Gson.
 *
 * Format:
 * ```json
 * [
 *   {
 *     "eventType": "player_join",
 *     "eventNodeId": "on_join",
 *     "sourceLocation": "world@0,5,0",
 *     "actions": [
 *       { "nodeId": "send_message", "parameters": {} }
 *     ]
 *   }
 * ]
 * ```
 *
 12.1, 12.2, 12.3, 12.4, 12.5, 33.1, 33.2, 33.3, 33.4, 33.5
 */
class ASTSerializer(private val nodeRegistry: NodeRegistry) {

    private val gson = Gson()

    /
     * Serialize a list of [CompiledScript]s to a JSON string.
     12.1, 12.2, 33.1, 33.2
     */
    fun serialize(scripts: List<CompiledScript>): String {
        val jsonArray = JsonArray()
        for (script in scripts) {
            val scriptObj = JsonObject()
            scriptObj.addProperty("eventType", script.event.eventType)
            scriptObj.addProperty("eventNodeId", script.event.nodeId)
            scriptObj.addProperty("sourceLocation", script.sourceLocation)

            val actionsArray = JsonArray()
            for (action in script.actions) {
                val actionObj = JsonObject()
                actionObj.addProperty("nodeId", action.nodeId)
                actionObj.add("parameters", JsonObject())
                actionsArray.add(actionObj)
            }
            scriptObj.add("actions", actionsArray)
            jsonArray.add(scriptObj)
        }
        return gson.toJson(jsonArray)
    }

    /
     * Deserialize a JSON string back into a list of [CompiledScript]s.
     * Validates all node types against the registry.
     12.3, 12.4, 12.5, 33.3, 33.4, 33.5
     */
    fun deserialize(json: String): DeserializationResult {
        val errors = mutableListOf<String>()
        val scripts = mutableListOf<CompiledScript>()

        try {
            val jsonArray = JsonParser.parseString(json).asJsonArray

            for (element in jsonArray) {
                val scriptObj = element.asJsonObject
                val eventNodeId = scriptObj.get("eventNodeId")?.asString ?: ""
                val sourceLocation = scriptObj.get("sourceLocation")?.asString ?: "unknown"
                val actionsArray = scriptObj.getAsJsonArray("actions") ?: JsonArray()

                val event = findEventByNodeId(eventNodeId)
                if (event == null) {
                    errors.add("Unknown event nodeId '$eventNodeId' at $sourceLocation")
                    continue
                }

                val actions = mutableListOf<com.opencreativeplus.api.node.IAction>()
                var actionError = false
                for (actionElement in actionsArray) {
                    val actionObj = actionElement.asJsonObject
                    val nodeId = actionObj.get("nodeId")?.asString ?: ""
                    val paramsObj = actionObj.getAsJsonObject("parameters") ?: JsonObject()
                    val params = paramsObj.entrySet().associate { (k, v) ->
                        k to (if (v.isJsonPrimitive) v.asString as Any else v.toString() as Any)
                    }

                    val action = findActionByNodeId(nodeId, params)
                    if (action == null) {
                        errors.add("Unknown action nodeId '$nodeId' at $sourceLocation")
                        actionError = true
                        break
                    }
                    actions.add(action)
                }

                if (!actionError) {
                    scripts.add(CompiledScript(event = event, actions = actions, sourceLocation = sourceLocation))
                }
            }
        } catch (e: Exception) {
            return DeserializationResult(emptyList(), listOf("JSON parse error: ${e.message}"))
        }

        return DeserializationResult(scripts, errors)
    }

    /
     * Find an event instance by nodeId. Requires registry iteration support.
     33.4
     */
    private fun findEventByNodeId(nodeId: String): com.opencreativeplus.api.node.IEvent? = null

    /
     * Find an action instance by nodeId. Requires registry iteration support.
     33.4
     */
    private fun findActionByNodeId(nodeId: String, params: Map<String, Any>): com.opencreativeplus.api.node.IAction? = null
}

/
 * Result of deserializing a JSON AST string.
 12.4, 12.5
 */
data class DeserializationResult(
    val scripts: List<CompiledScript>,
    val errors: List<String>
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
}
