package com.opencreativeplus.plugin.gui

import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.core.execution.VariableScopeImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.logging.Logger

/**
 * GUI that displays all SAVED and PLOT-scoped variables for a plot.
 * Supports pagination when there are more than 45 variables.
 * Subscribes to [VariableManager.changes] with a 2-second debounce and
 * refreshes all open viewers when any variable changes.
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

    /** Tracks the current page index per viewer (player UUID → page index). */
    private val viewerPages = ConcurrentHashMap<UUID, Int>()

    private val logger = Logger.getLogger("VariableExplorerGUI")

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
     * Resets the player's page to 0.
     */
    fun open(player: Player) {
        viewers.add(player)
        viewerPages[player.uniqueId] = 0
        player.openInventory(buildPagedInventory(0))
    }

    /**
     * Called when [player] closes the GUI. Cancels the subscription when the
     * last viewer leaves.
     */
    fun onClose(player: Player) {
        viewers.remove(player)
        viewerPages.remove(player.uniqueId)
        if (viewers.isEmpty()) {
            subscription?.cancel()
            subscription = null
        }
    }

    /**
     * Handle a click in the GUI inventory.
     *
     * - Slot 45: previous page button
     * - Slot 53: next page button
     * - Slots 0–44: variable items — delete the variable at that slot on the current page
     *
     * Req 2.3: logs deletion with player name, variable name, and timestamp.
     * Req 2.4: persists immediately when the variable is in SAVED scope.
     */
    fun handleClick(slot: Int, player: Player) {
        val currentPage = viewerPages[player.uniqueId] ?: 0

        when {
            slot == 45 -> {
                // Previous page button
                if (currentPage > 0) {
                    val newPage = currentPage - 1
                    viewerPages[player.uniqueId] = newPage
                    player.openInventory(buildPagedInventory(newPage))
                }
            }
            slot == 53 -> {
                // Next page button
                val allVars = collectAllVars()
                val totalPages = (allVars.size + 44) / 45
                if (currentPage < totalPages - 1) {
                    val newPage = currentPage + 1
                    viewerPages[player.uniqueId] = newPage
                    player.openInventory(buildPagedInventory(newPage))
                }
            }
            slot in 0..44 -> {
                // Variable item — delete it asynchronously to avoid blocking main thread
                scope.launch {
                    val allVars = collectAllVars()
                    val varIndex = currentPage * 45 + slot
                    if (varIndex >= allVars.size) return@launch

                    val (key, _) = allVars.entries.toList()[varIndex]

                    // Determine which scope the variable belongs to
                    val savedScope = variableManager.getSavedScope(plotId)
                    val isInSavedScope = (savedScope as? VariableScopeImpl)?.has(key) == true

                    // Delete from the appropriate scope by rebuilding it (clear + re-set all except deleted key)
                    if (isInSavedScope) {
                        val savedImpl = savedScope as VariableScopeImpl
                        val remaining = savedImpl.toMap().filterKeys { it != key }
                        savedImpl.clear()
                        remaining.forEach { (k, v) -> savedImpl.set(k, v) }

                        // Req 2.4: persist immediately when deleting from SAVED scope
                        variableManager.savePlotVariables(plotId)
                    } else {
                        val plotScope = variableManager.getPlotScope(plotId)
                        val plotImpl = plotScope as? VariableScopeImpl
                        if (plotImpl != null) {
                            val remaining = plotImpl.toMap().filterKeys { it != key }
                            plotImpl.clear()
                            remaining.forEach { (k, v) -> plotImpl.set(k, v) }
                        }
                    }

                    // Req 2.3: log deletion with player name, variable name, and timestamp
                    logger.info("[VariableExplorer] Player ${player.name} deleted variable '$key' at ${java.time.Instant.now()}")

                    // Refresh all viewers on main thread
                    scheduleRefresh()
                }
            }
        }
    }

    /**
     * Build a paged inventory for the given [page].
     *
     * - Empty vars: 9-slot inventory with an info item (glass pane, "§7Нет переменных")
     * - ≤ 45 vars: 54-slot inventory with all vars in slots 0–44
     * - > 45 vars: 54-slot inventory with up to 45 vars per page and pagination buttons
     *   in slots 45 (prev) and 53 (next).
     *
     * Req 2.6
     */
    fun buildPagedInventory(page: Int): Inventory {
        val allVars = collectAllVars()

        if (allVars.isEmpty()) {
            val inv = Bukkit.createInventory(this, 9, "§6Переменные участка")
            val infoItem = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
            val meta = infoItem.itemMeta
            if (meta != null) {
                meta.setDisplayName("§7Нет переменных")
                infoItem.itemMeta = meta
            }
            inv.setItem(0, infoItem)
            return inv
        }

        val inv = Bukkit.createInventory(this, 54, "§6Переменные участка")
        val pageVars = allVars.entries.toList()
            .drop(page * 45)
            .take(45)

        pageVars.forEachIndexed { slotIndex, (name, value) ->
            val item = ItemStack(Material.PAPER)
            val meta = item.itemMeta ?: return@forEachIndexed
            meta.setDisplayName("§e$name")
            meta.lore = listOf(
                "§7Значение: §f$value",
                "§8Тип: §7${value?.javaClass?.simpleName ?: "null"}"
            )
            item.itemMeta = meta
            inv.setItem(slotIndex, item)
        }

        // Add pagination buttons only when there are more than 45 variables
        if (allVars.size > 45) {
            // Slot 45: previous page button
            if (page > 0) {
                val prevItem = ItemStack(Material.ARROW)
                val prevMeta = prevItem.itemMeta
                if (prevMeta != null) {
                    prevMeta.setDisplayName("§7← Назад")
                    prevItem.itemMeta = prevMeta
                }
                inv.setItem(45, prevItem)
            } else {
                inv.setItem(45, ItemStack(Material.AIR))
            }

            // Slot 53: next page button
            val totalPages = (allVars.size + 44) / 45
            if (page < totalPages - 1) {
                val nextItem = ItemStack(Material.ARROW)
                val nextMeta = nextItem.itemMeta
                if (nextMeta != null) {
                    nextMeta.setDisplayName("§7Вперёд →")
                    nextItem.itemMeta = nextMeta
                }
                inv.setItem(53, nextItem)
            } else {
                inv.setItem(53, ItemStack(Material.AIR))
            }
        }

        return inv
    }

    /**
     * Build the inventory from the current state of both plot and saved scopes.
     * Kept for backward compatibility (used by [getInventory]).
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
     * Collect all variables from both saved and plot scopes, merged into a single map.
     * Saved scope is loaded first; plot scope overrides on key collision.
     * Uses runBlocking only as a last resort — getSavedScope is a suspend function.
     * NOTE: runBlocking on the main thread is safe here because getSavedScope uses
     * a per-plot Mutex and only does a ConcurrentHashMap lookup on the fast path.
     */
    private fun collectAllVars(): Map<String, Any?> {
        val plotScope = variableManager.getPlotScope(plotId)
        val savedScope = runBlocking { variableManager.getSavedScope(plotId) }

        val allVars = mutableMapOf<String, Any?>()
        (savedScope as? VariableScopeImpl)?.toMap()?.forEach { (k, v) -> allVars[k] = v }
        (plotScope as? VariableScopeImpl)?.toMap()?.forEach { (k, v) -> allVars[k] = v }
        return allVars
    }

    /**
     * Schedule a refresh on the Bukkit main thread for all current viewers,
     * using each viewer's current page.
     */
    private fun scheduleRefresh() {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            viewers.forEach { viewer ->
                val currentPage = viewerPages[viewer.uniqueId] ?: 0
                viewer.openInventory(buildPagedInventory(currentPage))
            }
        })
    }
}
