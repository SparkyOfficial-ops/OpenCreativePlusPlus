package com.opencreativeplus.plugin.node.entity

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IValue
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity

/**
 * Spawns an entity of the specified type at a location variable.
 * Params: "type" (String EntityType name, default "ZOMBIE"), "location" (String var name)
 * s: 5.1, 5.7
 */
class SpawnEntityNode(params: Map<String, Any>) : IAction {
    override val nodeId = "spawn_entity"
    override val displayName = "Spawn Entity"
    private val entityTypeName: String = params["type"] as? String ?: "ZOMBIE"
    private val locationVar: String = params["location"] as? String ?: error("location param required")

    override suspend fun execute(context: ExecutionContext) {
        val loc = context.localScope.get(locationVar) as? Location ?: return
        val type = runCatching { EntityType.valueOf(entityTypeName.uppercase()) }.getOrElse {
            println("[OCP] SpawnEntityNode: unknown entity type '$entityTypeName'")
            return
        }
        context.syncContext { loc.world?.spawnEntity(loc, type) }
    }
}

/**
 * Removes a target entity from the world.
 * Params: "entity" (String var name holding an Entity)
 * s: 5.2
 */
class KillEntityNode(params: Map<String, Any>) : IAction {
    override val nodeId = "kill_entity"
    override val displayName = "Kill Entity"
    private val entityVar: String = params["entity"] as? String ?: error("entity param required")

    override suspend fun execute(context: ExecutionContext) {
        val entity = context.localScope.get(entityVar) as? Entity ?: return
        context.syncContext { entity.remove() }
    }
}

/**
 * Enables or disables AI for a LivingEntity.
 * Params: "entity" (String var name), "ai" (Boolean, default true)
 * s: 5.3, 5.8
 */
class SetEntityAINode(params: Map<String, Any>) : IAction {
    override val nodeId = "set_entity_ai"
    override val displayName = "Set Entity AI"
    private val entityVar: String = params["entity"] as? String ?: error("entity param required")
    private val aiEnabled: Boolean = params["ai"] as? Boolean ?: true

    override suspend fun execute(context: ExecutionContext) {
        val entity = context.localScope.get(entityVar) as? LivingEntity ?: return
        context.syncContext { entity.setAI(aiEnabled) }
    }
}

/**
 * Sets the health of a LivingEntity, clamped to [0, maxHealth].
 * Params: "entity" (String var name), "health" (Double)
 * s: 5.4
 */
class SetEntityHealthNode(params: Map<String, Any>) : IAction {
    override val nodeId = "set_entity_health"
    override val displayName = "Set Entity Health"
    private val entityVar: String = params["entity"] as? String ?: error("entity param required")
    private val health: Double = params["health"] as? Double ?: 20.0

    override suspend fun execute(context: ExecutionContext) {
        val entity = context.localScope.get(entityVar) as? LivingEntity ?: return
        context.syncContext {
            val maxHp = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0
            val clamped = health.coerceIn(0.0, maxHp)
            entity.health = clamped
        }
    }
}

/**
 * Teleports a target entity to a location variable.
 * Params: "entity" (String var name), "location" (String var name)
 * s: 5.5
 */
class MoveEntityToNode(params: Map<String, Any>) : IAction {
    override val nodeId = "move_entity_to"
    override val displayName = "Move Entity To"
    private val entityVar: String = params["entity"] as? String ?: error("entity param required")
    private val locationVar: String = params["location"] as? String ?: error("location param required")

    override suspend fun execute(context: ExecutionContext) {
        val entity = context.localScope.get(entityVar) as? Entity ?: return
        val loc = context.localScope.get(locationVar) as? Location ?: return
        context.syncContext { entity.teleport(loc) }
    }
}

/**
 * Returns a list of entities within a radius of a location variable.
 * Params: "location" (String var name), "radius" (Double, default 10.0)
 * s: 5.6
 */
class GetNearbyEntitiesNode(params: Map<String, Any>) : IValue<List<Entity>> {
    override val nodeId = "get_nearby_entities"
    override val displayName = "Get Nearby Entities"
    private val locationVar: String = params["location"] as? String ?: error("location param required")
    private val radius: Double = params["radius"] as? Double ?: 10.0

    override suspend fun compute(context: ExecutionContext): List<Entity> {
        val loc = context.localScope.get(locationVar) as? Location ?: return emptyList()
        return loc.world?.getNearbyEntities(loc, radius, radius, radius)?.toList() ?: emptyList()
    }
}
