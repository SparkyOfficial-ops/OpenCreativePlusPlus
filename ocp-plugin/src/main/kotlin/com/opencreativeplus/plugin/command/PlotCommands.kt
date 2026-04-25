package com.opencreativeplus.plugin.command

import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.core.trace.TraceManager
import com.opencreativeplus.core.watchdog.TPSMonitor
import com.opencreativeplus.plugin.gui.VariableExplorerGUI
import com.opencreativeplus.plugin.node.dialogue.DialogueManager
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import com.opencreativeplus.plugin.mode.ModeManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * Handles /build, /dev, /play, /plot, /ocptps, and /ocp commands.
 *
 2.1, 2.2, 2.3, 32.2, 32.5, 34.3, 14.1
 */
class PlotCommands(
    private val plotManager: PlotManagerImpl,
    private val modeManager: ModeManagerImpl,
    private val tpsMonitor: TPSMonitor,
    private val scope: CoroutineScope,
    private val traceManager: TraceManager? = null,
    private val variableManager: VariableManager? = null,
    private val plugin: Plugin? = null
) : CommandExecutor {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§c[OCP] Player only command.")
            return true
        }

        when (command.name.lowercase()) {
            "build" -> handleModeSwitch(sender, PlotMode.BUILD)
            "dev"   -> handleModeSwitch(sender, PlotMode.DEV)
            "play"  -> handleModeSwitch(sender, PlotMode.PLAY)
            "plot"  -> handlePlot(sender, args)
            "ocptps" -> sender.sendMessage("§6[OCP] Current TPS: §f${String.format("%.1f", tpsMonitor.getCurrentTPS())}")
            "ocp"   -> handleOcp(sender, args)
        }

        return true
    }

    private fun handleModeSwitch(player: Player, mode: PlotMode) {
        scope.launch {
            val plot = plotManager.getPlayerPlot(player.uniqueId)
            if (plot == null) {
                player.sendMessage("§c[OCP] You don't have a plot. Use /plot create first.")
                return@launch
            }
            if (!plotManager.canEdit(player, plot)) {
                player.sendMessage("§c[OCP] You don't have permission to change modes on this plot.")
                return@launch
            }
            modeManager.switchMode(player, plot, mode)
        }
    }

    private fun handlePlot(player: Player, args: Array<out String>) {
        val sub = args.firstOrNull()?.lowercase()
        when (sub) {
            "create" -> scope.launch {
                val plot = plotManager.createPlot(player.uniqueId)
                player.sendMessage("§a[OCP] Plot '${plot.name}' created!")
            }
            "trust" -> {
                val targetName = args.getOrNull(1)
                if (targetName == null) {
                    player.sendMessage("§c[OCP] Usage: /plot trust <player>")
                    return
                }
                scope.launch {
                    val plot = plotManager.getPlayerPlot(player.uniqueId) ?: run {
                        player.sendMessage("§c[OCP] You don't have a plot.")
                        return@launch
                    }
                    val target = org.bukkit.Bukkit.getOfflinePlayer(targetName)
                    plotManager.addTrustedPlayer(plot.id, target.uniqueId)
                    player.sendMessage("§a[OCP] ${targetName} can now edit your plot.")
                }
            }
            "untrust" -> {
                val targetName = args.getOrNull(1)
                if (targetName == null) {
                    player.sendMessage("§c[OCP] Usage: /plot untrust <player>")
                    return
                }
                scope.launch {
                    val plot = plotManager.getPlayerPlot(player.uniqueId) ?: run {
                        player.sendMessage("§c[OCP] You don't have a plot.")
                        return@launch
                    }
                    val target = org.bukkit.Bukkit.getOfflinePlayer(targetName)
                    plotManager.removeTrustedPlayer(plot.id, target.uniqueId)
                    player.sendMessage("§a[OCP] ${targetName} removed from trusted players.")
                }
            }
            // Req 11.1, 11.4: open Variable Explorer GUI for the player's current plot
            "vars" -> scope.launch {
                val plot = plotManager.getPlayerPlot(player.uniqueId)
                if (plot == null || !plotManager.canEdit(player, plot)) {
                    player.sendMessage("§c[OCP] You must be on your own plot to use /plot vars.")
                    return@launch
                }
                val vm = variableManager
                val pl = plugin
                if (vm == null || pl == null) {
                    player.sendMessage("§c[OCP] Variable Explorer is not available.")
                    return@launch
                }
                val gui = VariableExplorerGUI(plot.id, vm, pl, scope)
                org.bukkit.Bukkit.getScheduler().runTask(pl, Runnable { gui.open(player) })
            }
            else -> player.sendMessage("§7[OCP] Usage: /plot <create|trust|untrust|vars>")
        }
    }

    /**
     * Handles /ocp subcommands.
     * Currently supports: trace
     * s: 14.1
     */
    private fun handleOcp(player: Player, args: Array<out String>) {
        val sub = args.firstOrNull()?.lowercase()
        when (sub) {
            "trace" -> {
                if (!player.hasPermission("ocp.debug")) {
                    player.sendMessage("§cYou don't have permission to use trace mode.")
                    return
                }
                val tm = traceManager
                if (tm == null) {
                    player.sendMessage("§c[OCP] Trace mode is not available.")
                    return
                }
                tm.toggle(player)
            }
            else -> player.sendMessage("§7[OCP] Usage: /ocp <trace>")
        }
    }
}

/**
 * Hidden command executor for `/ocp_dialogue <dialogueId> <optionIndex>`.
 * Triggered by clicking Adventure chat components — not shown in tab-completion or help.
 * Silently ignores malformed arguments.
 * s: 13.3
 */
class OcpDialogueCommand : CommandExecutor {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        val dialogueId = args.getOrNull(0)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return true
        val optionIndex = args.getOrNull(1)?.toIntOrNull() ?: return true
        DialogueManager.onOptionClick(dialogueId, optionIndex)
        return true
    }
}

/**
 * Listens for PlayerQuitEvent and cleans up any pending dialogues for the player.
 * s: 13.5
 */
class DialogueQuitListener : Listener {
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        DialogueManager.onPlayerQuit(event.player.uniqueId)
    }
}
