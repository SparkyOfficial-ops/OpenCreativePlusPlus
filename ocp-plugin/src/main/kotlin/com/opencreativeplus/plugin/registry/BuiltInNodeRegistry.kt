package com.opencreativeplus.plugin.registry

import com.opencreativeplus.plugin.node.action.SendMessageAction
import com.opencreativeplus.plugin.node.action.WaitAction
import com.opencreativeplus.plugin.node.event.OnJoinEvent
import org.bukkit.Material

/**
 * Registers all built-in nodes into the NodeRegistry.
 * Called during plugin startup to make built-in nodes available to the Physical Parser.
 * Requirements: 25.2, 7.1, 7.2, 7.3
 */
object BuiltInNodeRegistry {

    /**
     * Register all built-in event, action, condition, and value nodes.
     *
     * @param registry The NodeRegistryImpl to register nodes into
     */
    fun register(registry: NodeRegistryImpl) {
        registerEvents(registry)
        registerActions(registry)
    }

    private fun registerEvents(registry: NodeRegistryImpl) {
        // DIAMOND_BLOCK → OnJoinEvent (player_join trigger)
        registry.registerEvent(Material.DIAMOND_BLOCK) { OnJoinEvent() }
    }

    private fun registerActions(registry: NodeRegistryImpl) {
        // PAPER → SendMessageAction
        registry.registerAction(Material.PAPER) { params -> SendMessageAction(params) }

        // CLOCK → WaitAction
        registry.registerAction(Material.CLOCK) { params -> WaitAction(params) }
    }
}
