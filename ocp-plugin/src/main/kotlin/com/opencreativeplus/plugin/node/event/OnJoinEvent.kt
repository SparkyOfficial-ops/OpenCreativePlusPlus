package com.opencreativeplus.plugin.node.event

import com.opencreativeplus.api.node.IEvent

/**
 * Event node that triggers when a player joins the plot.
 * Represented by a DIAMOND_BLOCK in the coding grid.
 * Requirements: 7.1, 7.4
 */
class OnJoinEvent : IEvent {
    override val nodeId: String = "on_join"
    override val displayName: String = "On Player Join"
    override val eventType: String = "player_join"
}
