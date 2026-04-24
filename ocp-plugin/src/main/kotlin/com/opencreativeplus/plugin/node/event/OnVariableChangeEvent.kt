package com.opencreativeplus.plugin.node.event

import com.opencreativeplus.api.node.IEvent

/**
 * Event node that triggers when a plot-scoped variable's value changes.
 *
 * Populates eventData:
 * - "var_name"  → name of the changed variable
 * - "old_value" → previous value (empty string if unknown)
 * - "new_value" → new value
 *
 * Subscribes to VariableManager.changes(plotId) and calls EventDispatcher
 * when the variable name matches the configured variable name.
 * Requirements: 7.4 (ocp-manifest-roadmap)
 */
class OnVariableChangeEvent : IEvent {
    override val nodeId: String = "on_variable_change"
    override val displayName: String = "On Variable Change"
    override val eventType: String = "variable_change"
}
