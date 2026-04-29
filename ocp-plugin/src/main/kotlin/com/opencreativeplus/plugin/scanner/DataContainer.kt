package com.opencreativeplus.plugin.scanner

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * Represents a typed value stored in a ParamChest item (physical data container).
 *
 * Each subtype corresponds to a specific item material:
 * - [Text]     → Book (display name as string value)
 * - [Number]   → Magma Cream (display name parsed as Double)
 * - [Variable] → Iron Ingot (PDC ocp:var_name as variable name reference)
 * - [Location] → Compass (PDC ocp:loc_x/y/z/world/yaw/pitch as coordinates)
 *
 * PDC keys used for serialization:
 * - ocp:dc_type  — STRING — "text" | "number" | "variable" | "location"
 * - ocp:dc_value — STRING — serialized value (for Text and Number)
 * - ocp:var_name — STRING — variable name (for Variable)
 * - ocp:loc_x, ocp:loc_y, ocp:loc_z — DOUBLE — coordinates (for Location)
 * - ocp:loc_world — STRING — world name (for Location)
 * - ocp:loc_yaw, ocp:loc_pitch — DOUBLE — rotation (for Location, optional)
 *
 * Requirements: 3.1, 3.2, 3.3, 3.4, 3.8
 */
sealed class DataContainer {

    /** String value read from a Book item's display name. Requirements: 3.1 */
    data class Text(val value: String) : DataContainer()

    /** Numeric value read from a Magma Cream item's display name. Requirements: 3.2 */
    data class Number(val value: Double) : DataContainer()

    /** Variable name reference read from an Iron Ingot item's PDC ocp:var_name. Requirements: 3.3 */
    data class Variable(val name: String) : DataContainer()

    /** Location value read from a Compass item's PDC coordinates. Requirements: 3.4 */
    data class Location(
        val x: Double,
        val y: Double,
        val z: Double,
        val world: String,
        val yaw: Float = 0f,
        val pitch: Float = 0f
    ) : DataContainer()

    // -----------------------------------------------------------------------
    // Display helpers for HologramReporter (Requirement 9.2)
    // -----------------------------------------------------------------------

    /** Human-readable type label for hologram display. */
    fun typeLabel(): String = when (this) {
        is Text     -> "Текст"
        is Number   -> "Число"
        is Variable -> "Переменная"
        is Location -> "Местоположение"
    }

    /** Human-readable value string for hologram display. */
    fun displayValue(): String = when (this) {
        is Text     -> value
        is Number   -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Variable -> name
        is Location -> "(${x.format()}, ${y.format()}, ${z.format()}, $world)"
    }

    private fun Double.format(): String =
        if (this == this.toLong().toDouble()) this.toLong().toString() else this.toString()

    // -----------------------------------------------------------------------
    // Serialization to ItemStack PDC (Requirement 3.8)
    // -----------------------------------------------------------------------

    companion object {
        private val KEY_DC_TYPE   = NamespacedKey("ocp", "dc_type")
        private val KEY_DC_VALUE  = NamespacedKey("ocp", "dc_value")
        private val KEY_VAR_NAME  = NamespacedKey("ocp", "var_name")
        private val KEY_LOC_X     = NamespacedKey("ocp", "loc_x")
        private val KEY_LOC_Y     = NamespacedKey("ocp", "loc_y")
        private val KEY_LOC_Z     = NamespacedKey("ocp", "loc_z")
        private val KEY_LOC_WORLD = NamespacedKey("ocp", "loc_world")
        private val KEY_LOC_YAW   = NamespacedKey("ocp", "loc_yaw")
        private val KEY_LOC_PITCH = NamespacedKey("ocp", "loc_pitch")

        /**
         * Serializes this [DataContainer] into the PDC of the given [ItemStack].
         * Requirements: 3.8
         */
        fun DataContainer.serializeTo(item: ItemStack) {
            val meta = item.itemMeta ?: return
            val pdc = meta.persistentDataContainer
            when (this) {
                is Text -> {
                    pdc.set(KEY_DC_TYPE,  PersistentDataType.STRING, "text")
                    pdc.set(KEY_DC_VALUE, PersistentDataType.STRING, value)
                }
                is Number -> {
                    pdc.set(KEY_DC_TYPE,  PersistentDataType.STRING, "number")
                    pdc.set(KEY_DC_VALUE, PersistentDataType.STRING, value.toString())
                }
                is Variable -> {
                    pdc.set(KEY_DC_TYPE,  PersistentDataType.STRING, "variable")
                    pdc.set(KEY_VAR_NAME, PersistentDataType.STRING, name)
                }
                is Location -> {
                    pdc.set(KEY_DC_TYPE,   PersistentDataType.STRING, "location")
                    pdc.set(KEY_LOC_X,     PersistentDataType.DOUBLE, x)
                    pdc.set(KEY_LOC_Y,     PersistentDataType.DOUBLE, y)
                    pdc.set(KEY_LOC_Z,     PersistentDataType.DOUBLE, z)
                    pdc.set(KEY_LOC_WORLD, PersistentDataType.STRING, world)
                    pdc.set(KEY_LOC_YAW,   PersistentDataType.DOUBLE, yaw.toDouble())
                    pdc.set(KEY_LOC_PITCH, PersistentDataType.DOUBLE, pitch.toDouble())
                }
            }
            item.itemMeta = meta
        }

        /**
         * Deserializes a [DataContainer] from the PDC of the given [ItemStack].
         * Returns null if the item has no dc_type key.
         * Requirements: 3.8
         */
        fun deserializeFrom(item: ItemStack): DataContainer? {
            val pdc = item.itemMeta?.persistentDataContainer ?: return null
            return when (pdc.get(KEY_DC_TYPE, PersistentDataType.STRING)) {
                "text"     -> Text(pdc.get(KEY_DC_VALUE, PersistentDataType.STRING) ?: "")
                "number"   -> Number(pdc.get(KEY_DC_VALUE, PersistentDataType.STRING)?.toDoubleOrNull() ?: 0.0)
                "variable" -> Variable(pdc.get(KEY_VAR_NAME, PersistentDataType.STRING) ?: "")
                "location" -> Location(
                    x     = pdc.get(KEY_LOC_X,     PersistentDataType.DOUBLE) ?: 0.0,
                    y     = pdc.get(KEY_LOC_Y,     PersistentDataType.DOUBLE) ?: 0.0,
                    z     = pdc.get(KEY_LOC_Z,     PersistentDataType.DOUBLE) ?: 0.0,
                    world = pdc.get(KEY_LOC_WORLD, PersistentDataType.STRING) ?: "",
                    yaw   = (pdc.get(KEY_LOC_YAW,  PersistentDataType.DOUBLE) ?: 0.0).toFloat(),
                    pitch = (pdc.get(KEY_LOC_PITCH, PersistentDataType.DOUBLE) ?: 0.0).toFloat()
                )
                else -> null
            }
        }

        /**
         * Reads the plain-text display name from an ItemStack.
         * Uses Adventure's PlainTextComponentSerializer to strip formatting.
         */
        internal fun ItemStack.plainDisplayName(): String {
            val meta = itemMeta ?: return ""
            val name = meta.displayName() ?: return ""
            return PlainTextComponentSerializer.plainText().serialize(name)
        }
    }
}
