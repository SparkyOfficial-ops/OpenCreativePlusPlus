package com.opencreativeplus.core.trace

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Trace Mode for players — visual debugging of script execution flow.
 *
 * s: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7
 */

/**
 * Represents a line of code (glass strip) in the visual scripting system.
 * Used by [TraceManager.onCodeLineExecute] to highlight the execution path.
 */
data class CodeLine(
    val startLocation: Location,
    val nodes: List<Any>
)

class TraceManager(private val plugin: Plugin) {

    /** playerId → list of active ArmorStand overlays for that player */
    private val tracingPlayers = ConcurrentHashMap<UUID, MutableList<ArmorStand>>()

    /**
     * Returns true if the given player currently has Trace Mode active.
     * s: 14.1
     */
    fun isTracing(playerId: UUID): Boolean = tracingPlayers.containsKey(playerId)

    /**
     * Fast check for the ExecutionEngine hot path — skip all trace work
     * (block lookups, param formatting) while nobody is tracing.
     */
    fun isAnyoneTracing(): Boolean = tracingPlayers.isNotEmpty()

    /**
     * Toggles Trace Mode on/off for [player].
     * Sends "§aTrace Mode ON" or "§cTrace Mode OFF" accordingly.
     * Returns the new state (true = ON, false = OFF).
     * s: 14.1, 14.6
     */
    fun toggle(player: Player): Boolean {
        return if (tracingPlayers.containsKey(player.uniqueId)) {
            clearOverlays(player.uniqueId)
            tracingPlayers.remove(player.uniqueId)
            player.sendMessage("§cTrace Mode OFF")
            false
        } else {
            tracingPlayers[player.uniqueId] = mutableListOf()
            player.sendMessage("§aTrace Mode ON")
            true
        }
    }

    /**
     * Called when a node executes. For each tracing player:
     * - Spawns REDSTONE_DUST particles above the block (if location is non-null)
     * - Spawns an invisible ArmorStand overlay showing node name + up to 3 params (if location is non-null)
     * - Auto-removes the ArmorStand after 60 ticks (3 seconds)
     *
     * May be called from executor threads — all world/entity access is deferred
     * to the main thread via the Bukkit scheduler.
     * s: 14.2, 14.3, 14.4
     */
    fun onNodeExecute(blockLocation: Location?, nodeDisplayName: String, params: Map<String, Any>) {
        if (blockLocation == null || tracingPlayers.isEmpty()) return
        val playerIds = tracingPlayers.keys.toList()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            for (playerId in playerIds) {
                // Player may have toggled trace off between scheduling and execution
                if (!tracingPlayers.containsKey(playerId)) continue
                val player = Bukkit.getPlayer(playerId) ?: continue
                spawnNodeParticles(blockLocation, player)
                spawnOverlay(blockLocation, nodeDisplayName, params, playerId)
            }
        })
    }

    /**
     * Called when a code line (glass strip) executes. For each tracing player:
     * - Spawns ELECTRIC_SPARK particles along the strip at 0.5-block intervals
     * s: 14.5
     */
    fun onCodeLineExecute(codeLine: CodeLine) {
        tracingPlayers.keys.forEach { playerId ->
            val player = Bukkit.getPlayer(playerId) ?: return@forEach
            highlightStrip(codeLine, player)
        }
    }

    /**
     * Called when a node at [location] executes.
     * Spawns REDSTONE_DUST particles at the block location for each tracing player only.
     * Req 8.2, 8.5
     */
    fun onNodeExecute(location: Location) {
        tracingPlayers.keys.forEach { playerId ->
            val player = Bukkit.getPlayer(playerId) ?: return@forEach
            val loc = location.clone().add(0.5, 1.2, 0.5)
            @Suppress("DEPRECATION")
            player.spawnParticle(
                Particle.REDSTONE,
                loc,
                10,
                Particle.DustOptions(Color.RED, 1.0f)
            )
        }
    }

    /**
     * Spawns ELECTRIC_SPARK particles along the straight path from [from] to [to]
     * at 0.5-block intervals, visible only to tracing players.
     * Req 8.3, 8.5
     */
    fun highlightPath(from: Location, to: Location) {
        if (from.world != to.world) return
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val distance = from.distance(to)
        if (distance == 0.0) return
        val steps = (distance / 0.5).toInt().coerceAtLeast(1)
        tracingPlayers.keys.forEach { playerId ->
            val player = Bukkit.getPlayer(playerId) ?: return@forEach
            for (i in 0..steps) {
                val t = i.toDouble() / steps
                val loc = Location(
                    from.world,
                    from.x + dx * t,
                    from.y + dy * t + 0.5,
                    from.z + dz * t
                )
                player.spawnParticle(Particle.ELECTRIC_SPARK, loc, 1)
            }
        }
    }

    /**
     * Called when a script execution completes. Sends a summary message to the tracing player.
     * Format: "§7[Trace] ops=X, time=Yms"
     * s: 14.7
     */
    fun onExecutionComplete(playerId: UUID, operationCount: Int, durationMs: Long) {
        Bukkit.getPlayer(playerId)?.sendMessage(
            "§7[Trace] ops=$operationCount, time=${durationMs}ms"
        )
    }

    /**
     * Removes all active ArmorStand overlays for [playerId].
     * Also removes the player from tracingPlayers so that any pending
     * runTaskLater callbacks become no-ops (they check tracingPlayers[playerId]).
     * s: 14.6
     */
    fun clearOverlays(playerId: UUID) {
        val stands = tracingPlayers[playerId] ?: return
        // Snapshot the list before clearing so runTaskLater callbacks find an empty list
        val snapshot = stands.toList()
        stands.clear()
        snapshot.forEach { it.remove() }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun spawnNodeParticles(blockLocation: Location, player: Player) {
        val loc = blockLocation.clone().add(0.5, 1.2, 0.5)
        player.spawnParticle(
            Particle.REDSTONE,
            loc,
            10,
            Particle.DustOptions(Color.RED, 1.0f)
        )
    }

    private fun spawnOverlay(
        blockLocation: Location,
        name: String,
        params: Map<String, Any>,
        playerId: UUID
    ) {
        val loc = blockLocation.clone().add(0.5, 2.0, 0.5)
        val paramText = params.entries.take(3).joinToString(" ") { "${it.key}=${it.value}" }
        val displayName = "§e$name §7$paramText".trim()

        val stand = blockLocation.world.spawn(loc, ArmorStand::class.java) { armorStand ->
            armorStand.isVisible = false
            armorStand.isMarker = true
            @Suppress("DEPRECATION")
            armorStand.customName = displayName
            armorStand.isCustomNameVisible = true
        }

        tracingPlayers[playerId]?.add(stand)

        // Auto-remove after 60 ticks (3 seconds) — s: 14.4
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            stand.remove()
            // Only remove from list if the player is still tracing (guard against cleared list)
            tracingPlayers[playerId]?.remove(stand)
        }, 60L)
    }

    private fun highlightStrip(codeLine: CodeLine, player: Player) {
        val start = codeLine.startLocation
        val endX = start.blockX + codeLine.nodes.size + 1.0
        var x = start.blockX.toDouble()
        while (x <= endX) {
            val loc = Location(start.world, x, start.y, start.blockZ + 0.5)
            player.spawnParticle(Particle.ELECTRIC_SPARK, loc, 1)
            x += 0.5
        }
    }
}
