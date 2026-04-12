package com.opencreativeplus.plugin.event

import com.opencreativeplus.api.plot.ModeManager
import com.opencreativeplus.api.plot.PlotManager
import com.opencreativeplus.api.plot.PlotMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
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
                    eventData = mapOf("player" to player),
                    player = player
                )
            } catch (e: Exception) {
                System.err.println("[OCP] Error handling PlayerJoinEvent for ${player.name}: ${e.message}")
            }
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        scope.launch {
            try {
                val plot = plotManager.getPlayerPlot(player.uniqueId) ?: return@launch
                if (modeManager.getCurrentMode(player, plot) != PlotMode.PLAY) return@launch

                val eventData = buildMap<String, Any> {
                    put("player", player)
                    put("action", event.action.name)
                    event.clickedBlock?.let { put("block", it) }
                }

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
}
