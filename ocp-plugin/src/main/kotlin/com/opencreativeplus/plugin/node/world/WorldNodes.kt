package com.opencreativeplus.plugin.node.world

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IValue
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.WeatherType
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("WorldNodes")

/**
 * Sets the block at a specified location to a specified material.
 * Params: "location" (String var name holding a Location), "material" (String Material name)
 * s: 18.1, 18.6
 */
class SetBlockNode(params: Map<String, Any>) : IAction {
    override val nodeId = "set_block"
    override val displayName = "Set Block"
    private val locationVar: String = params["location"] as? String ?: error("location param required")
    private val materialName: String = params["material"] as? String ?: error("material param required")

    override suspend fun execute(context: ExecutionContext) {
        val loc = context.localScope.get(locationVar) as? Location ?: return
        val material = runCatching { Material.valueOf(materialName.uppercase()) }.getOrElse {
            logger.warning("Unknown Material: $materialName")
            return
        }
        context.syncContext { loc.block.type = material }
    }
}

/**
 * Returns the material of the block at a specified location.
 * Params: "location" (String var name holding a Location)
 * s: 18.2
 */
class GetBlockNode(params: Map<String, Any>) : IValue<Material?> {
    override val nodeId = "get_block"
    override val displayName = "Get Block"
    private val locationVar: String = params["location"] as? String ?: error("location param required")

    override suspend fun compute(context: ExecutionContext): Material? {
        val loc = context.localScope.get(locationVar) as? Location ?: return null
        // getBlock().type reads world state — must run on main thread (Bukkit API)
        return context.syncContext { loc.block.type }
    }
}

/**
 * Sets the weather in the plot's world.
 * Params: "weather" (String: "CLEAR", "RAIN", or "THUNDER")
 * s: 18.3
 */
class SetWeatherNode(params: Map<String, Any>) : IAction {
    override val nodeId = "set_weather"
    override val displayName = "Set Weather"
    private val weather: String = params["weather"] as? String ?: "CLEAR"

    override suspend fun execute(context: ExecutionContext) {
        val player = context.player ?: return
        val world = player.world
        context.syncContext {
            when (weather.uppercase()) {
                "CLEAR" -> {
                    world.setStorm(false)
                    world.isThundering = false
                    world.setWeatherDuration(0)
                }
                "RAIN" -> {
                    world.setStorm(true)
                    world.isThundering = false
                }
                "THUNDER" -> {
                    world.setStorm(true)
                    world.isThundering = true
                }
                else -> logger.warning("Unknown weather type: $weather")
            }
        }
    }
}

/**
 * Sets the time of day in the plot's world to a specified tick value.
 * Params: "time" (Long ticks, default 0)
 * s: 18.4
 */
class SetTimeNode(params: Map<String, Any>) : IAction {
    override val nodeId = "set_time"
    override val displayName = "Set Time"
    private val time: Long = when (val t = params["time"]) {
        is Long -> t
        is Int -> t.toLong()
        is Number -> t.toLong()
        else -> 0L
    }

    override suspend fun execute(context: ExecutionContext) {
        val player = context.player ?: return
        val world = player.world
        context.syncContext { world.time = time }
    }
}

/**
 * Creates an explosion at a location with configurable power and flags.
 * Params: "location" (String var name), "power" (Float, default 4.0f),
 *         "fire" (Boolean, default false), "breakBlocks" (Boolean, default false)
 * s: 18.5
 */
class CreateExplosionNode(params: Map<String, Any>) : IAction {
    override val nodeId = "create_explosion"
    override val displayName = "Create Explosion"
    private val locationVar: String = params["location"] as? String ?: error("location param required")
    private val power: Float = when (val p = params["power"]) {
        is Float -> p
        is Double -> p.toFloat()
        is Number -> p.toFloat()
        else -> 4.0f
    }
    private val fire: Boolean = params["fire"] as? Boolean ?: false
    private val breakBlocks: Boolean = params["breakBlocks"] as? Boolean ?: false

    override suspend fun execute(context: ExecutionContext) {
        val loc = context.localScope.get(locationVar) as? Location ?: return
        context.syncContext {
            loc.world?.createExplosion(loc, power, fire, breakBlocks)
        }
    }
}
