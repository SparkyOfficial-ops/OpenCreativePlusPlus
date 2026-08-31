package com.opencreativeplus.core.serialization

import com.opencreativeplus.api.model.EntityVariable
import com.opencreativeplus.api.model.PlayerVariable
import org.bson.Document
import java.util.UUID

/**
 * BSON codec for the UUID-safe entity wrappers stored in the SavedScope.
 *
 * [PlayerVariable] / [EntityVariable] are persisted as
 * `{ "__type": "PlayerVariable", "uuid": "..." }` (and likewise for EntityVariable)
 * so they survive MongoDB round-trips without ever holding live Player/Entity
 * references. Unknown values pass through unchanged in both directions.
 *
 * gameready-enhancements Req 2.1, 2.2
 */
object EntityVariableCodec {
    private const val TYPE_FIELD = "__type"
    private const val UUID_FIELD = "uuid"
    private const val PLAYER_TYPE = "PlayerVariable"
    private const val ENTITY_TYPE = "EntityVariable"

    /** Encode a scope value into a BSON-safe representation for storage. */
    fun encode(value: Any): Any = when (value) {
        is PlayerVariable -> Document(TYPE_FIELD, PLAYER_TYPE).append(UUID_FIELD, value.uuid.toString())
        is EntityVariable -> Document(TYPE_FIELD, ENTITY_TYPE).append(UUID_FIELD, value.uuid.toString())
        else -> value
    }

    /** Decode a value read from BSON back into its runtime representation. */
    fun decode(value: Any?): Any? {
        if (value !is Document) return value
        val uuid = value.getString(UUID_FIELD) ?: return value
        return when (value.getString(TYPE_FIELD)) {
            // Corrupted uuid strings fall back to the raw document instead of failing the load
            PLAYER_TYPE -> runCatching { PlayerVariable(UUID.fromString(uuid)) }.getOrDefault(value)
            ENTITY_TYPE -> runCatching { EntityVariable(UUID.fromString(uuid)) }.getOrDefault(value)
            else -> value
        }
    }
}
