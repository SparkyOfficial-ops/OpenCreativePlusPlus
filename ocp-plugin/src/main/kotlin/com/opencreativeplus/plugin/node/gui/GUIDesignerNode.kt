package com.opencreativeplus.plugin.node.gui

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.core.database.PlotPersistence
import com.opencreativeplus.plugin.mode.ModeManagerImpl
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import kotlinx.coroutines.CoroutineScope
import org.bukkit.plugin.Plugin

/**
 * Action node that opens the [GUIDesignerEditor] for the executing player.
 *
 * The editor is opened only when the player right-clicks the CRAFTING_TABLE node
 * block in DEV mode (via [ActionNodeInteractListener]). The execute() method itself
 * is intentionally a no-op: scripts run in PLAY mode where DEV tooling must not open,
 * and in DEV mode scripts don't execute at all — so there is no scenario in which
 * calling execute() should open the editor.
 *
 * nodeId = "gui_designer"
 * params:
 *   - `menu_name` (String): name of the menu to create or edit
 *
 * Requirements: 1.2
 */
class GUIDesignerNode(
    private val params: Map<String, Any>,
    private val plugin: Plugin,
    private val modeManager: ModeManagerImpl,
    private val plotManager: PlotManagerImpl,
    private val plotPersistence: PlotPersistence? = null,
    private val scope: CoroutineScope? = null
) : IAction {

    override val nodeId = "gui_designer"
    override val displayName = "GUI Designer"

    /**
     * No-op: the GUI Designer is opened by right-clicking the CRAFTING_TABLE block
     * in DEV mode via [ActionNodeInteractListener], not by script execution.
     *
     * Executing this node inside a PLAY-mode script would be a design error —
     * the editor is a developer tool, not a runtime action.
     */
    override suspend fun execute(context: ExecutionContext) {
        // intentional no-op — see class KDoc
    }

    /**
     * Opens the [GUIDesignerEditor] for [player] on the given [plot].
     * Called directly by [ActionNodeInteractListener] on right-click in DEV mode.
     */
    fun openEditor(player: org.bukkit.entity.Player, plot: com.opencreativeplus.api.plot.Plot) {
        val menuName = params["menu_name"]?.toString() ?: "menu_${System.currentTimeMillis()}"
        val menuStore = NoOpCustomMenuStore()
        val editor = GUIDesignerEditor(
            player = player,
            menuName = menuName,
            menuStore = menuStore,
            plugin = plugin,
            plotId = plot.id,
            plotPersistence = plotPersistence,
            scope = scope
        )
        plugin.server.pluginManager.registerEvents(editor, plugin)
        editor.open()
    }
}
