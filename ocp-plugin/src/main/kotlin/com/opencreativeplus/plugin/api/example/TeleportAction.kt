package com.opencreativeplus.plugin.api.example

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction

/**
 * Example extension action: teleports the player to a named location.
 *
 * Registration example:
 * ```kotlin
 * OpenCreativePlusAPI.getInstance().registerAction(Material.ENDER_PEARL) { params ->
 *     TeleportAction(params)
 * }
 * ```
 *
 * Requirements: 11.1, 11.2
 */
class TeleportAction(params: Map<String, Any>) : IAction {

    private val worldName: String = params["world"] as? String ?: "world"
    private val x: Double = (params["x"] as? Number)?.toDouble() ?: 0.0
    private val y: Double = (params["y"] as? Number)?.toDouble() ?: 64.0
    private val z: Double = (params["z"] as? Number)?.toDouble() ?: 0.0

    override suspend fun execute(context: ExecutionContext) {
        val player = context.player ?: return
        context.syncContext {
            val world = org.bukkit.Bukkit.getWorld(worldName) ?: return@syncContext
            player.teleport(org.bukkit.Location(world, x, y, z))
        }
    }
}
