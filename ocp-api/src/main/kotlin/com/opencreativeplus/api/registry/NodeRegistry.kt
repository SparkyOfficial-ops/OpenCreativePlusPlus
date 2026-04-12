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
     *
     * @param blockType The Minecraft block material
     * @param factory Factory function that creates an IAction from parameters
     */
    fun registerAction(blockType: Material, factory: (params: Map<String, Any>) -> IAction)

    /**
     * Register a condition node factory for a specific block type.
     *
     * @param blockType The Minecraft block material
     * @param factory Factory function that creates an ICondition from parameters
     */
    fun registerCondition(blockType: Material, factory: (params: Map<String, Any>) -> ICondition)

    /**
     * Register a value node factory for a specific block type.
     *
     * @param blockType The Minecraft block material
     * @param factory Factory function that creates an IValue from parameters
     */
    fun registerValue(blockType: Material, factory: (params: Map<String, Any>) -> IValue<*>)

    /**
     * Register an event node factory for a specific block type.
     *
     * @param blockType The Minecraft block material
     * @param factory Factory function that creates an IEvent
     */
    fun registerEvent(blockType: Material, factory: () -> IEvent)

    /**
     * Get the action factory for a specific block type.
     *
     * @param blockType The Minecraft block material
     * @return The factory function, or null if not registered
     */
    fun getActionFactory(blockType: Material): ((Map<String, Any>) -> IAction)?

    /**
     * Get the condition factory for a specific block type.
     *
     * @param blockType The Minecraft block material
     * @return The factory function, or null if not registered
     */
    fun getConditionFactory(blockType: Material): ((Map<String, Any>) -> ICondition)?

    /**
     * Get the value factory for a specific block type.
     *
     * @param blockType The Minecraft block material
     * @return The factory function, or null if not registered
     */
    fun getValueFactory(blockType: Material): ((Map<String, Any>) -> IValue<*>)?

    /**
     * Get the event factory for a specific block type.
     *
     * @param blockType The Minecraft block material
     * @return The factory function, or null if not registered
     */
    fun getEventFactory(blockType: Material): (() -> IEvent)?
}
