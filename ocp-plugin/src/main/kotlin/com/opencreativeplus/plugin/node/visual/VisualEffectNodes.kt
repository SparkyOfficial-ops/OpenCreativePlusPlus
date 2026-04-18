package com.opencreativeplus.plugin.node.visual

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle

/**
 * Spawns a particle effect at a location, visible to all players within 64 blocks.
 * Params: "location" (String var name), "particle" (String OcpParticle name, default "FLAME"),
 *         "count" (Int, default 10), "spread" (Double, default 0.5)
 * s: 6.1, 6.6
 */
class SpawnParticleNode(params: Map<String, Any>) : IAction {
    override val nodeId = "spawn_particle"
    override val displayName = "Spawn Particle"
    private val locationVar: String = params["location"] as? String ?: error("location param required")
    private val particleName: String = params["particle"] as? String ?: "FLAME"
    private val count: Int = params["count"] as? Int ?: 10
    private val spread: Double = params["spread"] as? Double ?: 0.5

    override suspend fun execute(context: ExecutionContext) {
        val loc = context.localScope.get(locationVar) as? Location ?: return
        val ocpParticle = OcpParticle.fromName(particleName) ?: run {
            println("[OCP] SpawnParticleNode: unknown particle '$particleName'")
            return
        }
        val particle = ocpParticle.bukkitParticle
        // Spawn for all players within 64 blocks
        loc.world?.players
            ?.filter { it.location.distanceSquared(loc) <= 64.0 * 64.0 }
            ?.forEach { player ->
                if (particle == Particle.REDSTONE) {
                    player.spawnParticle(particle, loc, count, spread, spread, spread,
                        Particle.DustOptions(Color.RED, 1.0f))
                } else {
                    player.spawnParticle(particle, loc, count, spread, spread, spread)
                }
            }
    }
}

/**
 * Plays a sound at a location, audible to all players within the sound's range.
 * Params: "location" (String var name), "sound" (String OcpSound name, default "UI_BUTTON_CLICK"),
 *         "volume" (Float, default 1.0), "pitch" (Float, default 1.0)
 * s: 6.2, 6.7
 */
class PlaySoundNode(params: Map<String, Any>) : IAction {
    override val nodeId = "play_sound"
    override val displayName = "Play Sound"
    private val locationVar: String = params["location"] as? String ?: error("location param required")
    private val soundName: String = params["sound"] as? String ?: "UI_BUTTON_CLICK"
    private val volume: Float = (params["volume"] as? Number)?.toFloat() ?: 1.0f
    private val pitch: Float = (params["pitch"] as? Number)?.toFloat() ?: 1.0f

    override suspend fun execute(context: ExecutionContext) {
        val loc = context.localScope.get(locationVar) as? Location ?: return
        val ocpSound = OcpSound.fromName(soundName) ?: run {
            println("[OCP] PlaySoundNode: unknown sound '$soundName'")
            return
        }
        loc.world?.playSound(loc, ocpSound.bukkitSound, volume, pitch)
    }
}

/**
 * Draws a line of particles between two location variables at 0.5-block intervals.
 * Params: "from" (String var name), "to" (String var name),
 *         "particle" (String OcpParticle name, default "FLAME")
 * s: 6.3
 */
class DrawLineNode(params: Map<String, Any>) : IAction {
    override val nodeId = "draw_line"
    override val displayName = "Draw Line"
    private val fromVar: String = params["from"] as? String ?: error("from param required")
    private val toVar: String = params["to"] as? String ?: error("to param required")
    private val particleName: String = params["particle"] as? String ?: "FLAME"

    override suspend fun execute(context: ExecutionContext) {
        val from = context.localScope.get(fromVar) as? Location ?: return
        val to = context.localScope.get(toVar) as? Location ?: return
        val ocpParticle = OcpParticle.fromName(particleName) ?: run {
            println("[OCP] DrawLineNode: unknown particle '$particleName'")
            return
        }
        val particle = ocpParticle.bukkitParticle
        val world = from.world ?: return

        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val distance = from.distance(to)
        if (distance == 0.0) return

        val steps = (distance / 0.5).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val loc = Location(world, from.x + dx * t, from.y + dy * t, from.z + dz * t)
            if (particle == Particle.REDSTONE) {
                world.spawnParticle(particle, loc, 1, Particle.DustOptions(Color.RED, 1.0f))
            } else {
                world.spawnParticle(particle, loc, 1)
            }
        }
    }
}
