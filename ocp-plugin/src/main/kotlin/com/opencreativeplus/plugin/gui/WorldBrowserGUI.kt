package com.opencreativeplus.plugin.gui

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.core.database.PlotPersistence
import com.opencreativeplus.plugin.input.SignInputManager
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
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * GUI showing all public plots (54-slot inventory).
 * - Slots 0-44: plot items (PLAYER_HEAD with owner skin)
 * - Slot 45: "← Back" (ARROW) if page > 0
 * - Slot 46: "🔍 Поиск" (SPYGLASS)
 * - Slot 47: Sort mode toggle (COMPARATOR)
 * - Slot 48: Filter toggle (HOPPER)
 * - Slot 53: "Next →" (ARROW) if more pages
 */
@Suppress("DEPRECATION")
class WorldBrowserGUI(
    private val plotManager: PlotManagerImpl,
    private val plotPersistence: PlotPersistence,
    private val signInputManager: SignInputManager,
    private val scope: CoroutineScope,
    private val plugin: Plugin
) : Listener {

    companion object {
        const val GUI_TITLE = "§8Браузер миров"
        private const val PAGE_SIZE = 45
    }

    enum class SortMode { RATING, PLAYERS, NEWEST }

    /** playerId → list of plots shown on current page */
    private val openInventories = ConcurrentHashMap<UUID, List<Plot>>()
    /** playerId → current page */
    private val playerPages = ConcurrentHashMap<UUID, Int>()
    /** playerId → current sort mode */
    private val playerSortModes = ConcurrentHashMap<UUID, SortMode>()
    /** playerId → current search query */
    private val playerSearchQueries = ConcurrentHashMap<UUID, String?>()

    fun open(player: Player, page: Int = 0, search: String? = null, sort: SortMode = SortMode.RATING) {
        scope.launch {
            val allPublicPlots = loadPublicPlots(search, sort)
            val totalPages = if (allPublicPlots.isEmpty()) 1 else ((allPublicPlots.size - 1) / PAGE_SIZE) + 1
            val safePage = page.coerceIn(0, maxOf(0, totalPages - 1))
            val pagePlots = allPublicPlots.drop(safePage * PAGE_SIZE).take(PAGE_SIZE)

            openInventories[player.uniqueId] = pagePlots
            playerPages[player.uniqueId] = safePage
            playerSortModes[player.uniqueId] = sort
            playerSearchQueries[player.uniqueId] = search

            Bukkit.getScheduler().runTask(plugin, Runnable {
                val inv = Bukkit.createInventory(null, 54, GUI_TITLE)

                // Plot items in slots 0-44
                pagePlots.forEachIndexed { index, plot ->
                    inv.setItem(index, buildPlotItem(plot))
                }

                // Slot 45: Back button
                if (safePage > 0) {
                    inv.setItem(45, makeItem(Material.ARROW, "§7← Назад", emptyList()))
                }

                // Slot 46: Search
                val searchLabel = if (search != null) "§e🔍 Поиск: §f$search" else "§e🔍 Поиск"
                inv.setItem(46, makeItem(
                    Material.SPYGLASS,
                    searchLabel,
                    listOf("§7Нажмите для поиска по названию")
                ))

                // Slot 47: Sort mode
                val sortLabel = when (sort) {
                    SortMode.RATING  -> "§eСортировка: §fРейтинг"
                    SortMode.PLAYERS -> "§eСортировка: §fИгроки"
                    SortMode.NEWEST  -> "§eСортировка: §fНовые"
                }
                inv.setItem(47, makeItem(
                    Material.COMPARATOR,
                    sortLabel,
                    listOf("§7Нажмите для смены сортировки")
                ))

                // Slot 48: Filter (currently always public)
                inv.setItem(48, makeItem(
                    Material.HOPPER,
                    "§eФильтр: §fПубличные",
                    listOf("§7Показаны только публичные миры")
                ))

                // Slot 53: Next button
                if (allPublicPlots.size > (safePage + 1) * PAGE_SIZE) {
                    inv.setItem(53, makeItem(Material.ARROW, "§7Далее →", emptyList()))
                }

                player.openInventory(inv)
            })
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.view.title != GUI_TITLE) return
        event.isCancelled = true

        val slot = event.rawSlot
        val plots = openInventories[player.uniqueId] ?: return
        val currentPage = playerPages[player.uniqueId] ?: 0
        val currentSort = playerSortModes[player.uniqueId] ?: SortMode.RATING
        val currentSearch = playerSearchQueries[player.uniqueId]

        when {
            slot in 0 until PAGE_SIZE -> {
                val plot = plots.getOrNull(slot) ?: return
                player.closeInventory()
                scope.launch {
                    val worlds = plotManager.ensurePlotLoaded(plot.id)
                    val mainWorld = worlds?.first ?: run {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            player.sendMessage("§c[OCP] Не удалось загрузить мир.")
                        })
                        return@launch
                    }
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        player.teleport(mainWorld.spawnLocation)
                    })
                }
            }
            slot == 45 && currentPage > 0 -> {
                open(player, currentPage - 1, currentSearch, currentSort)
            }
            slot == 46 -> {
                // Search
                player.closeInventory()
                scope.launch {
                    val query = signInputManager.awaitSignInput(player, "Поиск...")
                    if (query != null && query.isNotBlank()) {
                        open(player, 0, query, currentSort)
                    } else {
                        open(player, 0, null, currentSort)
                    }
                }
            }
            slot == 47 -> {
                // Cycle sort mode
                val nextSort = when (currentSort) {
                    SortMode.RATING  -> SortMode.PLAYERS
                    SortMode.PLAYERS -> SortMode.NEWEST
                    SortMode.NEWEST  -> SortMode.RATING
                }
                open(player, 0, currentSearch, nextSort)
            }
            slot == 48 -> {
                // Filter toggle (currently only public — no-op for now)
                open(player, currentPage, currentSearch, currentSort)
            }
            slot == 53 -> {
                open(player, currentPage + 1, currentSearch, currentSort)
            }
        }
    }

    private suspend fun loadPublicPlots(search: String?, sort: SortMode): List<Plot> {
        val plots = if (search != null && search.isNotBlank()) {
            plotPersistence.searchPlots(search, 0, 1000)
        } else {
            plotPersistence.getPlotsPaged(0, 1000)
        }

        val publicPlots = plots.filter { it.settings.isPublic }

        return when (sort) {
            SortMode.RATING  -> publicPlots.sortedByDescending { it.metadata.rating }
            SortMode.PLAYERS -> publicPlots.sortedByDescending { it.metadata.currentPlayers }
            SortMode.NEWEST  -> publicPlots.sortedByDescending { it.createdAt }
        }
    }

    private fun buildPlotItem(plot: Plot): ItemStack {
        val skull = ItemStack(Material.PLAYER_HEAD)
        val meta = skull.itemMeta as? SkullMeta ?: return skull

        val ownerProfile = Bukkit.getOfflinePlayer(plot.owner)
        meta.owningPlayer = ownerProfile

        meta.setDisplayName("§e${plot.name}")

        val lore = mutableListOf<String>()
        lore.add("§7Владелец: §f${ownerProfile.name ?: plot.owner.toString().take(8)}")
        if (plot.description.isNotBlank()) {
            lore.add("§7${plot.description.take(50)}")
        }
        lore.add("")
        if (plot.metadata.tags.isNotEmpty()) {
            lore.add("§bТеги: §f${plot.metadata.tags.joinToString(", ")}")
        }
        lore.add("§6Рейтинг: §f${plot.metadata.rating}")
        lore.add("§aИгроков: §f${plot.metadata.currentPlayers}")
        lore.add("")
        lore.add("§7Нажмите, чтобы посетить!")

        meta.lore = lore
        skull.itemMeta = meta
        return skull
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
