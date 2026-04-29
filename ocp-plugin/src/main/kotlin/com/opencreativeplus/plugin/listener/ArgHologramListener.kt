package com.opencreativeplus.plugin.listener

import com.opencreativeplus.plugin.scanner.BlockScanner
import com.opencreativeplus.plugin.scanner.DataContainer
import com.opencreativeplus.plugin.visualizer.HologramReporter
import org.bukkit.block.Chest
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.plugin.Plugin

/**
 * Listens for chest inventory interactions and updates argument holograms accordingly.
 *
 * When a player closes or clicks inside a chest inventory, the holograms above that
 * chest are refreshed to reflect the current contents (one line per DataContainer arg).
 *
 * The update is deferred by 1 tick via [runTaskLater] so that the inventory state
 * has fully settled before we read it (Req 9.3).
 *
 * Requirements: 9.3
 */
class ArgHologramListener(
    private val plugin: Plugin,
    private val hologramReporter: HologramReporter,
    private val blockScanner: () -> BlockScanner
) : Listener {

    /**
     * Refresh arg holograms when a player closes a chest inventory.
     * Requirements: 9.3
     */
    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val chest = event.inventory.holder as? Chest ?: return
        val chestLocation = chest.location

        // Schedule update in the next tick so inventory state is settled (Req 9.3)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val args = readChestArgs(chest)
            hologramReporter.showArgHolograms(player, chestLocation, args)
        }, 1L)
    }

    /**
     * Refresh arg holograms when a player clicks inside a chest inventory.
     * Requirements: 9.3
     */
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val chest = event.inventory.holder as? Chest ?: return
        val chestLocation = chest.location

        // Schedule update in the next tick so the click has been processed (Req 9.3)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            // Re-read the chest state after the click has been applied
            val updatedChest = chestLocation.block.state as? Chest ?: return@Runnable
            val args = readChestArgs(updatedChest)
            hologramReporter.showArgHolograms(player, chestLocation, args)
        }, 1L)
    }

    /**
     * Reads the non-null [DataContainer] arguments from a chest's inventory.
     *
     * Each non-null item is deserialized via [DataContainer.deserializeFrom].
     * Items that cannot be deserialized (no dc_type PDC key) are skipped.
     */
    private fun readChestArgs(chest: Chest): List<DataContainer> {
        return chest.inventory.contents
            .filterNotNull()
            .mapNotNull { item -> DataContainer.deserializeFrom(item) }
    }
}
