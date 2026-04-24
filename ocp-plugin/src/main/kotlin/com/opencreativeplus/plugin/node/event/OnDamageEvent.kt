package com.opencreativeplus.plugin.node.event

import com.opencreativeplus.api.node.IEvent

/**
 * Event node that triggers when a player takes damage within a plot.
 *
 * Populates eventData:
 * - "player"  → victim's name
 * - "damager" → attacker's name, or "environment" if no attacker
 *
 * Listens to Bukkit EntityDamageByEntityEvent / EntityDamageEvent.
 * Requirements: 7.1 (ocp-manifest-roadmap)
 */
class OnDamageEvent : IEvent {
    override val nodeId: String = "on_damage"
    override val displayName: String = "On Player Damage"
    override val eventType: String = "player_damage"
}
