package com.opencreativeplus.plugin.node.ui

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.EntityEffect
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.time.Duration
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("UINodes")

/**
 * Displays a title and subtitle to a target player using the Adventure Component API.
 * Params: "player" (String var name), "title" (String, default ""),
 *         "subtitle" (String, default ""), "fadeIn" (Int ticks, default 10),
 *         "stay" (Int ticks, default 70), "fadeOut" (Int ticks, default 20)
 * s: 19.1, 19.5
 */
class SendTitleNode(params: Map<String, Any>) : IAction {
    override val nodeId = "send_title"
    override val displayName = "Send Title"
    private val playerVar: String = params["player"] as? String ?: error("player param required")
    private val titleText: String = params["title"] as? String ?: ""
    private val subtitleText: String = params["subtitle"] as? String ?: ""
    private val fadeIn: Int = params["fadeIn"] as? Int ?: 10
    private val stay: Int = params["stay"] as? Int ?: 70
    private val fadeOut: Int = params["fadeOut"] as? Int ?: 20

    override suspend fun execute(context: ExecutionContext) {
        val player = context.localScope.get(playerVar) as? Player ?: return
        context.syncContext {
            val times = Title.Times.times(
                Duration.ofMillis(fadeIn * 50L),
                Duration.ofMillis(stay * 50L),
                Duration.ofMillis(fadeOut * 50L)
            )
            val title = Title.title(
                Component.text(titleText),
                Component.text(subtitleText),
                times
            )
            player.showTitle(title)
        }
    }
}

/**
 * Displays a message in the action bar of a target player using the Adventure Component API.
 * Params: "player" (String var name), "message" (String, default "")
 * s: 19.2
 */
class SendActionBarNode(params: Map<String, Any>) : IAction {
    override val nodeId = "send_action_bar"
    override val displayName = "Send Action Bar"
    private val playerVar: String = params["player"] as? String ?: error("player param required")
    private val message: String = params["message"] as? String ?: ""

    override suspend fun execute(context: ExecutionContext) {
        val player = context.localScope.get(playerVar) as? Player ?: return
        // sendActionBar must run on main thread (Adventure/Bukkit API)
        context.syncContext { player.sendActionBar(Component.text(message)) }
    }
}

/**
 * Triggers a named animation on a target entity.
 * Params: "entity" (String var name holding an Entity), "animation" (String, default "HURT")
 * Supported animations: HURT, DEATH, WAKE_UP
 * s: 19.3
 */
class PlayAnimationNode(params: Map<String, Any>) : IAction {
    override val nodeId = "play_animation"
    override val displayName = "Play Animation"
    private val entityVar: String = params["entity"] as? String ?: error("entity param required")
    private val animation: String = params["animation"] as? String ?: "HURT"

    override suspend fun execute(context: ExecutionContext) {
        val entity = context.localScope.get(entityVar) as? Entity ?: return
        val effect = resolveEntityEffect(animation)
        if (effect == null) {
            logger.warning("Unknown animation: $animation")
            return
        }
        context.syncContext { entity.playEffect(effect) }
    }

    private fun resolveEntityEffect(name: String): EntityEffect? {
        return when (name.uppercase()) {
            "HURT" -> EntityEffect.HURT
            "DEATH" -> EntityEffect.DEATH
            "WAKE_UP" -> EntityEffect.ENTITY_POOF
            else -> try {
                EntityEffect.valueOf(name.uppercase())
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/**
 * Creates or updates a boss bar for a target player.
 * Params: "player" (String var name), "title" (String, default ""),
 *         "color" (String BarColor name, default "PURPLE"), "progress" (Double 0.0-1.0, default 1.0)
 * The boss bar is stored in plotScope under "bossbar_<playerUUID>" for later updates.
 * s: 19.4
 */
class SendBossBarNode(params: Map<String, Any>) : IAction {
    override val nodeId = "send_boss_bar"
    override val displayName = "Send Boss Bar"
    private val playerVar: String = params["player"] as? String ?: error("player param required")
    private val title: String = params["title"] as? String ?: ""
    private val color: String = params["color"] as? String ?: "PURPLE"
    private val progress: Double = params["progress"] as? Double ?: 1.0

    override suspend fun execute(context: ExecutionContext) {
        val player = context.localScope.get(playerVar) as? Player ?: return
        val barColor = try {
            BarColor.valueOf(color.uppercase())
        } catch (e: IllegalArgumentException) {
            logger.warning("Unknown BarColor: $color, defaulting to PURPLE")
            BarColor.PURPLE
        }
        val clampedProgress = progress.coerceIn(0.0, 1.0)
        context.syncContext {
            @Suppress("DEPRECATION")
            val bossBar = Bukkit.createBossBar(title, barColor, BarStyle.SOLID)
            bossBar.progress = clampedProgress
            bossBar.addPlayer(player)
            // Remove any existing bossbar for this player before storing the new one
            (context.plotScope.get("bossbar_${player.uniqueId}") as? org.bukkit.boss.BossBar)
                ?.removePlayer(player)
            context.plotScope.set("bossbar_${player.uniqueId}", bossBar)
        }
    }
}
