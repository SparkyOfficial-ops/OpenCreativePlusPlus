package com.opencreativeplus.plugin.gui

import com.opencreativeplus.core.execution.VariableManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Abstract base class for inventory GUIs that automatically re-render when
 * watched plot variables change.
 *
 * Usage:
 * 1. Call [watchVariable] for every variable this GUI depends on (typically in init {}).
 * 2. Implement [buildInventory] to construct the current inventory state.
 * 3. Call [open] to show the GUI to a player; call [onClose] when the player closes it.
 *
 * Threading:
 * - [viewers] is a [CopyOnWriteArraySet] — safe for concurrent reads/writes.
 * - [scheduleUpdate] runs [updateAll] on the main thread (via Bukkit scheduler).
 * - When >10 viewers are open, updates are batched into a single delayed tick to
 *   avoid performance spikes (Requirement 11.5).
 *
 * s: 11.1, 11.2, 11.3, 11.4, 11.5
 */
abstract class ReactiveGUI(
    protected val plotId: UUID,
    protected val variableManager: VariableManager,
    private val plugin: Plugin,
    private val scope: CoroutineScope
) : InventoryHolder {

    /** Thread-safe set of players currently viewing this GUI. */
    private val viewers = CopyOnWriteArraySet<Player>()

    /** Variable names this GUI declared a dependency on via [watchVariable]. */
    private val watchedVars: MutableSet<String> = CopyOnWriteArraySet()

    /** Active Flow subscription job; null when no viewers are present. */
    private var subscription: Job? = null

    /**
     * Flag used to debounce batched updates: prevents scheduling multiple
     * redundant tick-delayed updates when many variables change rapidly.
     */
    private val updateScheduled = AtomicBoolean(false)

    // -------------------------------------------------------------------------
    // Abstract contract — implement in subclass
    // -------------------------------------------------------------------------

    /**
     * Build and return the current inventory contents. Called on every update.
     * Implementations should read the latest variable values from [variableManager].
     *
     * s: 11.3
     */
    abstract fun buildInventory(): Inventory

    // -------------------------------------------------------------------------
    // InventoryHolder contract
    // -------------------------------------------------------------------------

    /** Delegates to [buildInventory] so Bukkit can identify this holder. */
    override fun getInventory(): Inventory = buildInventory()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Open the GUI for [player]. Starts the variable subscription if this is
     * the first viewer.
     *
     * s: 11.1, 11.2
     */
    fun open(player: Player) {
        viewers.add(player)
        if (subscription == null) subscribe()
        player.openInventory(buildInventory())
    }

    /**
     * Called when [player] closes the GUI. Cancels the subscription when the
     * last viewer leaves.
     *
     * s: 11.4
     */
    fun onClose(player: Player) {
        viewers.remove(player)
        if (viewers.isEmpty()) {
            subscription?.cancel()
            subscription = null
        }
    }

    /**
     * Declare a dependency on the plot variable [name]. Must be called before
     * [open] (e.g. in an `init {}` block of the subclass).
     *
     * s: 11.1
     */
    protected fun watchVariable(name: String) {
        watchedVars.add(name)
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /**
     * Subscribe to [VariableManager.changes] for this plot, filtered to only
     * the variables this GUI watches.
     *
     * s: 11.2
     */
    private fun subscribe() {
        subscription = variableManager.changes(plotId)
            .filter { it.name in watchedVars }
            .onEach { scheduleUpdate() }
            .launchIn(scope)
    }

    /**
     * Schedule or immediately execute [updateAll].
     *
     * - ≤10 viewers: update immediately on the main thread.
     * - >10 viewers: batch into a single delayed tick to avoid performance spikes.
     *   A debounce flag ([updateScheduled]) prevents duplicate tick-delayed calls.
     *
     * s: 11.3, 11.5
     */
    private fun scheduleUpdate() {
        if (viewers.size > 10) {
            // Batch: schedule exactly one tick-delayed update
            if (updateScheduled.compareAndSet(false, true)) {
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    updateScheduled.set(false)
                    updateAll()
                }, 1L)
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, Runnable { updateAll() })
        }
    }

    /**
     * Rebuild the inventory and push changes to every current viewer.
     *
     * Uses a soft-update strategy: instead of calling [Player.openInventory] again
     * (which resets the mouse cursor every time), we overwrite the contents of the
     * already-open inventory when the size matches.  This keeps the cursor stable
     * while the GUI refreshes — critical when a variable changes every tick and the
     * player is trying to click a pagination button.
     *
     * s: 11.3
     */
    private fun updateAll() {
        val newInv = buildInventory()
        viewers.forEach { p ->
            val currentTop = p.openInventory.topInventory
            if (currentTop.size == newInv.size) {
                // Soft update: swap contents in-place — no cursor reset
                currentTop.contents = newInv.contents
            } else {
                // Size changed (e.g. variable count crossed a page boundary)
                p.openInventory(newInv)
            }
        }
    }
}
