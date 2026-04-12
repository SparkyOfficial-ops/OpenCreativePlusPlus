package com.opencreativeplus.core.serialization

import java.util.UUID
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

/
 * Serializes and deserializes node parameters to/from a block's [PersistentDataContainer].
 *
 * Keys are namespaced using the provided [namespacedKey] factory (typically `ocp:{param_name}`).
 * Supported types for round-trip serialization:
 * - [String]
 * - [Int]
 * - [Double]
 * - [Boolean] (stored as [Byte]: `1` = true, `0` = false)
 * - [Location] (stored as a comma-separated string)
 * - [UUID] (stored as its string representation)
 * - [List] (stored as a `|`-delimited string of element `.toString()` values)
 *
 * Only blocks whose [Block.getState] implements [TileState] (e.g. chests, signs, command blocks)
 * have a [PersistentDataContainer]; calls on other block types are silently ignored.
 *
 * s: 20.1, 20.2, 20.4, 20.5
 *
 * @param namespacedKey Factory that converts a parameter name string into a [NamespacedKey].
 */
class ParamSerializer(private val namespacedKey: (String) -> NamespacedKey) {

    /
     * Persists [value] under [paramName] in the [PersistentDataContainer] of [block].
     *
     * If the block's state is not a [TileState], the call is a no-op.
     * After writing, [TileState.update] is called to flush the change to the world.
     *
     * @param block     The block whose PDC should be written.
     * @param paramName The parameter name; becomes the key suffix in the namespaced key.
     * @param value     The value to store. Must be one of the supported types listed above.
     */
    fun save(block: Block, paramName: String, value: Any) {
        val pdc = (block.state as? TileState)?.persistentDataContainer ?: return
        val key = namespacedKey(paramName)
        when (value) {
            is String   -> pdc.set(key, PersistentDataType.STRING, value)
            is Int      -> pdc.set(key, PersistentDataType.INTEGER, value)
            is Double   -> pdc.set(key, PersistentDataType.DOUBLE, value)
            is Boolean  -> pdc.set(key, PersistentDataType.BYTE, if (value) 1.toByte() else 0.toByte())
            is Location -> pdc.set(key, PersistentDataType.STRING, serializeLocation(value))
            is UUID     -> pdc.set(key, PersistentDataType.STRING, value.toString())
            is List<*>  -> pdc.set(key, PersistentDataType.STRING, serializeList(value))
        }
        (block.state as TileState).update()
    }

    /
     * Reads the value stored under [paramName] from the [PersistentDataContainer] of [block].
     *
     * The method tries each supported primitive type in order (String → Int → Double → Boolean)
     * and returns the first match. Returns `null` if the block has no PDC, the key is absent,
     * or the block's state is not a [TileState].
     *
     * Note: [Location], [UUID], and [List] values are stored as [String] and are returned as
     * raw strings; callers are responsible for further deserialization if needed.
     *
     * @param block     The block whose PDC should be read.
     * @param paramName The parameter name to look up.
     * @return The stored value, or `null` if not found.
     */
    fun load(block: Block, paramName: String): Any? {
        val pdc = (block.state as? TileState)?.persistentDataContainer ?: return null
        val key = namespacedKey(paramName)
        return pdc.get(key, PersistentDataType.STRING)
            ?: pdc.get(key, PersistentDataType.INTEGER)
            ?: pdc.get(key, PersistentDataType.DOUBLE)
            ?: pdc.get(key, PersistentDataType.BYTE)?.let { it == 1.toByte() }
    }

    /
     * Serializes a [Location] to a comma-separated string:
     * `"worldName,x,y,z,yaw,pitch"`.
     *
     * If the location's world is `null`, the world segment is an empty string.
     */
    private fun serializeLocation(loc: Location): String =
        "${loc.world?.name},${loc.x},${loc.y},${loc.z},${loc.yaw},${loc.pitch}"

    /
     * Serializes a [List] to a `|`-delimited string of each element's [toString] value.
     *
     * Example: `["a", "b", "c"]` → `"a|b|c"`.
     */
    private fun serializeList(list: List<*>): String = list.joinToString("|") { it.toString() }
}
