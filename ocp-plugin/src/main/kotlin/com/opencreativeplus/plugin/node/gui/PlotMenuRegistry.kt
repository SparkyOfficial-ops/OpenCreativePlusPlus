package com.opencreativeplus.plugin.node.gui

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of [CustomMenuDefinition] objects per plot.
 *
 * Populated when a [GUIDesignerEditor] saves a menu, and queried by [OpenMenuNode]
 * to open menus for players. This decouples menu storage from a specific block reference.
 *
 * Requirements: 1.1, 1.6, 1.8
 */
object PlotMenuRegistry {

    /** plotId → (menuName → definition) */
    private val menus = ConcurrentHashMap<UUID, ConcurrentHashMap<String, CustomMenuDefinition>>()

    /**
     * Store or replace a [CustomMenuDefinition] for the given [plotId].
     */
    fun put(plotId: UUID, definition: CustomMenuDefinition) {
        menus.getOrPut(plotId) { ConcurrentHashMap() }[definition.name] = definition
    }

    /**
     * Retrieve a [CustomMenuDefinition] by [plotId] and [menuName].
     * Returns null if not found.
     */
    fun get(plotId: UUID, menuName: String): CustomMenuDefinition? =
        menus[plotId]?.get(menuName)

    /**
     * Remove all menus for a given [plotId] (e.g. when a plot is unloaded).
     */
    fun clearPlot(plotId: UUID) {
        menus.remove(plotId)
    }
}
