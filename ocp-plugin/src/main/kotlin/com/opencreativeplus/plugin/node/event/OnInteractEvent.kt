package com.opencreativeplus.plugin.node.event

import com.opencreativeplus.api.node.IEvent

/**
 * Event node that triggers when a player interacts with a block within a plot.
 *
 * Populates eventData:
 * - "block_loc" → block location string
 * - "item"      → held item type name
 *
 * Listens to Bukkit PlayerInteractEvent.
 * Requirements: 7.2 (ocp-manifest-roadmap)
 */
class OnInteractEvent : IEvent {
    override val nodeId: String = "on_interact"
    override val displayName: String = "On Player Interact"
    override val eventType: String = "player_interact"
}
