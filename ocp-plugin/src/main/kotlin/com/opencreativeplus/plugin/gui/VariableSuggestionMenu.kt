package com.opencreativeplus.plugin.gui

import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.core.execution.VariableScopeImpl
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import java.util.UUID

/**
 * Paginated inventory GUI for selecting a plot variable.
 *
 * Displays up to 45 variables per page (slots 0–44).
 * Navigation arrows occupy slots 45 (prev) and 53 (next).
 * Slot 49 shows an info item when no variables exist.
 *
 * s: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7
 */
class VariableSuggestionMenu(
    private val player: Player,
    private val plotId: UUID,
    private val variableManager: VariableManager,
    private val onSelect: (String) -> Unit
) : Listener {

    private val pageSize = 45
    internal var currentPage = 0
    internal var variables: List<VariableEntry> = emptyList()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Load variables from the plot scope and render the first page.
     * s: 2.2, 2.3
     */
    suspend fun open() {
        variables = loadVariables()
        currentPage = 0
        render()
    }

    /**
     * Handle a click at [slot].
     * Slot 45 → previous page; slot 53 → next page; other slots → select variable.
     * s: 2.5, 2.7
     */
    fun handleClick(slot: Int) {
        when (slot) {
            45 -> {
                if (currentPage > 0) {
                    currentPage--
                    render()
                }
            }
            53 -> {
                if ((currentPage + 1) * pageSize < variables.size) {
                    currentPage++
                    render()
                }
            }
            in 0 until pageSize -> {
                val idx = currentPage * pageSize + slot
                if (idx < variables.size) {
                    onSelect(variables[idx].name)
                    player.closeInventory()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Listener
    // -------------------------------------------------------------------------

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val p = event.whoClicked as? Player ?: return
        if (!event.view.title.startsWith("Choose Variable")) return
        event.isCancelled = true
        handleClick(event.rawSlot)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Load variables from the plot scope via VariableScopeImpl.toMap().
     * s: 2.3
     */
    private fun loadVariables(): List<VariableEntry> {
        val scope = variableManager.getPlotScope(plotId)
        val map = (scope as? VariableScopeImpl)?.toMap() ?: return emptyList()
        return map.entries.map { (name, value) ->
            VariableEntry(name = name, lastKnownType = value::class.simpleName ?: "Unknown")
        }.sortedBy { it.name }
    }

    /**
     * Build and open the inventory for the current page.
     * s: 2.4, 2.6, 2.7
     */
    internal fun render() {
        val title = "Choose Variable (page ${currentPage + 1})"
        val inv = Bukkit.createInventory(null, 54, title)

        val pageVars = variables.drop(currentPage * pageSize).take(pageSize)

        if (pageVars.isEmpty()) {
            // s: 2.6 — info item when no variables
            inv.setItem(49, makeItem(Material.BARRIER, "§cNo variables defined for this plot", emptyList()))
        } else {
            // s: 2.4 — each variable as a BOOK item
            pageVars.forEachIndexed { i, entry ->
                inv.setItem(i, makeItem(
                    Material.BOOK,
                    entry.name,
                    listOf("§7Type: ${entry.lastKnownType}")
                ))
            }
        }

        // s: 2.7 — navigation arrows
        if (currentPage > 0) {
            inv.setItem(45, makeItem(Material.ARROW, "§e← Previous", emptyList()))
        }
        if ((currentPage + 1) * pageSize < variables.size) {
            inv.setItem(53, makeItem(Material.ARROW, "§eNext →", emptyList()))
        }

        player.openInventory(inv)
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

/**
 * Represents a variable entry in the suggestion menu.
 * s: 2.4
 */
data class VariableEntry(val name: String, val lastKnownType: String)
