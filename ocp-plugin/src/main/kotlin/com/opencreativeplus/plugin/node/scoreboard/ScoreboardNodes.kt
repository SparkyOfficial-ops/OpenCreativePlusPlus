package com.opencreativeplus.plugin.node.scoreboard

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("ScoreboardNodes")

/**
 * Resolves a template string by replacing `{variable_name}` placeholders
 * with values from plot scope (first) or local scope (fallback).
 * s: 12.5
 */
fun resolveTemplate(template: String, context: ExecutionContext): String {
    return Regex("\\{(\\w+)}").replace(template) { match ->
        val varName = match.groupValues[1]
        context.plotScope.get(varName)?.toString()
            ?: context.localScope.get(varName)?.toString()
            ?: match.value
    }
}

/**
 * Creates a named scoreboard with a title template.
 * Params: "name" (String, default "scoreboard"), "title" (String, default "Score")
 * Stores the Scoreboard as "scoreboard_$name" and Objective as "scoreboard_obj_$name" in plotScope.
 * s: 12.1
 */
class CreateScoreboardNode(params: Map<String, Any>) : IAction {
    override val nodeId = "create_scoreboard"
    override val displayName = "Create Scoreboard"
    private val name: String = params["name"] as? String ?: "scoreboard"
    private val title: String = params["title"] as? String ?: "Score"

    override suspend fun execute(context: ExecutionContext) {
        val resolvedTitle = resolveTemplate(title, context)
        context.syncContext {
            val scoreboard = Bukkit.getScoreboardManager().newScoreboard
            @Suppress("DEPRECATION")
            val objective = scoreboard.registerNewObjective(name, "dummy", resolvedTitle)
            context.plotScope.set("scoreboard_$name", scoreboard)
            context.plotScope.set("scoreboard_obj_$name", objective)
            logger.fine("Created scoreboard '$name' with title '$resolvedTitle'")
        }
    }
}

/**
 * Sets a specific line of a scoreboard to a text template.
 * Params: "name" (String, default "scoreboard"), "line" (Int, default 0), "text" (String, default "")
 * s: 12.2
 */
class SetScoreboardLineNode(params: Map<String, Any>) : IAction {
    override val nodeId = "set_scoreboard_line"
    override val displayName = "Set Scoreboard Line"
    private val name: String = params["name"] as? String ?: "scoreboard"
    private val line: Int = params["line"] as? Int ?: 0
    private val text: String = params["text"] as? String ?: ""

    override suspend fun execute(context: ExecutionContext) {
        val objective = context.plotScope.get("scoreboard_obj_$name") as? org.bukkit.scoreboard.Objective
        if (objective == null) {
            logger.warning("Scoreboard objective 'scoreboard_obj_$name' not found")
            return
        }
        val resolvedText = resolveTemplate(text, context)
        context.syncContext {
            objective.getScore(resolvedText).score = line
        }
    }
}

/**
 * Displays a named scoreboard to a target player.
 * Params: "player" (String var name), "name" (String, default "scoreboard")
 * Must run via syncContext. s: 12.3, 12.6
 */
class ShowScoreboardNode(params: Map<String, Any>) : IAction {
    override val nodeId = "show_scoreboard"
    override val displayName = "Show Scoreboard"
    private val playerVar: String = params["player"] as? String ?: error("player param required")
    private val name: String = params["name"] as? String ?: "scoreboard"

    override suspend fun execute(context: ExecutionContext) {
        val player = context.localScope.get(playerVar) as? Player ?: return
        val scoreboard = context.plotScope.get("scoreboard_$name") as? org.bukkit.scoreboard.Scoreboard
        if (scoreboard == null) {
            logger.warning("Scoreboard 'scoreboard_$name' not found")
            return
        }
        context.syncContext {
            player.scoreboard = scoreboard
        }
    }
}

/**
 * Removes the scoreboard from a target player's display (resets to main scoreboard).
 * Params: "player" (String var name)
 * s: 12.4
 */
class HideScoreboardNode(params: Map<String, Any>) : IAction {
    override val nodeId = "hide_scoreboard"
    override val displayName = "Hide Scoreboard"
    private val playerVar: String = params["player"] as? String ?: error("player param required")

    override suspend fun execute(context: ExecutionContext) {
        val player = context.localScope.get(playerVar) as? Player ?: return
        context.syncContext {
            player.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        }
    }
}
