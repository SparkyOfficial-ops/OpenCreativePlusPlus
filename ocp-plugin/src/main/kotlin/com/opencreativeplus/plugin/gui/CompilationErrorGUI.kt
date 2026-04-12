package com.opencreativeplus.plugin.gui

import com.opencreativeplus.plugin.compiler.CompilationError
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/**
 * Displays compilation errors in a chest GUI.
 * Each error is shown as a barrier block with location and message in lore.
 *
 23.5
 */
object CompilationErrorGUI {

    private const val GUI_TITLE = "§cCompilation Errors"

    fun open(player: Player, errors: List<CompilationError>) {
        val size = minOf(54, ((errors.size / 9) + 1) * 9)
        val inv = Bukkit.createInventory(null, size, GUI_TITLE)

        errors.take(54).forEachIndexed { index, error ->
            val item = ItemStack(Material.BARRIER)
            val meta: ItemMeta = item.itemMeta ?: return@forEachIndexed
            meta.setDisplayName("§cError at ${error.location}")
            meta.lore = listOf(
                "§7${error.message}",
                "",
                "§7Fix the block at the listed location."
            )
            item.itemMeta = meta
            inv.setItem(index, item)
        }

        player.openInventory(inv)
    }
}
