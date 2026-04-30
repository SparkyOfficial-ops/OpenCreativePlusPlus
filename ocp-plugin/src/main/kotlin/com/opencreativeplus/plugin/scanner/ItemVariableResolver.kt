package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.model.ItemVariableRef
import com.opencreativeplus.api.model.ItemVariableType
import com.opencreativeplus.core.execution.PlaceholderParser
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.logging.Logger

/**
 * Resolves [ItemVariableRef] instances to their actual Bukkit runtime objects,
 * and resolves [DataContainer] values to their runtime representations.
 *
 * Variable lookup order: localScope first, then plotScope.
 * If the variable is not found or the cast fails, logs a warning and returns null (null-safe fallback).
 *
 * When a [PlaceholderParser] is provided, [DataContainer.Text] values are passed through
 * [PlaceholderParser.parse] before being returned (Requirements 5.1, 5.2).
 *
 * Requirements: 4.4, 4.5, 4.6, 5.1, 5.2
 */
class ItemVariableResolver(
    private val executionContext: ExecutionContext,
    private val placeholderParser: PlaceholderParser? = null,
    private val logger: Logger = Logger.getLogger("OCP-ItemVariableResolver")
) {

    // -------------------------------------------------------------------------
    // Scope lookup — defined first so helpers below can reference it
    // -------------------------------------------------------------------------

    /** Looks up a variable in localScope first, then plotScope. Req 4.6 */
    private fun lookupVariable(name: String): Any? =
        executionContext.localScope.get(name) ?: executionContext.plotScope.get(name)

    // -------------------------------------------------------------------------
    // Warning helpers — always return null for null-safe fallback (Req 4.6)
    // -------------------------------------------------------------------------

    private fun warnMissing(varName: String, typeName: String): Nothing? {
        logger.warning("[OCP] ItemVariableResolver: Variable '$varName' (expected $typeName) not found in any scope, using null fallback")
        return null
    }

    private fun warnTypeMismatch(varName: String, expectedType: String, actual: Any): Nothing? {
        logger.warning("[OCP] ItemVariableResolver: Variable '$varName' expected $expectedType but got ${actual::class.simpleName}, using null fallback")
        return null
    }

    // -------------------------------------------------------------------------
    // Location deserialization
    // -------------------------------------------------------------------------

    /**
     * Deserializes a location from the format "world,x,y,z,yaw,pitch".
     * Yaw and pitch are optional (default 0.0).
     */
    private fun deserializeLocation(serialized: String): Location {
        val parts = serialized.split(",")
        require(parts.size >= 4) { "Expected at least 4 parts (world,x,y,z), got ${parts.size}" }
        val world = Bukkit.getWorld(parts[0])
            ?: error("World '${parts[0]}' is not loaded")
        val x = parts[1].toDouble()
        val y = parts[2].toDouble()
        val z = parts[3].toDouble()
        val yaw = parts.getOrNull(4)?.toFloat() ?: 0f
        val pitch = parts.getOrNull(5)?.toFloat() ?: 0f
        return Location(world, x, y, z, yaw, pitch)
    }

    // -------------------------------------------------------------------------
    // Private resolution helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves a PLAYER_REFERENCE variable.
     * The stored value may be a [Player] directly, a UUID string, or a player name string.
     * Req 4.4
     */
    private fun resolvePlayer(varName: String): Player? {
        val raw = lookupVariable(varName) ?: return warnMissing(varName, "Player")
        if (raw is Player) return raw
        val str = raw as? String ?: return warnTypeMismatch(varName, "Player", raw)
        // Try UUID first, then player name
        val byUuid = runCatching { Bukkit.getPlayer(UUID.fromString(str)) }.getOrNull()
        if (byUuid != null) return byUuid
        val byName = Bukkit.getPlayerExact(str)
        if (byName != null) return byName
        logger.warning("[OCP] ItemVariableResolver: Player '$str' for variable '$varName' is not online, using null fallback")
        return null
    }

    /**
     * Resolves a LOCATION_REFERENCE variable.
     * The stored value may be a [Location] directly, or a serialized string "world,x,y,z,yaw,pitch".
     * Req 4.5
     */
    private fun resolveLocation(varName: String): Location? {
        val raw = lookupVariable(varName) ?: return warnMissing(varName, "Location")
        if (raw is Location) return raw
        val str = raw as? String ?: return warnTypeMismatch(varName, "Location", raw)
        return try {
            deserializeLocation(str)
        } catch (e: Exception) {
            logger.warning("[OCP] ItemVariableResolver: Failed to deserialize Location '$str' for variable '$varName': ${e.message}")
            null
        }
    }

    /**
     * Resolves an ENTITY_REFERENCE variable.
     * The stored value may be an [Entity] directly, or a UUID string.
     */
    private fun resolveEntity(varName: String): Entity? {
        val raw = lookupVariable(varName) ?: return warnMissing(varName, "Entity")
        if (raw is Entity) return raw
        val str = raw as? String ?: return warnTypeMismatch(varName, "Entity", raw)
        val uuid = try {
            UUID.fromString(str)
        } catch (e: IllegalArgumentException) {
            logger.warning("[OCP] ItemVariableResolver: Invalid UUID '$str' for entity variable '$varName'")
            return null
        }
        val entity = Bukkit.getEntity(uuid)
        if (entity == null) {
            logger.warning("[OCP] ItemVariableResolver: Entity with UUID '$uuid' for variable '$varName' not found, using null fallback")
        }
        return entity
    }

    /**
     * Resolves a NUMBER_REFERENCE variable.
     * The stored value may be a [Number] directly, or a numeric string.
     */
    private fun resolveNumber(varName: String): Number? {
        val raw = lookupVariable(varName) ?: return warnMissing(varName, "Number")
        if (raw is Number) return raw
        val str = raw as? String ?: return warnTypeMismatch(varName, "Number", raw)
        val asDouble = str.toDoubleOrNull()
        if (asDouble == null) {
            logger.warning("[OCP] ItemVariableResolver: Cannot parse '$str' as Number for variable '$varName'")
        }
        return asDouble
    }

    /**
     * Resolves a TEXT_REFERENCE variable.
     * Any value is converted to its string representation.
     */
    private fun resolveText(varName: String): String? {
        val raw = lookupVariable(varName) ?: return warnMissing(varName, "String")
        return raw.toString()
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Resolves an [ItemVariableRef] to its runtime Bukkit value.
     * Returns null with a warning log if the variable doesn't exist or cannot be resolved.
     */
    fun resolve(ref: ItemVariableRef): Any? {
        return when (ref.type) {
            ItemVariableType.PLAYER_REFERENCE   -> resolvePlayer(ref.name)
            ItemVariableType.LOCATION_REFERENCE -> resolveLocation(ref.name)
            ItemVariableType.ENTITY_REFERENCE   -> resolveEntity(ref.name)
            ItemVariableType.NUMBER_REFERENCE   -> resolveNumber(ref.name)
            ItemVariableType.TEXT_REFERENCE     -> resolveText(ref.name)
        }
    }

    /**
     * Resolves a [DataContainer] to its runtime value.
     *
     * - [DataContainer.Text]     → raw string, passed through [PlaceholderParser.parse] if available (Req 5.1, 5.2)
     * - [DataContainer.Variable] → looks up the variable name in localScope then plotScope
     * - [DataContainer.Number]   → returns the numeric value directly
     * - [DataContainer.Location] → returns the [DataContainer.Location] instance directly
     *
     * Requirements: 5.1, 5.2
     */
    fun resolveDataContainer(dc: DataContainer): Any? = when (dc) {
        is DataContainer.Text -> {
            val raw = dc.value
            placeholderParser?.parse(raw, executionContext) ?: raw
        }
        is DataContainer.Variable -> lookupVariable(dc.name)
        is DataContainer.Number   -> dc.value
        is DataContainer.Location -> dc
    }
}
