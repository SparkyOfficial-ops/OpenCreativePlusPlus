package com.opencreativeplus.plugin.api

import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.api.node.IValue
import com.opencreativeplus.plugin.registry.NodeRegistryImpl
import org.bukkit.Material

/**
 * Public API facade for third-party plugins to register custom nodes.
 *
 * Usage:
 * ```kotlin
 * val api = OpenCreativePlusAPI.getInstance()
 * api.registerAction(Material.TNT) { params -> MyCustomAction(params) }
 * ```
 *
 11.1, 11.2, 11.3, 11.4, 11.5
 */
class OpenCreativePlusAPI private constructor(
    private val registry: NodeRegistryImpl
) {

    companion object {
        @Volatile
        private var instance: OpenCreativePlusAPI? = null

        fun initialize(registry: NodeRegistryImpl): OpenCreativePlusAPI {
            return OpenCreativePlusAPI(registry).also { instance = it }
        }

        fun getInstance(): OpenCreativePlusAPI =
            instance ?: error("OpenCreativePlusAPI has not been initialized yet.")
    }

    /**
     * Register a custom action node factory.
     11.2, 11.3
     */
    fun registerAction(blockType: Material, factory: (params: Map<String, Any>) -> IAction) {
        validateMaterial(blockType)
        val node = factory(emptyMap())
        require(node.nodeId.isNotBlank()) {
            "[OCP] Action nodeId must not be blank for material: $blockType"
        }
        registry.registerAction(blockType, factory)
    }

    /**
     * Register a custom condition node factory.
     11.2, 11.3
     */
    fun registerCondition(blockType: Material, factory: (params: Map<String, Any>) -> ICondition) {
        validateMaterial(blockType)
        registry.registerCondition(blockType, factory)
    }

    /**
     * Register a custom value node factory.
     11.2, 11.3
     */
    fun registerValue(blockType: Material, factory: (params: Map<String, Any>) -> IValue<*>) {
        validateMaterial(blockType)
        registry.registerValue(blockType, factory)
    }

    /**
     * Register a custom event node factory.
     11.2, 11.3
     */
    fun registerEvent(blockType: Material, factory: () -> IEvent) {
        validateMaterial(blockType)
        val event = factory()
        require(event.eventType.isNotBlank()) {
            "[OCP] Event eventType must not be blank for material: $blockType"
        }
        registry.registerEvent(blockType, factory)
    }

    /**
     * Validate that the material is a valid block type.
     11.4, 11.5
     */
    private fun validateMaterial(material: Material) {
        require(material.isBlock) {
            "[OCP] Cannot register node for non-block material: $material"
        }
    }
}
