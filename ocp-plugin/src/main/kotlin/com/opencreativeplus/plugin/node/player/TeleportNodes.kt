package com.opencreativeplus.plugin.node.player

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.core.execution.ScriptExecutionException
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.Vector

/**
 * Teleports the current target player to a specified location variable.
 * Params: "location" (String var name holding a Location)
 * s: 15.1, 15.5
 */
class TeleportPlayerNode(params: Map<String, Any>) : IAction {
    override val nodeId = "teleport_player"
    override val displayName = "Teleport Player"
    private val locationVar: String = params["location"] as? String ?: error("location param required")

    override suspend fun execute(context: ExecutionContext) {
        val player = context.currentTarget as? Player
            ?: throw ScriptExecutionException("Блок Teleport: цель не является игроком. Убедитесь, что перед этим блоком стоит 'Выбор игрока'.")
        val loc = context.localScope.get(locationVar) as? Location
            ?: throw ScriptExecutionException("Блок Teleport: переменная '$locationVar' не найдена или не является локацией.")
        context.syncContext { player.teleport(loc) }
    }
}

/**
 * Teleports one player to another player's current location.
 * Params: "player" (String var name, the player to teleport), "target" (String var name, the player to teleport to)
 * s: 15.2, 15.5
 */
class TeleportToPlayerNode(params: Map<String, Any>) : IAction {
    override val nodeId = "teleport_to_player"
    override val displayName = "Teleport To Player"
    private val playerVar: String = params["player"] as? String ?: error("player param required")
    private val targetVar: String = params["target"] as? String ?: error("target param required")

    override suspend fun execute(context: ExecutionContext) {
        val player = context.localScope.get(playerVar) as? Player
            ?: throw ScriptExecutionException("Блок Teleport To Player: переменная '$playerVar' не найдена или не является игроком.")
        val target = context.localScope.get(targetVar) as? Player
            ?: throw ScriptExecutionException("Блок Teleport To Player: переменная '$targetVar' (цель) не найдена или не является игроком.")
        context.syncContext { player.teleport(target.location) }
    }
}

/**
 * Applies a velocity vector to the current target player.
 * Params: "x" (Double, default 0.0), "y" (Double, default 0.0), "z" (Double, default 0.0)
 * s: 15.3
 */
class LaunchPlayerNode(params: Map<String, Any>) : IAction {
    override val nodeId = "launch_player"
    override val displayName = "Launch Player"
    private val vx: Double = params["x"] as? Double ?: 0.0
    private val vy: Double = params["y"] as? Double ?: 0.0
    private val vz: Double = params["z"] as? Double ?: 0.0

    override suspend fun execute(context: ExecutionContext) {
        val player = context.currentTarget as? Player
            ?: throw ScriptExecutionException("Блок Launch Player: цель не является игроком.")
        context.syncContext { player.velocity = Vector(vx, vy, vz) }
    }
}

/**
 * Enables or disables flight for the current target player.
 * Params: "flight" (Boolean, default true)
 * s: 15.4
 */
class SetPlayerFlightNode(params: Map<String, Any>) : IAction {
    override val nodeId = "set_player_flight"
    override val displayName = "Set Player Flight"
    private val flightEnabled: Boolean = params["flight"] as? Boolean ?: true

    override suspend fun execute(context: ExecutionContext) {
        val player = context.currentTarget as? Player
            ?: throw ScriptExecutionException("Блок Set Flight: цель не является игроком.")
        context.syncContext {
            player.allowFlight = flightEnabled
            player.isFlying = flightEnabled
        }
    }
}
