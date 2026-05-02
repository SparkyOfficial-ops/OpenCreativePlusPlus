package com.opencreativeplus.plugin.listener

import com.opencreativeplus.plugin.gui.MyWorldsGUI
import com.opencreativeplus.plugin.gui.WorldBrowserGUI
import kotlinx.coroutines.CoroutineScope
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

/**
 * Listens for item right-clicks to open world navigation GUIs:
 * - COMPASS → WorldBrowserGUI
 * - DIAMOND → MyWorldsGUI
 */
class WorldNavigationListener(
    private val myWorldsGUI: MyWorldsGUI,
    private val worldBrowserGUI: WorldBrowserGUI,
    private val scope: CoroutineScope
) : Listener {

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val item = event.player.inventory.itemInMainHand
        when (item.type) {
            Material.COMPASS -> {
                event.isCancelled = true
                worldBrowserGUI.open(event.player)
            }
            Material.DIAMOND -> {
                event.isCancelled = true
                myWorldsGUI.open(event.player)
            }
            else -> {}
        }
    }
}
