package com.opencreativeplus.plugin.command

import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.core.watchdog.TPSMonitor
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import com.opencreativeplus.plugin.mode.ModeManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/
 * Handles /build, /dev, /play, /plot, and /ocptps commands.
 *
 2.1, 2.2, 2.3, 32.2, 32.5, 34.3
 */
class PlotCommands(
    private val plotManager: PlotManagerImpl,
    private val modeManager: ModeManagerImpl,
    private val tpsMonitor: TPSMonitor,
    private val scope: CoroutineScope
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
            else -> player.sendMessage("§7[OCP] Usage: /plot <create|trust|untrust>")
        }
    }
}
