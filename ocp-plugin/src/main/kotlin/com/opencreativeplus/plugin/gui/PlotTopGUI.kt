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
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Chest GUI displaying the top 27 plots sorted by rating descending.
 * Supports click-to-teleport and live refresh when ratings change.
 *
 * Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6
 */
class PlotTopGUI(
    private val plotPersistence: PlotPersistence,
    private val plotManager: PlotManagerImpl,
    private val scope: CoroutineScope,
    private val plugin: org.bukkit.plugin.Plugin = requireNotNull(
        org.bukkit.Bukkit.getPluginManager().getPlugin("OpenCreativePlus")
    ) { "OpenCreativePlus plugin not found" }
) : Listener {

    companion object {
        private const val GUI_TITLE = "§8Plot Top"
        private const val MAX_PLOTS = 27
    }

    /** playerUUID → list of plots shown (for click handling) */
    private val openInventories = ConcurrentHashMap<UUID, List<Plot>>()

    /** All players currently viewing this GUI */
    private val viewers = CopyOnWriteArraySet<Player>()

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    /**
     * Load the top 27 plots from the database, sorted by rating descending.
     * Requirements: 8.1, 8.6
     */
    suspend fun loadTop27(): List<Plot> {
        return plotPersistence.getPlotsPaged(0, MAX_PLOTS, null)
    }

    // -------------------------------------------------------------------------
    // Build inventory
    // -------------------------------------------------------------------------

    /**
     * Build a 27-slot inventory (3 rows) with one PLAYER_HEAD per plot.
     * Requirements: 8.2, 8.3
     */
    fun buildInventory(plots: List<Plot>): Inventory {
        val inv = Bukkit.createInventory(null, MAX_PLOTS, GUI_TITLE)
        plots.forEachIndexed { index, plot ->
            inv.setItem(index, buildPlotItem(plot))
        }
        return inv
    }

    // -------------------------------------------------------------------------
    // Open GUI
    // -------------------------------------------------------------------------

    /**
     * Open the Plot Top GUI for [player].
     * Requirements: 8.1
     */
    fun open(player: Player) {
        scope.launch {
            val plots = loadTop27()
            val inv = buildInventory(plots)
            openInventories[player.uniqueId] = plots
            Bukkit.getScheduler().runTask(plugin) { _ ->
                viewers.add(player)
                player.openInventory(inv)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Rating change refresh
    // -------------------------------------------------------------------------

    /**
     * Called when the rating of any plot changes. Schedules a refresh for all
     * current viewers within 5 seconds (100 ticks).
     * Requirements: 8.5
     */
    fun onRatingChange(plotId: UUID) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            // Snapshot viewers to avoid ConcurrentModificationException
            val currentViewers = viewers.filter { it.isOnline && it.openInventory.title == GUI_TITLE }
            currentViewers.forEach { player ->
                open(player)
            }
        }, 100L)
    }

    // -------------------------------------------------------------------------
    // Inventory click handler
    // -------------------------------------------------------------------------

    /**
     * Handle clicks in the Plot Top GUI — cancel the event and teleport the
     * player to the selected plot.
     * Requirements: 8.4
     */
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.view.title != GUI_TITLE) return

        event.isCancelled = true

        val slot = event.rawSlot
        if (slot < 0 || slot >= MAX_PLOTS) return

        val plots = openInventories[player.uniqueId] ?: return
        val plot = plots.getOrNull(slot) ?: return

        player.closeInventory()
        viewers.remove(player)
        openInventories.remove(player.uniqueId)

        scope.launch {
            teleportToPlot(player, plot)
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Build a PLAYER_HEAD item for [plot] with name, owner, rating, and tags in lore.
     * Requirements: 8.2, 8.3
     */
    private fun buildPlotItem(plot: Plot): ItemStack {
        val skull = ItemStack(Material.PLAYER_HEAD)
        val meta = skull.itemMeta as? SkullMeta ?: return skull

        val ownerProfile = Bukkit.getOfflinePlayer(plot.owner)
        meta.owningPlayer = ownerProfile

        meta.setDisplayName("§e${plot.name}")

        val ownerName = ownerProfile.name ?: plot.owner.toString()
        val lore = mutableListOf<String>()
        lore.add("§7Owner: §f$ownerName")
        lore.add("§6Rating: §f${plot.metadata.rating}")
        if (plot.metadata.tags.isNotEmpty()) {
            lore.add("§bTags: §f${plot.metadata.tags.joinToString(", ")}")
        }
        lore.add("")
        lore.add("§7Click to visit!")

        meta.lore = lore
        skull.itemMeta = meta
        return skull
    }

    /**
     * Teleport [player] to [plot]'s main world, loading it if necessary.
     * Requirements: 8.4
     */
    private suspend fun teleportToPlot(player: Player, plot: Plot) {
        val worlds = plotManager.ensurePlotLoaded(plot.id)
        val mainWorld = worlds?.first ?: run {
            player.sendMessage("§c[OCP] Could not load plot world.")
            return
        }
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable { player.teleport(mainWorld.spawnLocation) }
        )
    }
}
