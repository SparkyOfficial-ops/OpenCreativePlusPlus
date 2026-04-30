package com.opencreativeplus.plugin.node.player

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import org.bukkit.GameMode
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("PlayerStatNodes")

/**
 * Applies a potion effect to the current target entity (LivingEntity).
 * Params: "effect" (String PotionEffectType name),
 *         "duration" (Int ticks, default 200), "amplifier" (Int, default 0)
 * s: 16.1
 */
class ApplyPotionEffectNode(params: Map<String, Any>) : IAction {
    override val nodeId = "apply_potion_effect"
    override val displayName = "Apply Potion Effect"
    private val effectName: String = params["effect"] as? String ?: error("effect param required")
    private val duration: Int = params["duration"] as? Int ?: 200
    private val amplifier: Int = params["amplifier"] as? Int ?: 0

    override suspend fun execute(context: ExecutionContext) {
        val entity = context.currentTarget as? LivingEntity ?: return
        val effectType = PotionEffectType.getByName(effectName.uppercase())
        if (effectType == null) {
            logger.warning("Unknown PotionEffectType: $effectName")
            return
        }
        context.syncContext { entity.addPotionEffect(PotionEffect(effectType, duration, amplifier)) }
    }
}

/**
 * Removes a potion effect from the current target entity (LivingEntity).
 * Params: "effect" (String PotionEffectType name)
 * s: 16.2
 */
class RemovePotionEffectNode(params: Map<String, Any>) : IAction {
    override val nodeId = "remove_potion_effect"
    override val displayName = "Remove Potion Effect"
    private val effectName: String = params["effect"] as? String ?: error("effect param required")

    override suspend fun execute(context: ExecutionContext) {
        val entity = context.currentTarget as? LivingEntity ?: return
        val effectType = PotionEffectType.getByName(effectName.uppercase())
        if (effectType == null) {
            logger.warning("Unknown PotionEffectType: $effectName")
            return
        }
        context.syncContext { entity.removePotionEffect(effectType) }
    }
}

/**
 * Sets the current target entity's health (LivingEntity), clamped to [0, maxHealth].
 * Params: "health" (Double)
 * s: 16.3
 */
class SetPlayerHealthNode(params: Map<String, Any>) : IAction {
    override val nodeId = "set_player_health"
    override val displayName = "Set Player Health"
    private val health: Double = params["health"] as? Double ?: 20.0

    override suspend fun execute(context: ExecutionContext) {
        val entity = context.currentTarget as? LivingEntity ?: return
        context.syncContext {
            val maxHealth = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0
            entity.health = health.coerceIn(0.0, maxHealth)
        }
    }
}

/**
 * Sets the current target player's food level, clamped to [0, 20].
 * Params: "food" (Int, default 20)
 * s: 16.4
 */
class SetPlayerFoodLevelNode(params: Map<String, Any>) : IAction {
    override val nodeId = "set_player_food_level"
    override val displayName = "Set Player Food Level"
    private val food: Int = params["food"] as? Int ?: 20

    override suspend fun execute(context: ExecutionContext) {
        val player = context.currentTarget as? Player ?: return
        context.syncContext { player.foodLevel = food.coerceIn(0, 20) }
    }
}

/**
 * Gives experience points to the current target player.
 * Params: "exp" (Int, default 0)
 * s: 16.5
 */
class GiveExperienceNode(params: Map<String, Any>) : IAction {
    override val nodeId = "give_experience"
    override val displayName = "Give Experience"
    private val exp: Int = params["exp"] as? Int ?: 0

    override suspend fun execute(context: ExecutionContext) {
        val player = context.currentTarget as? Player ?: return
        context.syncContext { player.giveExp(exp) }
    }
}

/**
 * Changes the current target player's game mode.
 * Params: "mode" (String, default "SURVIVAL")
 * s: 16.6
 */
class SetGameModeNode(params: Map<String, Any>) : IAction {
    override val nodeId = "set_game_mode"
    override val displayName = "Set Game Mode"
    private val mode: String = params["mode"] as? String ?: "SURVIVAL"

    override suspend fun execute(context: ExecutionContext) {
        val player = context.currentTarget as? Player ?: return
        val gameMode = try {
            GameMode.valueOf(mode.uppercase())
        } catch (e: IllegalArgumentException) {
            logger.warning("Unknown GameMode: $mode")
            return
        }
        context.syncContext { player.gameMode = gameMode }
    }
}
