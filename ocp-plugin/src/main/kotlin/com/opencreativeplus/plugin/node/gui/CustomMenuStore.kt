package com.opencreativeplus.plugin.node.gui

import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.persistence.PersistentDataType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * Stores and retrieves [CustomMenuDefinition] objects in the PDC of a GUI Designer block.
 *
 * Each menu is stored under the key `ocp:menu:{name}` as a [PersistentDataType.BYTE_ARRAY],
 * serialized via Java object serialization.
 */
class CustomMenuStore(private val block: Block) : ICustomMenuStore {

    /**
     * Serializes [definition] and writes it to the block's PDC.
     * Does nothing if the block's state is not a [TileState].
     */
    override fun save(definition: CustomMenuDefinition) {
        val state = block.state as? TileState ?: return
        val key = menuKey(definition.name)
        val data = serialize(definition)
        state.persistentDataContainer.set(key, PersistentDataType.BYTE_ARRAY, data)
        state.update()
    }

    /**
     * Reads and deserializes a [CustomMenuDefinition] from the block's PDC.
     * Returns null if the key is absent, the block is not a [TileState], or deserialization fails.
     */
    override fun load(menuName: String): CustomMenuDefinition? {
        val state = block.state as? TileState ?: return null
        val key = menuKey(menuName)
        val data = state.persistentDataContainer.get(key, PersistentDataType.BYTE_ARRAY) ?: return null
        return deserialize(data)
    }

    /**
     * Converts a [CustomMenuDefinition] to a [ByteArray] using Java object serialization.
     */
    override fun serialize(def: CustomMenuDefinition): ByteArray {
        val baos = ByteArrayOutputStream()
        ObjectOutputStream(baos).use { it.writeObject(def) }
        return baos.toByteArray()
    }

    /**
     * Converts a [ByteArray] back to a [CustomMenuDefinition].
     * Returns null on any exception (corrupt data, class mismatch, etc.).
     */
    @Suppress("UNCHECKED_CAST")
    override fun deserialize(data: ByteArray): CustomMenuDefinition? {
        return try {
            val bais = ByteArrayInputStream(data)
            ObjectInputStream(bais).use { it.readObject() as CustomMenuDefinition }
        } catch (e: Exception) {
            null
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun menuKey(name: String): NamespacedKey = NamespacedKey("ocp", "menu:$name")
}
