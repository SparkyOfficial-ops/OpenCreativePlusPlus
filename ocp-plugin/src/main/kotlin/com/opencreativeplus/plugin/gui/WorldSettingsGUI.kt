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
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * GUI for configuring a specific plot's settings (54-slot inventory).
 *
 * Layout:
 * Row 1 - Flags (slots 0-4):
 *   0: Toggle interactions (LEVER)
 *   1: Toggle explosions (TNT)
 *   2: Toggle fire (FLINT_AND_STEEL)
 *   3: Toggle PvP (DIAMOND_SWORD / WOODEN_SWORD)
 *   4: Toggle mob spawning (ZOMBIE_HEAD / SKELETON_SKULL)
 * Row 2 - Access (slots 9-11):
 *   9:  Toggle public/private (LIME_DYE / GRAY_DYE)
 *   10: Toggle coding access (CRAFTING_TABLE)
 *   11: Manage trusted players (PLAYER_HEAD)
 * Row 3 - Time (slots 18-19):
 *   18: Set Day (SUNFLOWER)
 *   19: Set Night (ENDER_EYE)
 * Slot 53: "← Back" (ARROW) — returns to MyWorldsGUI
 */
@Suppress("DEPRECATION")
class WorldSettingsGUI(
    private val plotManager: PlotManagerImpl,
    private val scope: CoroutineScope,
    private val plugin: Plugin,
    private val myWorldsGUI: MyWorldsGUI? = null
) : Listener {

    companion object {
        const val GUI_TITLE = "§8Настройки мира"
    }

    /** playerId → plot being configured */
    private val openPlots = ConcurrentHashMap<UUID, Plot>()
    /** playerId → page to return to in MyWorldsGUI */
    private val returnPages = ConcurrentHashMap<UUID, Int>()

    fun open(player: Player, plot: Plot, returnPage: Int = 0) {
        openPlots[player.uniqueId] = plot
        returnPages[player.uniqueId] = returnPage
        renderInventory(player, plot)
    }

    private fun renderInventory(player: Player, plot: Plot) {
        val inv = Bukkit.createInventory(null, 54, GUI_TITLE)
        val s = plot.settings

        // Row 1 - Flags
        inv.setItem(0, makeToggle(
            Material.LEVER,
            "§eВзаимодействия",
            s.allowInteractions,
            "§7Разрешить взаимодействие с блоками"
        ))
        inv.setItem(1, makeToggle(
            Material.TNT,
            "§eВзрывы",
            s.allowExplosions,
            "§7Разрешить взрывы на участке"
        ))
        inv.setItem(2, makeToggle(
            Material.FLINT_AND_STEEL,
            "§eОгонь",
            s.allowFire,
            "§7Разрешить распространение огня"
        ))
        inv.setItem(3, makeToggle(
            if (s.pvpEnabled) Material.DIAMOND_SWORD else Material.WOODEN_SWORD,
            "§ePvP",
            s.pvpEnabled,
            "§7Разрешить PvP на участке"
        ))
        inv.setItem(4, makeToggle(
            if (s.mobSpawningEnabled) Material.ZOMBIE_HEAD else Material.SKELETON_SKULL,
            "§eМобы",
            s.mobSpawningEnabled,
            "§7Разрешить спавн мобов"
        ))

        // Row 2 - Access
        inv.setItem(9, makeToggle(
            if (s.isPublic) Material.LIME_DYE else Material.GRAY_DYE,
            "§eПубличный доступ",
            s.isPublic,
            "§7Виден ли мир в браузере миров"
        ))
        inv.setItem(10, makeToggle(
            Material.CRAFTING_TABLE,
            "§eДоступ к коду",
            s.allowCodingAccess,
            "§7Доверенные игроки могут войти в /dev"
        ))
        inv.setItem(11, makeItem(
            Material.PLAYER_HEAD,
            "§eДоверенные игроки",
            listOf(
                "§7Игроков: §f${plot.trustedPlayers.size}",
                "",
                "§7Нажмите для управления"
            )
        ))

        // Row 3 - Time
        inv.setItem(18, makeItem(
            Material.SUNFLOWER,
            "§eУстановить день",
            listOf("§7Устанавливает время на 6000 (день)")
        ))
        inv.setItem(19, makeItem(
            Material.ENDER_EYE,
            "§eУстановить ночь",
            listOf("§7Устанавливает время на 18000 (ночь)")
        ))

        // Slot 53: Back
        inv.setItem(53, makeItem(Material.ARROW, "§7← Назад", listOf("§7Вернуться к списку миров")))

        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.view.title != GUI_TITLE) return
        event.isCancelled = true

        val plot = openPlots[player.uniqueId] ?: return

        if (!plotManager.canEdit(player, plot)) {
            player.sendMessage("§c[OCP] Нет прав для изменения настроек.")
            return
        }

        val s = plot.settings

        when (event.rawSlot) {
            53 -> {
                // Back to MyWorldsGUI
                val returnPage = returnPages.remove(player.uniqueId) ?: 0
                openPlots.remove(player.uniqueId)
                player.closeInventory()
                myWorldsGUI?.open(player, returnPage)
                return
            }
            11 -> {
                // Trusted players management — no-op for now
                return
            }
        }

        val newSettings: PlotSettings = when (event.rawSlot) {
            0  -> s.copy(allowInteractions = !s.allowInteractions)
            1  -> s.copy(allowExplosions = !s.allowExplosions)
            2  -> s.copy(allowFire = !s.allowFire)
            3  -> s.copy(pvpEnabled = !s.pvpEnabled)
            4  -> s.copy(mobSpawningEnabled = !s.mobSpawningEnabled)
            9  -> s.copy(isPublic = !s.isPublic)
            10 -> s.copy(allowCodingAccess = !s.allowCodingAccess)
            18 -> s.copy(timeOfDay = 6000L)
            19 -> s.copy(timeOfDay = 18000L)
            else -> return
        }

        scope.launch {
            plotManager.updateSettings(plot.id, newSettings)
            val updated = plotManager.getPlot(plot.id) ?: return@launch
            openPlots[player.uniqueId] = updated
            Bukkit.getScheduler().runTask(plugin, Runnable {
                renderInventory(player, updated)
            })
        }    }

    private fun makeToggle(material: Material, name: String, enabled: Boolean, description: String): ItemStack {
        val stateText = if (enabled) "§aВКЛ" else "§cВЫКЛ"
        return makeItem(material, "$name: $stateText", listOf(description, "", "§7Нажмите для переключения"))
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
