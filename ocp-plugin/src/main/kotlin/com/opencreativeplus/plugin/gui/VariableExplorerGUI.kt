package com.opencreativeplus.plugin.gui

import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.core.execution.VariableScopeImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * GUI that displays all SAVED and PLOT-scoped variables for a plot.
 * Subscribes to [VariableManager.changes] with a 2-second debounce and
 * refreshes all open viewers when any variable changes.
 *
 * Does NOT extend [ReactiveGUI] — manages its own viewer set and subscription
 * to avoid fighting ReactiveGUI's private name-filtered subscription mechanism.
 *
 * s: 11.2, 11.3, 11.4
 */
@OptIn(FlowPreview::class)
class VariableExplorerGUI(
    private val plotId: UUID,
    private val variableManager: VariableManager,
    private val plugin: Plugin,
    private val scope: CoroutineScope
) : InventoryHolder {

    private val viewers = CopyOnWriteArraySet<Player>()
    private var subscription: Job? = null

    init {
        // Req 11.3: subscribe with debounce(2000) — refresh within 2 seconds of any change
        subscription = variableManager.changes(plotId)
            .debounce(2000L)
            .onEach { scheduleRefresh() }
            .launchIn(scope)
    }

    override fun getInventory(): Inventory = buildInventory()

    /**
     * Open the GUI for [player] and register them as a viewer.
     */
    fun open(player: Player) {
        viewers.add(player)
        player.openInventory(buildInventory())
    }

    /**
     * Called when [player] closes the GUI. Cancels the subscription when the
     * last viewer leaves.
     */
    fun onClose(player: Player) {
        viewers.remove(player)
        if (viewers.isEmpty()) {
            subscription?.cancel()
            subscription = null
        }
    }

    /**
     * Build the inventory from the current state of both plot and saved scopes.
     * Uses [runBlocking] for [VariableManager.getSavedScope] since [buildInventory]
     * must be synchronous (called from the Bukkit main thread).
     *
     * s: 11.2, 11.3
     */
    fun buildInventory(): Inventory {
        val plotScope = variableManager.getPlotScope(plotId)
        val savedScope = runBlocking { variableManager.getSavedScope(plotId) }

        // Merge: saved first, then plot overrides (plot-scoped takes precedence)
        val allVars = mutableMapOf<String, Any?>()
        (savedScope as? VariableScopeImpl)?.toMap()?.forEach { (k, v) -> allVars[k] = v }
        (plotScope as? VariableScopeImpl)?.toMap()?.forEach { (k, v) -> allVars[k] = v }

        val size = if (allVars.isEmpty()) 9
                   else (((allVars.size + 8) / 9) * 9).coerceIn(9, 54)

        val inv = Bukkit.createInventory(this, size, "§6Переменные участка")

        allVars.entries.forEachIndexed { index, (name, value) ->
            if (index >= size) return@forEachIndexed
            val item = ItemStack(Material.PAPER)
            val meta = item.itemMeta ?: return@forEachIndexed
            meta.setDisplayName("§e$name")
            meta.lore = listOf(
                "§7Значение: §f$value",
                "§8Тип: §7${value?.javaClass?.simpleName ?: "null"}"
            )
            item.itemMeta = meta
            inv.setItem(index, item)
        }

        return inv
    }

    /**
     * Schedule a refresh on the Bukkit main thread for all current viewers.
     */
    private fun scheduleRefresh() {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val inv = buildInventory()
            viewers.forEach { it.openInventory(inv) }
        })
    }
}
