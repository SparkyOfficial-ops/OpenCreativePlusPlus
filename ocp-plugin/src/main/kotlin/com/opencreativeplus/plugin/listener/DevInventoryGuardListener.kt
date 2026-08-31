package com.opencreativeplus.plugin.listener

import com.opencreativeplus.plugin.inventory.InventoryManager
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
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
 * 1. Players in DEV mode cannot drop items (Q key, Ctrl+Q, DROP_ALL / DROP_ONE clicks, drag out).
 * 2. Players in DEV mode cannot pick up items dropped in the world (prevents inventory pollution).
 * 3. Players in DEV mode cannot move items OUT of the player inventory into another container
 *    (shift-click, number-key swap) or drag items into the opened container.
 * 4. After any inventory interaction in DEV mode, a 1-tick delayed check runs.
 *    If ANY provisioned slot is missing, the entire inventory is cleared and re-provisioned.
 *    This catches creative-mode drag, shift-click into crafting table, etc.
 * 5. On disconnect the DEV mark is cleaned up.
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

        // Q / Ctrl+Q inside the inventory fires InventoryClickEvent, not PlayerDropItemEvent —
        // cancel explicitly so coding blocks never end up on the ground.
        if (event.click == ClickType.DROP || event.click == ClickType.CONTROL_DROP) {
            event.isCancelled = true
            player.sendMessage("§c[OCP] Нельзя выбросить предметы в DEV-режиме.")
            return
        }

        // Block moving items OUT of the player inventory into another container
        if (event.clickedInventory?.type == InventoryType.PLAYER &&
            event.inventory.type != InventoryType.PLAYER) {
            event.isCancelled = true
            return
        }

        // Schedule integrity check 1 tick later (after Bukkit has processed the click)
        scheduleIntegrityCheck(player)
    }

    /**
     * InventoryDragEvent fires instead of InventoryClickEvent for multi-slot drags.
     * A drag while a container is open can cross inventories (player slots → container
     * slots), bypassing the click guard — cancel any drag that touches the opened
     * container's slots.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!inventoryManager.isPlayerInDev(player)) return

        val topSize = event.inventory.size
        if (event.inventory.type != InventoryType.PLAYER && event.rawSlots.any { it < topSize }) {
            event.isCancelled = true
            scheduleIntegrityCheck(player)
        }
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
