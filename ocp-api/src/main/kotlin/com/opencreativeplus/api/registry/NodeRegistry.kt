package com.opencreativeplus.api.registry

import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.api.node.IValue
import org.bukkit.Material

/**
 * Registry for mapping Minecraft block types to node factories.
 * Supports registration of actions, conditions, values, and events.
 */
interface NodeRegistry {
    /**
     * Register an action node factory for a specific block type.
     * The factory is NOT called during registration.
     *
     * @param blockType The Minecraft block material
     * @param factory Factory function that creates an IAction from parameters
     */
    fun registerAction(blockType: Material, factory: (params: Map<String, Any>) -> IAction)

    /**
     * Register an action node factory with an explicit nodeId.
     * Preferred overload — avoids calling factory(emptyMap()) during registration.
     *
     * @param blockType The Minecraft block material
     * @param nodeId Explicit node identifier stored in metadata
     * @param factory Factory function that creates an IAction from parameters
     */
    fun registerAction(blockType: Material, nodeId: String, factory: (params: Map<String, Any>) -> IAction)

    /**
     * Register a condition node factory for a specific block type.
     */
    fun registerCondition(blockType: Material, factory: (params: Map<String, Any>) -> ICondition)

    /**
     * Register a condition node factory with an explicit nodeId.
     */
    fun registerCondition(blockType: Material, nodeId: String, factory: (params: Map<String, Any>) -> ICondition)

    /**
     * Register a value node factory for a specific block type.
     */
    fun registerValue(blockType: Material, factory: (params: Map<String, Any>) -> IValue<*>)

    /**
     * Register a value node factory with an explicit nodeId.
     */
    fun registerValue(blockType: Material, nodeId: String, factory: (params: Map<String, Any>) -> IValue<*>)

    /**
     * Register an event node factory for a specific block type.
     */
    fun registerEvent(blockType: Material, factory: () -> IEvent)

    /**
     * Get the action factory for a specific block type.
     */
    fun getActionFactory(blockType: Material): ((Map<String, Any>) -> IAction)?

    /**
     * Get the condition factory for a specific block type.
     */
    fun getConditionFactory(blockType: Material): ((Map<String, Any>) -> ICondition)?

    /**
     * Get the value factory for a specific block type.
     */
    fun getValueFactory(blockType: Material): ((Map<String, Any>) -> IValue<*>)?

    /**
     * Get the event factory for a specific block type.
     */
    fun getEventFactory(blockType: Material): (() -> IEvent)?

    /**
     * Get the nodeId for a registered action block type.
     */
    fun getActionNodeId(blockType: Material): String?

    /**
     * Get the nodeId for a registered condition block type.
     */
    fun getConditionNodeId(blockType: Material): String?

    /**
     * Get the nodeId for a registered value block type.
     */
    fun getValueNodeId(blockType: Material): String?

    /**
     * Find the Material key for a given node instance by searching action, condition, and value registrations.
     * Returns null if the node's type is not registered.
     */
    fun getMaterialForNode(node: com.opencreativeplus.api.node.INode): Material?
}
