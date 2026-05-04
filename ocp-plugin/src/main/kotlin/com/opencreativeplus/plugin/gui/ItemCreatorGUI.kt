package com.opencreativeplus.plugin.gui

import com.opencreativeplus.plugin.input.SignInputManager
import com.opencreativeplus.plugin.scanner.DataContainer
import com.opencreativeplus.plugin.scanner.DataContainer.Companion.serializeTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.Plugin

/**
 * GUI for creating item-variables of four types: Text, Number, Variable, Location.
 * Opened via /ocp items command.
 *
 * Requirements: 3.1–3.12
 */
class ItemCreatorGUI(
    private val plugin: Plugin,
    private val signInputManager: SignInputManager,
    private val scope: CoroutineScope,
    /** Injectable for testing: creates the backing inventory. Defaults to Bukkit.createInventory. */
    internal val inventoryFactory: (size: Int, title: String) -> Inventory = { size, title ->
        @Suppress("DEPRECATION")
        Bukkit.createInventory(null, size, title)
    },
    /** Injectable for testing: unregisters this listener. Defaults to HandlerList.unregisterAll. */
    internal val listenerUnregister: (Listener) -> Unit = { HandlerList.unregisterAll(it) }
) : Listener {

    companion object {
        internal const val GUI_TITLE = "§8Item Creator"
        private const val SIZE = 9
    }

    /**
     * Opens the Item Creator GUI for the given player.
     * Must be called on the main thread.
     * Requirements: 3.1–3.5
     */
    fun open(player: Player) {
        val inv = inventoryFactory(SIZE, GUI_TITLE)

        // Slot 0: Text (BOOK)
        inv.setItem(0, makeItem(Material.BOOK, "§fТекст", listOf("§7Создать текстовую переменную")))
        // Slot 1: Number (MAGMA_CREAM)
        inv.setItem(1, makeItem(Material.MAGMA_CREAM, "§fЧисло", listOf("§7Создать числовую переменную")))
        // Slot 2: Variable (IRON_INGOT)
        inv.setItem(2, makeItem(Material.IRON_INGOT, "§fПеременная", listOf("§7Создать ссылку на переменную")))
        // Slot 3: Location (COMPASS)
        inv.setItem(3, makeItem(Material.COMPASS, "§fМестоположение", listOf("§7Сохранить текущее местоположение")))

        plugin.server.pluginManager.registerEvents(this, plugin)
        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        @Suppress("DEPRECATION")
        if (event.view.title != GUI_TITLE) return
        event.isCancelled = true

        val slot = event.rawSlot
        if (slot !in 0..3) return

        // Unregister immediately to avoid duplicate handling
        listenerUnregister(this)
        player.closeInventory()

        scope.launch {
            handleClick(player, slot)
        }
    }

    /**
     * Handles a click on one of the four icon slots.
     * Requirements: 3.6–3.9
     */
    internal suspend fun handleClick(player: Player, slot: Int) {
        when (slot) {
            0 -> {
                // Text: prompt via sign input
                val input = signInputManager.awaitSignInput(player, "") ?: return
                val dc = DataContainer.Text(input)
                val item = ItemStack(Material.BOOK)
                dc.serializeTo(item)
                deliverItem(player, item)
            }
            1 -> {
                // Number: prompt via sign input, validate
                val input = signInputManager.awaitSignInput(player, "") ?: return
                val number = input.toDoubleOrNull()
                if (number == null) {
                    player.sendMessage("§c[OCP] Введите корректное число.")
                    return
                }
                val dc = DataContainer.Number(number)
                val item = ItemStack(Material.MAGMA_CREAM)
                dc.serializeTo(item)
                deliverItem(player, item)
            }
            2 -> {
                // Variable: prompt via sign input
                val input = signInputManager.awaitSignInput(player, "") ?: return
                val dc = DataContainer.Variable(input)
                val item = ItemStack(Material.IRON_INGOT)
                dc.serializeTo(item)
                deliverItem(player, item)
            }
            3 -> {
                // Location: use player's current location, no input needed
                val loc = player.location
                val dc = DataContainer.Location(
                    x = loc.x,
                    y = loc.y,
                    z = loc.z,
                    world = loc.world?.name ?: "",
                    yaw = loc.yaw,
                    pitch = loc.pitch
                )
                val item = ItemStack(Material.COMPASS)
                dc.serializeTo(item)
                deliverItem(player, item)
            }
        }
    }

    /**
     * Delivers the created item to the player's inventory.
     * If inventory is full, drops the item at the player's feet.
     * Must be called on the main thread (inventory API).
     * Requirements: 3.10, 3.11
     */
    internal fun deliverItem(player: Player, item: ItemStack) {
        // inventory.addItem and world.dropItem require main thread
        plugin.server.scheduler.runTask(plugin, Runnable {
            val leftover = player.inventory.addItem(item)
            if (leftover.isNotEmpty()) {
                player.world.dropItem(player.location, item)
                player.sendMessage("§e[OCP] Инвентарь полон, предмет выброшен рядом с вами.")
            }
        })
    }

    private fun makeItem(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        @Suppress("DEPRECATION")
        val meta: ItemMeta = item.itemMeta ?: return item
        @Suppress("DEPRECATION")
        meta.setDisplayName(name)
        @Suppress("DEPRECATION")
        meta.lore = lore
        item.itemMeta = meta
        return item
    }
}
