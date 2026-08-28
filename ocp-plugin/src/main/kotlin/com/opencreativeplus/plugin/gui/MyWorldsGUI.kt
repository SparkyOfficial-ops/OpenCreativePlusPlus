package com.opencreativeplus.plugin.gui

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.core.database.PlotPersistence
import com.opencreativeplus.plugin.mode.ModeManagerImpl
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import com.opencreativeplus.plugin.world.WorldManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.Plugin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * GUI showing the player's own plots (54-slot inventory).
 * - Slots 0-44: plot items (GRASS_BLOCK)
 * - Slot 45: "← Back" (ARROW) if page > 0
 * - Slot 49: "Create New World" (EMERALD)
 * - Slot 53: "Next →" (ARROW) if more pages
 *
 * Left-click: teleport to plot
 * Right-click: open WorldSettingsGUI
 * Shift+click: delete plot (with confirmation)
 */
@Suppress("DEPRECATION")
class MyWorldsGUI(
    private val plotManager: PlotManagerImpl,
    private val plotPersistence: PlotPersistence,
    private val modeManager: ModeManagerImpl,
    private val scope: CoroutineScope,
    private val plugin: Plugin,
    private val worldManager: WorldManager? = null
) : Listener {

    companion object {
        const val GUI_TITLE = "§8Мои миры"
        const val CONFIRM_TITLE = "§cУдалить мир?"
        private const val PAGE_SIZE = 45
    }

    /** playerId → list of plots shown on current page */
    private val openInventories = ConcurrentHashMap<UUID, List<Plot>>()
    /** playerId → current page */
    private val playerPages = ConcurrentHashMap<UUID, Int>()
    /** playerId → plotId pending delete confirmation */
    private val pendingDelete = ConcurrentHashMap<UUID, UUID>()

    fun open(player: Player, page: Int = 0) {
        scope.launch {
            val allPlots = plotManager.getPlotsByOwner(player.uniqueId)
            val totalPages = ((allPlots.size - 1) / PAGE_SIZE) + 1
            val safePage = page.coerceIn(0, maxOf(0, totalPages - 1))
            val pagePlots = allPlots.drop(safePage * PAGE_SIZE).take(PAGE_SIZE)

            openInventories[player.uniqueId] = pagePlots
            playerPages[player.uniqueId] = safePage

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

                // Slot 49: Create New World
                inv.setItem(49, makeItem(
                    Material.EMERALD,
                    "§aСоздать новый мир",
                    listOf("§7Нажмите, чтобы создать новый мир")
                ))

                // Slot 53: Next button
                if (allPlots.size > (safePage + 1) * PAGE_SIZE) {
                    inv.setItem(53, makeItem(Material.ARROW, "§7Далее →", emptyList()))
                }

                player.openInventory(inv)
            })
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return

        // Handle confirmation GUI
        if (event.view.title == CONFIRM_TITLE) {
            event.isCancelled = true
            when (event.rawSlot) {
                3 -> {
                    // Confirm delete
                    val plotId = pendingDelete.remove(player.uniqueId) ?: return
                    player.closeInventory()
                    scope.launch {
                        plotManager.deletePlot(plotId, plugin)
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            player.sendMessage("§a[OCP] Мир удалён.")
                        })
                        open(player, playerPages[player.uniqueId] ?: 0)
                    }
                }
                5 -> {
                    // Cancel
                    pendingDelete.remove(player.uniqueId)
                    player.closeInventory()
                    open(player, playerPages[player.uniqueId] ?: 0)
                }
            }
            return
        }

        if (event.view.title != GUI_TITLE) return
        event.isCancelled = true

        val slot = event.rawSlot
        val plots = openInventories[player.uniqueId] ?: return
        val currentPage = playerPages[player.uniqueId] ?: 0

        when {
            slot in 0 until PAGE_SIZE -> {
                val plot = plots.getOrNull(slot) ?: return
                when (event.click) {
                    ClickType.LEFT -> {
                        // Teleport to plot
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
                    ClickType.RIGHT -> {
                        // Open WorldSettingsGUI
                        player.closeInventory()
                        val settingsGUI = WorldSettingsGUI(plotManager, scope, plugin, this@MyWorldsGUI)
                        plugin.server.pluginManager.registerEvents(settingsGUI, plugin)
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            settingsGUI.open(player, plot, currentPage)
                        })
                    }
                    ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT -> {
                        // Delete with confirmation
                        if (plot.owner != player.uniqueId) {
                            player.sendMessage("§c[OCP] Вы не можете удалить чужой мир.")
                            return
                        }
                        pendingDelete[player.uniqueId] = plot.id
                        player.closeInventory()
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            openConfirmDelete(player)
                        })
                    }
                    else -> {}
                }
            }
            slot == 45 && currentPage > 0 -> {
                // Back
                open(player, currentPage - 1)
            }
            slot == 49 -> {
                // Open template selector GUI
                val wm = worldManager
                if (wm == null) {
                    player.sendMessage("§c[OCP] World manager not available.")
                    return
                }
                player.closeInventory()
                val templateGUI = TemplateSelectorGUI(plotManager, wm, scope, plugin)
                plugin.server.pluginManager.registerEvents(templateGUI, plugin)
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    templateGUI.open(player)
                })
            }
            slot == 53 -> {
                // Next page
                open(player, currentPage + 1)
            }
        }
    }

    private fun openConfirmDelete(player: Player) {
        val inv = Bukkit.createInventory(null, 9, CONFIRM_TITLE)
        inv.setItem(3, makeItem(
            Material.RED_CONCRETE,
            "§cПодтвердить удаление",
            listOf("§7Это действие нельзя отменить!")
        ))
        inv.setItem(5, makeItem(Material.GREEN_CONCRETE, "§aОтмена", emptyList()))
        player.openInventory(inv)
    }

    private fun buildPlotItem(plot: Plot): ItemStack {
        val item = ItemStack(Material.GRASS_BLOCK)
        val meta: ItemMeta = item.itemMeta ?: return item
        meta.setDisplayName("§e${plot.name}")

        val dateFormat = SimpleDateFormat("dd.MM.yyyy")
        val createdDate = dateFormat.format(Date(plot.createdAt))
        val statusText = if (plot.settings.isPublic) "§aПубличный" else "§7Приватный"

        meta.lore = listOf(
            "§7${plot.description.take(50)}",
            "",
            "§7Статус: $statusText",
            "§7Доверенных игроков: §f${plot.trustedPlayers.size}",
            "§7Создан: §f$createdDate",
            "",
            "§aЛКМ §7— войти",
            "§eПКМ §7— настройки",
            "§cShift+ЛКМ §7— удалить"
        )
        item.itemMeta = meta
        return item
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
