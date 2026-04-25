package com.opencreativeplus.plugin.event

import com.opencreativeplus.api.plot.ModeManager
import com.opencreativeplus.api.plot.PlotManager
import com.opencreativeplus.api.plot.PlotMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent

/**
 * Bukkit event listener that bridges Minecraft events to the [EventDispatcher].
 *
 * Only dispatches events when the plot is in PLAY mode (req 16.1).
 * Exceptions are caught per-handler to avoid affecting other listeners (req 16.5, 38.1).
 *
 16.1, 16.5, 38.1
 */
class PlotEventListener(
    private val eventDispatcher: EventDispatcher,
    private val plotManager: PlotManager,
    private val modeManager: ModeManager,
    private val scope: CoroutineScope
) : Listener {

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        scope.launch {
            try {
                val plot = plotManager.getPlayerPlot(player.uniqueId) ?: return@launch
                if (modeManager.getCurrentMode(player, plot) != PlotMode.PLAY) return@launch

                eventDispatcher.dispatchEvent(
                    plotId = plot.id,
                    eventType = "player_join",
                    eventData = mapOf("player" to player.name),
                    player = player
                )
            } catch (e: Exception) {
                System.err.println("[OCP] Error handling PlayerJoinEvent for ${player.name}: ${e.message}")
            }
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        // Req 4.5: skip air clicks (no associated block)
        val clickedBlock = event.clickedBlock ?: return

        val player = event.player
        scope.launch {
            try {
                val plot = plotManager.getPlayerPlot(player.uniqueId) ?: return@launch
                if (modeManager.getCurrentMode(player, plot) != PlotMode.PLAY) return@launch

                val eventData = mapOf<String, Any>(
                    "player" to player.name,
                    "block" to clickedBlock.location.toString(),
                    "action" to event.action.name
                )

                eventDispatcher.dispatchEvent(
                    plotId = plot.id,
                    eventType = "player_interact",
                    eventData = eventData,
                    player = player
                )
            } catch (e: Exception) {
                System.err.println("[OCP] Error handling PlayerInteractEvent for ${player.name}: ${e.message}")
            }
        }
    }

    /**
     * Req 3.1–3.3, 3.6: Dispatch EntityDamageByEntityEvent as "player_damage".
     * Resolves the shooter when the damager is a Projectile.
     */
    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        scope.launch {
            try {
                val victim = event.entity
                val rawDamager = event.damager

                // Req 3.6: resolve shooter for projectiles
                val damager = if (rawDamager is Projectile) {
                    rawDamager.shooter as? org.bukkit.entity.Entity ?: rawDamager
                } else {
                    rawDamager
                }

                // Find the plot by the victim's location world
                val player = victim as? org.bukkit.entity.Player
                val plotId = if (player != null) {
                    plotManager.getPlayerPlot(player.uniqueId)?.id
                } else {
                    null
                } ?: return@launch

                val plot = plotManager.getPlot(plotId) ?: return@launch
                val ownerPlayer = org.bukkit.Bukkit.getPlayer(plot.owner)
                if (ownerPlayer != null && modeManager.getCurrentMode(ownerPlayer, plot) != PlotMode.PLAY) return@launch

                val eventData = mapOf<String, Any>(
                    "victim" to victim.name,
                    "damager" to damager.name,
                    "damage" to event.damage
                )

                eventDispatcher.dispatchEvent(
                    plotId = plotId,
                    eventType = "player_damage",
                    eventData = eventData,
                    player = player
                )
            } catch (e: Exception) {
                System.err.println("[OCP] Error handling EntityDamageByEntityEvent: ${e.message}")
            }
        }
    }

    /**
     * Req 5.1–5.3: Dispatch PlayerDeathEvent as "player_death".
     */
    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val victim = event.entity
        scope.launch {
            try {
                val plot = plotManager.getPlayerPlot(victim.uniqueId) ?: return@launch
                if (modeManager.getCurrentMode(victim, plot) != PlotMode.PLAY) return@launch

                val killer: String = event.entity.killer?.name ?: "none"

                val eventData = mapOf<String, Any>(
                    "victim" to victim.name,
                    "killer" to killer
                )

                eventDispatcher.dispatchEvent(
                    plotId = plot.id,
                    eventType = "player_death",
                    eventData = eventData,
                    player = victim
                )
            } catch (e: Exception) {
                System.err.println("[OCP] Error handling PlayerDeathEvent for ${victim.name}: ${e.message}")
            }
        }
    }

    /**
     * Req 5.1–5.3: Dispatch EntityDeathEvent as "player_death" for non-player entities.
     */
    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        // Skip players — handled by onPlayerDeath
        if (event.entity is org.bukkit.entity.Player) return

        val victim = event.entity
        scope.launch {
            try {
                val killer = victim.killer
                val killerPlayer = killer as? org.bukkit.entity.Player

                // Resolve plot via the killer player if available
                val plotId = if (killerPlayer != null) {
                    plotManager.getPlayerPlot(killerPlayer.uniqueId)?.id
                } else {
                    null
                } ?: return@launch

                val plot = plotManager.getPlot(plotId) ?: return@launch
                val ownerPlayer = org.bukkit.Bukkit.getPlayer(plot.owner)
                if (ownerPlayer != null && modeManager.getCurrentMode(ownerPlayer, plot) != PlotMode.PLAY) return@launch

                val killerName: String = killer?.name ?: "none"

                val eventData = mapOf<String, Any>(
                    "victim" to victim.name,
                    "killer" to killerName
                )

                eventDispatcher.dispatchEvent(
                    plotId = plotId,
                    eventType = "player_death",
                    eventData = eventData,
                    player = killerPlayer
                )
            } catch (e: Exception) {
                System.err.println("[OCP] Error handling EntityDeathEvent for ${victim.name}: ${e.message}")
            }
        }
    }
}
