package com.opencreativeplus.api.model

import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID

/**
 * UUID-safe wrapper around a [Player] reference stored in a variable scope.
 * Holds only the player's [UUID] — never a live [Player] object — so scopes
 * cannot leak memory when the player disconnects.
 *
 * gameready-enhancements Req 2.7
 */
@JvmInline
value class PlayerVariable(val uuid: UUID)

/**
 * UUID-safe wrapper around an [Entity] reference stored in a variable scope.
 * Holds only the entity's [UUID] — never a live [Entity] object — so scopes
 * cannot leak memory when the entity is despawned.
 *
 * gameready-enhancements Req 2.8
 */
@JvmInline
value class EntityVariable(val uuid: UUID)

/**
 * Convert a runtime value into its storable form before writing to a scope:
 * [Player] becomes [PlayerVariable], [Entity] becomes [EntityVariable],
 * everything else is stored as-is.
 *
 * Note: [Player] is checked first because it is a subtype of [Entity].
 *
 * gameready-enhancements Req 2.1, 2.2
 */
fun Any.toStorable(): Any = when (this) {
    is Player -> PlayerVariable(this.uniqueId)
    is Entity -> EntityVariable(this.uniqueId)
    else -> this
}
