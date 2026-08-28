package com.opencreativeplus.plugin.gui

import com.opencreativeplus.core.world.PlotTemplate
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import com.opencreativeplus.plugin.world.WorldManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.Plugin

/**
 * GUI for selecting a world template when creating a new plot.
 * Opened from MyWorldsGUI when the player clicks the "Create New World" emerald.
 *
 * Layout (27 slots):
 *   Slot 11: Void world (STRUCTURE_VOID)
 *   Slot 13: Flat world (GRASS_BLOCK)
 *   Slot 15: Survival world (OAK_SAPLING)
 */
@Suppress("DEPRECATION")
class TemplateSelectorGUI(
    private val plotManager: PlotManagerImpl,
    private val worldManager: WorldManager,
    private val scope: CoroutineScope,
    private val plugin: Plugin
) : Listener {

    companion object {
        const val TITLE = "Выберите шаблон мира"
    }

    fun open(player: Player) {
        val inv = Bukkit.createInventory(null, 27, Component.text("§8$TITLE"))

        // Slot 11: Void
        inv.setItem(11, makeItem(
            Material.STRUCTURE_VOID,
            "§bПустой мир (Void)",
            listOf("§7Каменная платформа 3х3", "§7Идеально для чистого творчества")
        ))

        // Slot 13: Flat
        inv.setItem(13, makeItem(
            Material.GRASS_BLOCK,
            "§aПлоский мир (Flat)",
            listOf("§7Бесконечная трава и земля", "§7Удобно для масштабных построек")
        ))

        // Slot 15: Survival
        inv.setItem(15, makeItem(
            Material.OAK_SAPLING,
            "§eОбычный мир (Survival)",
            listOf("§7Генерация с деревьями, горами и пещерами", "§7Для приключений и выживания")
        ))

        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val titleStr = PlainTextComponentSerializer.plainText().serialize(event.view.title())
        if (titleStr != "§8$TITLE") return
        event.isCancelled = true

        val template = when (event.rawSlot) {
            11 -> PlotTemplate.VOID
            13 -> PlotTemplate.FLAT
            15 -> PlotTemplate.SURVIVAL
            else -> return
        }

        player.closeInventory()
        player.sendMessage("§e[OCP] Создание мира с шаблоном §f${template.name}§e...")

        scope.launch {
            try {
                // Check creation limit
                val canCreate = plotManager.canCreatePlot(player.uniqueId)
                if (!canCreate) {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        player.sendMessage("§c[OCP] Вы достигли лимита миров (${PlotManagerImpl.DEFAULT_MAX_PLOTS}).")
                    })
                    return@launch
                }

                val plot = plotManager.createPlot(player.uniqueId)

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    try {
                        val (mainWorld, _) = worldManager.createPlotWorldsSync(plot.id, template)
                        player.sendMessage("§a[OCP] Мир '${plot.name}' успешно создан!")
                        player.teleport(mainWorld.spawnLocation)
                    } catch (e: Exception) {
                        player.sendMessage("§c[OCP] Ошибка создания мира: ${e.message}")
                        plugin.logger.severe("[OCP] createPlotWorldsSync failed: ${e.message}")
                    }
                })
            } catch (e: Exception) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    player.sendMessage("§c[OCP] Ошибка создания мира: ${e.message}")
                })
            }
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
