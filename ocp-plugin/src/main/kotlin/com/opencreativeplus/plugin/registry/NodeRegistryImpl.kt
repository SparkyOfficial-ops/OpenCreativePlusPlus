package com.opencreativeplus.plugin.registry

import com.opencreativeplus.api.node.CommandNode
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.api.node.IValue
import com.opencreativeplus.api.node.NodeType
import com.opencreativeplus.api.registry.NodeRegistry
import org.bukkit.Material
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Thread-safe implementation of NodeRegistry.
 * Maps Minecraft block types to node factories.
 * nodeId is stored explicitly — factory is NEVER called during registration.
 */
class NodeRegistryImpl(
    private val logger: Logger = Logger.getLogger(NodeRegistryImpl::class.java.name)
) : NodeRegistry {

    private val actionFactories = ConcurrentHashMap<Material, (Map<String, Any>) -> IAction>()
    private val conditionFactories = ConcurrentHashMap<Material, (Map<String, Any>) -> ICondition>()
    private val valueFactories = ConcurrentHashMap<Material, (Map<String, Any>) -> IValue<*>>()
    private val eventFactories = ConcurrentHashMap<Material, () -> IEvent>()

    // nodeId metadata maps — populated by explicit-nodeId overloads
    private val actionNodeIds = ConcurrentHashMap<Material, String>()
    private val conditionNodeIds = ConcurrentHashMap<Material, String>()
    private val valueNodeIds = ConcurrentHashMap<Material, String>()

    // nodeId → factory maps for string-based lookup (Requirement 5)
    private val actionFactoriesById = ConcurrentHashMap<String, (Map<String, Any>) -> IAction>()
    private val conditionFactoriesById = ConcurrentHashMap<String, (Map<String, Any>) -> ICondition>()
    private val valueFactoriesById = ConcurrentHashMap<String, (Map<String, Any>) -> IValue<*>>()

    // CommandNode factories by nodeId (Req 8.7)
    private val commandNodeFactories = ConcurrentHashMap<String, (Map<String, Any>) -> CommandNode>()

    // --- registerAction ---

    override fun registerAction(blockType: Material, factory: (params: Map<String, Any>) -> IAction) {
        validateNotAlreadyRegistered(blockType, "action")
        actionFactories[blockType] = factory
        logger.fine("Registered action node for $blockType (nodeId unknown — use explicit overload)")
    }

    override fun registerAction(blockType: Material, nodeId: String, factory: (params: Map<String, Any>) -> IAction) {
        require(nodeId.isNotBlank()) { "Action nodeId must not be blank for $blockType" }
        validateNotAlreadyRegistered(blockType, "action")
        actionFactories[blockType] = factory
        actionNodeIds[blockType] = nodeId
        actionFactoriesById[nodeId] = factory
        logger.fine("Registered action node for $blockType: $nodeId")
    }

    // --- registerCondition ---

    override fun registerCondition(blockType: Material, factory: (params: Map<String, Any>) -> ICondition) {
        validateNotAlreadyRegistered(blockType, "condition")
        conditionFactories[blockType] = factory
        logger.fine("Registered condition node for $blockType (nodeId unknown — use explicit overload)")
    }

    override fun registerCondition(blockType: Material, nodeId: String, factory: (params: Map<String, Any>) -> ICondition) {
        require(nodeId.isNotBlank()) { "Condition nodeId must not be blank for $blockType" }
        validateNotAlreadyRegistered(blockType, "condition")
        conditionFactories[blockType] = factory
        conditionNodeIds[blockType] = nodeId
        conditionFactoriesById[nodeId] = factory
        logger.fine("Registered condition node for $blockType: $nodeId")
    }

    // --- registerValue ---

    override fun registerValue(blockType: Material, factory: (params: Map<String, Any>) -> IValue<*>) {
        validateNotAlreadyRegistered(blockType, "value")
        valueFactories[blockType] = factory
        logger.fine("Registered value node for $blockType (nodeId unknown — use explicit overload)")
    }

    override fun registerValue(blockType: Material, nodeId: String, factory: (params: Map<String, Any>) -> IValue<*>) {
        require(nodeId.isNotBlank()) { "Value nodeId must not be blank for $blockType" }
        validateNotAlreadyRegistered(blockType, "value")
        valueFactories[blockType] = factory
        valueNodeIds[blockType] = nodeId
        valueFactoriesById[nodeId] = factory
        logger.fine("Registered value node for $blockType: $nodeId")
    }

    // --- registerEvent ---

    override fun registerEvent(blockType: Material, factory: () -> IEvent) {
        validateNotAlreadyRegistered(blockType, "event")
        eventFactories[blockType] = factory
        logger.fine("Registered event node for $blockType")
    }

    // --- getters ---

    override fun getActionFactory(blockType: Material): ((Map<String, Any>) -> IAction)? =
        actionFactories[blockType]

    override fun getConditionFactory(blockType: Material): ((Map<String, Any>) -> ICondition)? =
        conditionFactories[blockType]

    override fun getValueFactory(blockType: Material): ((Map<String, Any>) -> IValue<*>)? =
        valueFactories[blockType]

    override fun getEventFactory(blockType: Material): (() -> IEvent)? =
        eventFactories[blockType]

    override fun getActionNodeId(blockType: Material): String? = actionNodeIds[blockType]

    override fun getConditionNodeId(blockType: Material): String? = conditionNodeIds[blockType]

    override fun getValueNodeId(blockType: Material): String? = valueNodeIds[blockType]

    override fun getActionFactoryById(nodeId: String): ((Map<String, Any>) -> IAction)? =
        actionFactoriesById[nodeId]

    override fun getConditionFactoryById(nodeId: String): ((Map<String, Any>) -> ICondition)? =
        conditionFactoriesById[nodeId]

    override fun getValueFactoryById(nodeId: String): ((Map<String, Any>) -> IValue<*>)? =
        valueFactoriesById[nodeId]

    override fun getMaterialForNode(node: com.opencreativeplus.api.node.INode): Material? {
        // Search action, condition, and value factories by creating a test instance is not possible
        // without params. Instead, we match by nodeId stored in the metadata maps.
        val nodeId = node.nodeId
        actionNodeIds.entries.firstOrNull { it.value == nodeId }?.key?.let { return it }
        conditionNodeIds.entries.firstOrNull { it.value == nodeId }?.key?.let { return it }
        valueNodeIds.entries.firstOrNull { it.value == nodeId }?.key?.let { return it }
        return null
    }

    /**
     * Returns all registered action block types (used for inventory provisioning in DEV mode).
     */
    fun getRegisteredActionMaterials(): Set<Material> = actionFactories.keys.toSet()

    // --- CommandNode registration (Req 8.7) ---

    override fun registerCommandNode(
        nodeId: String,
        type: NodeType,
        factory: (params: Map<String, Any>) -> CommandNode
    ) {
        require(nodeId.isNotBlank()) { "CommandNode nodeId must not be blank" }
        if (commandNodeFactories.containsKey(nodeId)) {
            logger.warning("Overwriting existing CommandNode registration for nodeId '$nodeId'")
        }
        commandNodeFactories[nodeId] = factory
        logger.fine("Registered CommandNode for nodeId '$nodeId' (type=$type)")
    }

    override fun getCommandNodeFactory(nodeId: String): ((Map<String, Any>) -> CommandNode)? =
        commandNodeFactories[nodeId]

    override fun getRegisteredNodeIds(): Set<String> = commandNodeFactories.keys.toSet()

    private fun validateNotAlreadyRegistered(blockType: Material, nodeType: String) {
        val alreadyRegistered = when (nodeType) {
            "action" -> actionFactories.containsKey(blockType)
            "condition" -> conditionFactories.containsKey(blockType)
            "value" -> valueFactories.containsKey(blockType)
            "event" -> eventFactories.containsKey(blockType)
            else -> false
        }
        if (alreadyRegistered) {
            logger.warning("Overwriting existing $nodeType registration for $blockType")
        }
    }
}
