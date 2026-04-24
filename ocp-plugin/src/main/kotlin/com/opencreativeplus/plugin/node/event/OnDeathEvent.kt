package com.opencreativeplus.plugin.node.event

import com.opencreativeplus.api.node.IEvent

/**
 * Event node that triggers when a player dies within a plot.
 *
 * Populates eventData:
 * - "player" → deceased player's name
 * - "killer" → killer's name, or "none" if no killer
 *
 * Listens to Bukkit PlayerDeathEvent.
 * Requirements: 7.3 (ocp-manifest-roadmap)
 */
class OnDeathEvent : IEvent {
    override val nodeId: String = "on_death"
    override val displayName: String = "On Player Death"
    override val eventType: String = "player_death"
}
