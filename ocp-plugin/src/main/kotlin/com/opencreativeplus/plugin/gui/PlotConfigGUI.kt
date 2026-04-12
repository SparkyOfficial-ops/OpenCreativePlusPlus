package com.opencreativeplus.plugin.gui

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotSettings
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/**
 * GUI for configuring plot settings: biome, time, PvP, mob spawning.
 *
 13.1, 39.1, 39.2, 22.1, 22.2
 */
class PlotConfigGUI(
    private val plotManager: PlotManagerImpl,
    private val scope: CoroutineScope
) : Listener {

    companion object {
        private const val GUI_TITLE = "§8Plot Settings"
        private const val SIZE = 27
    }

    fun open(player: Player, plot: Plot) {
        val inv = Bukkit.createInventory(null, SIZE, GUI_TITLE)

        // Slot 0: Toggle PvP
        inv.setItem(0, makeItem(
            if (plot.settings.pvpEnabled) Material.DIAMOND_SWORD else Material.WOODEN_SWORD,
            "§ePvP: ${if (plot.settings.pvpEnabled) "§aON" else "§cOFF"}",
            listOf("§7Click to toggle")
        ))

        // Slot 1: Toggle mob spawning
        inv.setItem(1, makeItem(
            if (plot.settings.mobSpawningEnabled) Material.ZOMBIE_HEAD else Material.SKELETON_SKULL,
            "§eMob Spawning: ${if (plot.settings.mobSpawningEnabled) "§aON" else "§cOFF"}",
            listOf("§7Click to toggle")
        ))

        // Slot 2: Time — day
        inv.setItem(2, makeItem(Material.SUNFLOWER, "§eSet Time: Day", listOf("§7Sets time to 6000")))

        // Slot 3: Time — night
        inv.setItem(3, makeItem(Material.ENDER_EYE, "§eSet Time: Night", listOf("§7Sets time to 18000")))

        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.view.title != GUI_TITLE) return
        event.isCancelled = true

        scope.launch {
            val plot = plotManager.getPlayerPlot(player.uniqueId) ?: return@launch
            if (!plotManager.canEdit(player, plot)) {
                player.sendMessage("§c[OCP] No permission.")
                return@launch
            }

            val newSettings = when (event.rawSlot) {
                0 -> plot.settings.copy(pvpEnabled = !plot.settings.pvpEnabled)
                1 -> plot.settings.copy(mobSpawningEnabled = !plot.settings.mobSpawningEnabled)
                2 -> plot.settings.copy(timeOfDay = 6000L)
                3 -> plot.settings.copy(timeOfDay = 18000L)
                else -> return@launch
            }

            plotManager.updateSettings(plot.id, newSettings)
            player.closeInventory()
            // Re-open with updated state
            val updated = plotManager.getPlot(plot.id) ?: return@launch
            Bukkit.getScheduler().runTask(
                requireNotNull(Bukkit.getPluginManager().getPlugin("OpenCreativePlus")),
                Runnable { open(player, updated) }
            )
        }
    }

    private fun makeItem(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta: ItemMeta = item.itemMeta ?: return item
        meta.setDisplayName(name)
        meta.lore = lore
        item.itemMeta = meta
        return item
    }
}
