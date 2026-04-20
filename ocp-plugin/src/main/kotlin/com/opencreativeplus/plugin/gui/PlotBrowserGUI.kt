package com.opencreativeplus.plugin.gui

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.core.database.PlotPersistence
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Chest GUI for browsing, filtering, and teleporting to plots.
 *
 * Displays up to 45 plots sorted by rating (descending) using player skull items.
 * Supports tag-based filtering.
 *
 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 21.1, 21.4, 22.4
 */
class PlotBrowserGUI(
    private val plotPersistence: PlotPersistence,
    private val plotManager: PlotManagerImpl,
    private val scope: CoroutineScope
) : Listener {

    companion object {
        private const val GUI_TITLE = "§8Plot Browser"
        private const val MAX_PLOTS = 45
    }

    /** inventoryId → list of plots shown (for click handling) */
    private val openInventories = ConcurrentHashMap<UUID, List<Plot>>()

    // -------------------------------------------------------------------------
    // Open GUI
    // -------------------------------------------------------------------------

    /**
     * Open the plot browser for [player], optionally filtered by [tagFilter].
     * [page] is zero-based; defaults to 0 (first page).
     10.1, 10.2, 10.3, 21.4, 22.4
     */
    fun open(player: Player, tagFilter: String? = null, page: Int = 0) {
        scope.launch {
            val plots = loadPlots(tagFilter, page)
            val inv = buildInventory(plots)
            // Store mapping so click handler can resolve the plot
            openInventories[player.uniqueId] = plots
            // Inventory must be opened on the main thread
            Bukkit.getScheduler().runTask(
                requireNotNull(Bukkit.getPluginManager().getPlugin("OpenCreativePlus")) {
                    "Plugin not found"
                }
            ) { _ ->
                player.openInventory(inv)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Inventory click handler
    // -------------------------------------------------------------------------

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val inv = event.inventory
        if (inv.viewers.isEmpty() || event.view.title != GUI_TITLE) return

        event.isCancelled = true

        val slot = event.rawSlot
        if (slot < 0 || slot >= MAX_PLOTS) return

        val plots = openInventories[player.uniqueId] ?: return
        val plot = plots.getOrNull(slot) ?: return

        player.closeInventory()
        openInventories.remove(player.uniqueId)

        // Teleport player to the plot's main world (req 10.6)
        scope.launch {
            teleportToPlot(player, plot)
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Load plots from database via paged MongoDB query, sorted by rating descending.
     * Optionally filter by [tagFilter].
     10.2, 21.4, 22.4
     */
    private suspend fun loadPlots(tagFilter: String?, page: Int = 0): List<Plot> {
        return plotPersistence.getPlotsPaged(page, MAX_PLOTS, tagFilter)
    }

    /**
     * Build the chest inventory with one item per plot.
     10.3, 10.4, 10.5
     */
    private fun buildInventory(plots: List<Plot>): Inventory {
        val inv = Bukkit.createInventory(null, MAX_PLOTS, GUI_TITLE)

        plots.forEachIndexed { index, plot ->
            inv.setItem(index, buildPlotItem(plot))
        }

        return inv
    }

    /**
     * Build a player skull item representing [plot].
     * Lore shows name, description, tags, rating, and player count.
     10.3, 10.4, 10.5, 21.1
     */
    private fun buildPlotItem(plot: Plot): ItemStack {
        val skull = ItemStack(Material.PLAYER_HEAD)
        val meta = skull.itemMeta as? SkullMeta ?: return skull

        // Use owner's player profile for the skull texture
        val ownerProfile = Bukkit.getOfflinePlayer(plot.owner)
        meta.owningPlayer = ownerProfile

        meta.setDisplayName("§e${plot.name}")

        val lore = mutableListOf<String>()
        lore.add("§7${plot.description.take(50)}")
        lore.add("")
        if (plot.metadata.tags.isNotEmpty()) {
            lore.add("§bTags: §f${plot.metadata.tags.joinToString(", ")}")
        }
        lore.add("§6Rating: §f${plot.metadata.rating}")
        lore.add("§aPlayers: §f${plot.metadata.currentPlayers}")
        lore.add("")
        lore.add("§7Click to visit!")

        meta.lore = lore
        skull.itemMeta = meta
        return skull
    }

    /**
     * Teleport [player] to [plot]'s main world, loading it if necessary.
     10.6
     */
    private suspend fun teleportToPlot(player: Player, plot: Plot) {
        val worlds = plotManager.ensurePlotLoaded(plot.id)
        val mainWorld = worlds?.first ?: run {
            player.sendMessage("§c[OCP] Could not load plot world.")
            return
        }
        Bukkit.getScheduler().runTask(
            requireNotNull(Bukkit.getPluginManager().getPlugin("OpenCreativePlus")),
            Runnable { player.teleport(mainWorld.spawnLocation) }
        )
    }
}
