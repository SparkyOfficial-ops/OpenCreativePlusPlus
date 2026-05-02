package com.opencreativeplus.plugin.node.gui

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.core.database.PlotPersistence
import com.opencreativeplus.plugin.mode.ModeManagerImpl
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import kotlinx.coroutines.CoroutineScope
import org.bukkit.plugin.Plugin

/**
 * Action node that opens the [GUIDesignerEditor] for the executing player.
 *
 * Only works in DEV mode. The `menu_name` param identifies which [CustomMenuDefinition]
 * is being edited. The menu is stored in [PlotMenuRegistry] (keyed by plotId + menuName).
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

    override suspend fun execute(context: ExecutionContext) {
        val player = context.player ?: return
        val menuName = params["menu_name"]?.toString() ?: return

        // Only open the editor in DEV mode
        val plot = plotManager.getPlayerPlot(player.uniqueId) ?: return
        if (modeManager.getCurrentMode(player, plot) != PlotMode.DEV) return

        // Use a no-op store (menus are stored in PlotMenuRegistry)
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

        context.syncContext {
            plugin.server.pluginManager.registerEvents(editor, plugin)
            editor.open()
        }
    }
}
