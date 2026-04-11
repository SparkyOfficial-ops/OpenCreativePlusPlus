package com.opencreativeplus.plugin.registry

import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.api.node.IValue
import com.opencreativeplus.api.registry.NodeRegistry
import org.bukkit.Material
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Thread-safe implementation of NodeRegistry.
 * Maps Minecraft block types to node factories.
 * Validates nodes implement required interfaces before registration.
 25.1, 25.2, 25.3, 25.4, 25.5, 11.3, 11.4
 */
class NodeRegistryImpl(
    private val logger: Logger = Logger.getLogger(NodeRegistryImpl::class.java.name)
) : NodeRegistry {

    private val actionFactories = ConcurrentHashMap<Material, (Map<String, Any>) -> IAction>()
    private val conditionFactories = ConcurrentHashMap<Material, (Map<String, Any>) -> ICondition>()
    private val valueFactories = ConcurrentHashMap<Material, (Map<String, Any>) -> IValue<*>>()
    private val eventFactories = ConcurrentHashMap<Material, () -> IEvent>()

    override fun registerAction(blockType: Material, factory: (params: Map<String, Any>) -> IAction) {
        validateNotAlreadyRegistered(blockType, "action")
        try {
            val testInstance = factory(emptyMap())
            require(testInstance.nodeId.isNotBlank()) { "Action nodeId must not be blank" }
            actionFactories[blockType] = factory
            logger.fine("Registered action node for $blockType: ${testInstance.nodeId}")
        } catch (e: IllegalArgumentException) {
            logger.warning("Invalid action node for $blockType: ${e.message}")
            throw e
        } catch (e: Exception) {
            logger.warning("Failed to validate action node for $blockType: ${e.message}")
            // Still register - factory may require params at runtime
            actionFactories[blockType] = factory
        }
    }

    override fun registerCondition(blockType: Material, factory: (params: Map<String, Any>) -> ICondition) {
        validateNotAlreadyRegistered(blockType, "condition")
        try {
            val testInstance = factory(emptyMap())
            require(testInstance.nodeId.isNotBlank()) { "Condition nodeId must not be blank" }
            conditionFactories[blockType] = factory
            logger.fine("Registered condition node for $blockType: ${testInstance.nodeId}")
        } catch (e: IllegalArgumentException) {
            logger.warning("Invalid condition node for $blockType: ${e.message}")
            throw e
        } catch (e: Exception) {
            conditionFactories[blockType] = factory
        }
    }

    override fun registerValue(blockType: Material, factory: (params: Map<String, Any>) -> IValue<*>) {
        validateNotAlreadyRegistered(blockType, "value")
        try {
            val testInstance = factory(emptyMap())
            require(testInstance.nodeId.isNotBlank()) { "Value nodeId must not be blank" }
            valueFactories[blockType] = factory
            logger.fine("Registered value node for $blockType: ${testInstance.nodeId}")
        } catch (e: IllegalArgumentException) {
            logger.warning("Invalid value node for $blockType: ${e.message}")
            throw e
        } catch (e: Exception) {
            valueFactories[blockType] = factory
        }
    }

    override fun registerEvent(blockType: Material, factory: () -> IEvent) {
        validateNotAlreadyRegistered(blockType, "event")
        try {
            val testInstance = factory()
            require(testInstance.nodeId.isNotBlank()) { "Event nodeId must not be blank" }
            require(testInstance.eventType.isNotBlank()) { "Event eventType must not be blank" }
            eventFactories[blockType] = factory
            logger.fine("Registered event node for $blockType: ${testInstance.nodeId} (${testInstance.eventType})")
        } catch (e: IllegalArgumentException) {
            logger.warning("Invalid event node for $blockType: ${e.message}")
            throw e
        } catch (e: Exception) {
            eventFactories[blockType] = factory
        }
    }

    override fun getActionFactory(blockType: Material): ((Map<String, Any>) -> IAction)? =
        actionFactories[blockType]

    override fun getConditionFactory(blockType: Material): ((Map<String, Any>) -> ICondition)? =
        conditionFactories[blockType]

    override fun getValueFactory(blockType: Material): ((Map<String, Any>) -> IValue<*>)? =
        valueFactories[blockType]

    override fun getEventFactory(blockType: Material): (() -> IEvent)? =
        eventFactories[blockType]

    /**
     * Returns all registered action block types (used for inventory provisioning in DEV mode).
     */
    fun getRegisteredActionMaterials(): Set<Material> = actionFactories.keys.toSet()

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
