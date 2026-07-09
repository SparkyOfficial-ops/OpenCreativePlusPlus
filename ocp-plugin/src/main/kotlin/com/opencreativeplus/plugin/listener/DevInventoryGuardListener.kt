package com.opencreativeplus.plugin.listener

import com.opencreativeplus.plugin.inventory.InventoryManager
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitRunnable

/**
 * Keeps the DEV mode inventory intact.
 *
 * Rules:
 * 1. Players in DEV mode cannot drop items (DROP_ALL / DROP_ONE clicks and Q key).
 * 2. Players in DEV mode cannot pick up items dropped in the world (prevents inventory pollution).
 * 3. After any inventory interaction in DEV mode, a 1-tick delayed check runs.
 *    If ANY provisioned slot is missing, the entire inventory is cleared and re-provisioned.
 *    This catches creative-mode drag, shift-click into crafting table, etc.
 * 4. On disconnect the DEV mark is cleaned up.
 */
class DevInventoryGuardListener(
    private val inventoryManager: InventoryManager,
    private val plugin: Plugin
) : Listener {

    // -------------------------------------------------------------------------
    // 1. Block drops from DEV inventory
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        val player = event.player
        if (!inventoryManager.isPlayerInDev(player)) return
        // Cancel the drop and give the item back
        event.isCancelled = true
        player.sendMessage("§c[OCP] Нельзя выбросить предметы в DEV-режиме.")
    }

    // -------------------------------------------------------------------------
    // 2. Block pickup in DEV mode (keep inventory clean)
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (!inventoryManager.isPlayerInDev(player)) return
        event.isCancelled = true
    }

    // -------------------------------------------------------------------------
    // 3. Detect missing items and reprovision
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!inventoryManager.isPlayerInDev(player)) return

        // Block moving items OUT of the player inventory into another container
        if (event.clickedInventory?.type == InventoryType.PLAYER &&
            event.inventory.type != InventoryType.PLAYER) {
            event.isCancelled = true
            return
        }

        // Schedule integrity check 1 tick later (after Bukkit has processed the click)
        scheduleIntegrityCheck(player)
    }

    // -------------------------------------------------------------------------
    // 4. Cleanup on disconnect
    // -------------------------------------------------------------------------

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        inventoryManager.unmarkPlayerInDev(event.player)
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private fun scheduleIntegrityCheck(player: Player) {
        object : BukkitRunnable() {
            override fun run() {
                if (!player.isOnline) return
                if (!inventoryManager.isPlayerInDev(player)) return
                if (inventoryManager.isDevInventoryIncomplete(player)) {
                    player.inventory.clear()
                    inventoryManager.provisionDevInventory(player)
                    player.sendMessage("§e[OCP] Инвентарь DEV-режима восстановлен.")
                }
            }
        }.runTaskLater(plugin, 1L)
    }
}
