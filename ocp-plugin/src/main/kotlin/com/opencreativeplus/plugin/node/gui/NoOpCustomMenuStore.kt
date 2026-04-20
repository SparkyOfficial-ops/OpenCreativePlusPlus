package com.opencreativeplus.plugin.node.gui

/**
 * A no-op implementation of the menu store interface used when no block PDC is available.
 *
 * [GUIDesignerNode] uses this when it doesn't have a block reference — menus are stored
 * exclusively in [PlotMenuRegistry] in that case.
 */
class NoOpCustomMenuStore : ICustomMenuStore {
    override fun save(definition: CustomMenuDefinition) { /* no-op */ }
    override fun load(menuName: String): CustomMenuDefinition? = null
    override fun serialize(def: CustomMenuDefinition): ByteArray = ByteArray(0)
    override fun deserialize(data: ByteArray): CustomMenuDefinition? = null
}
