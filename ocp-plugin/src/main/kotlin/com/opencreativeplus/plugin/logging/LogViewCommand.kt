package com.opencreativeplus.plugin.logging

import com.opencreativeplus.plugin.plot.PlotManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bson.Document

/**
 * /ocplogs command — shows the most recent 100 execution logs for the player's plot.
 *
 37.4, 37.5
 */
class LogViewCommand(
    private val executionLogger: ExecutionLogger,
    private val plotManager: PlotManagerImpl,
    private val scope: CoroutineScope
) : CommandExecutor {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§c[OCP] This command can only be used by players.")
            return true
        }

        scope.launch {
            val plot = plotManager.getPlayerPlot(sender.uniqueId)
            if (plot == null) {
                sender.sendMessage("§c[OCP] You don't have a plot.")
                return@launch
            }

            val logs = executionLogger.getRecentLogs(plot.id, limit = 100)
            if (logs.isEmpty()) {
                sender.sendMessage("§7[OCP] No execution logs found for your plot.")
                return@launch
            }

            sender.sendMessage("§6[OCP] Last ${logs.size} executions for '${plot.name}':")
            logs.forEach { doc -> sender.sendMessage(formatLog(doc)) }
        }

        return true
    }

    private fun formatLog(doc: Document): String {
        val event = doc.getString("event_type") ?: "?"
        val status = doc.getString("status") ?: "?"
        val duration = doc.getLong("duration_ms") ?: 0L
        val statusColor = when (status) {
            "SUCCESS" -> "§a"
            "ERROR" -> "§c"
            else -> "§e"
        }
        return "§7  $event → ${statusColor}$status §7(${duration}ms)"
    }
}
