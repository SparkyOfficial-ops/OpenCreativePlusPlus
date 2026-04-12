package com.opencreativeplus.plugin.watchdog

import com.opencreativeplus.core.watchdog.TPSMonitor
import com.opencreativeplus.core.watchdog.Watchdog
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

/
 * Admin command that displays the current server TPS and watchdog status.
 *
 * Usage: `/ocptps`
 *
 * Requires the `ocp.admin` permission.
 *
 34.3
 */
class TpsCommand(
    private val tpsMonitor: TPSMonitor,
    private val watchdog: Watchdog
) : CommandExecutor {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("§cYou don't have permission to use this command.")
            return true
        }

        val tps = tpsMonitor.getCurrentTPS()
        val tpsColor = when {
            tps >= 18.0 -> "§a"   // green
            tps >= 15.0 -> "§e"   // yellow
            else        -> "§c"   // red
        }

        val pausedStatus = if (watchdog.areScriptsPaused()) "§cPAUSED" else "§aRUNNING"

        sender.sendMessage("§6[OCP] §fServer TPS: $tpsColor${String.format("%.2f", tps)}")
        sender.sendMessage("§6[OCP] §fScript execution: $pausedStatus")

        return true
    }

    companion object {
        const val PERMISSION = "ocp.admin"
    }
}
