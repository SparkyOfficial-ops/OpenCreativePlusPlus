package com.opencreativeplus.plugin.node.gui

/**
 * Interface for storing and retrieving [CustomMenuDefinition] objects.
 */
interface ICustomMenuStore {
    fun save(definition: CustomMenuDefinition)
    fun load(menuName: String): CustomMenuDefinition?
    fun serialize(def: CustomMenuDefinition): ByteArray
    fun deserialize(data: ByteArray): CustomMenuDefinition?
}
