package com.opencreativeplus.plugin.registry

import com.opencreativeplus.plugin.node.action.SendMessageAction
import com.opencreativeplus.plugin.node.action.WaitAction
import com.opencreativeplus.plugin.node.array.AddToListNode
import com.opencreativeplus.plugin.node.array.CreateListNode
import com.opencreativeplus.plugin.node.array.FilterListNode
import com.opencreativeplus.plugin.node.array.GetListElementNode
import com.opencreativeplus.plugin.node.array.GetListSizeNode
import com.opencreativeplus.plugin.node.condition.EqualsCondition
import com.opencreativeplus.plugin.node.condition.GreaterThanCondition
import com.opencreativeplus.plugin.node.condition.LessThanCondition
import com.opencreativeplus.plugin.node.event.OnJoinEvent
import com.opencreativeplus.plugin.node.entity.GetNearbyEntitiesNode
import com.opencreativeplus.plugin.node.entity.KillEntityNode
import com.opencreativeplus.plugin.node.entity.MoveEntityToNode
import com.opencreativeplus.plugin.node.entity.SetEntityAINode
import com.opencreativeplus.plugin.node.entity.SetEntityHealthNode
import com.opencreativeplus.plugin.node.entity.SpawnEntityNode
import com.opencreativeplus.plugin.node.loop.ForEachNode
import com.opencreativeplus.plugin.node.loop.RepeatNode
import com.opencreativeplus.plugin.node.value.AddValue
import com.opencreativeplus.plugin.node.visual.DrawLineNode
import com.opencreativeplus.plugin.node.visual.PlaySoundNode
import com.opencreativeplus.plugin.node.visual.SpawnParticleNode
import com.opencreativeplus.plugin.node.player.TeleportPlayerNode
import com.opencreativeplus.plugin.node.player.TeleportToPlayerNode
import com.opencreativeplus.plugin.node.player.LaunchPlayerNode
import com.opencreativeplus.plugin.node.player.SetPlayerFlightNode
import com.opencreativeplus.plugin.node.player.ApplyPotionEffectNode
import com.opencreativeplus.plugin.node.player.RemovePotionEffectNode
import com.opencreativeplus.plugin.node.player.SetPlayerHealthNode
import com.opencreativeplus.plugin.node.player.SetPlayerFoodLevelNode
import com.opencreativeplus.plugin.node.player.GiveExperienceNode
import com.opencreativeplus.plugin.node.player.SetGameModeNode
import com.opencreativeplus.plugin.node.inventory.GiveItemNode
import com.opencreativeplus.plugin.node.inventory.RemoveItemNode
import com.opencreativeplus.plugin.node.inventory.ClearInventoryNode
import com.opencreativeplus.plugin.node.inventory.HasItemNode
import com.opencreativeplus.plugin.node.value.DivideValue
import com.opencreativeplus.plugin.node.value.EqualsValue
import com.opencreativeplus.plugin.node.value.GreaterThanValue
import com.opencreativeplus.plugin.node.value.LessThanValue
import com.opencreativeplus.plugin.node.value.MultiplyValue
import com.opencreativeplus.plugin.node.value.SubtractValue
import org.bukkit.Material

/**
 * Registers all built-in nodes into the NodeRegistry.
 * 25.2, 7.1, 7.2, 7.3, 30.1, 30.2, 30.4, 31.1, 31.2, 31.3
 */
object BuiltInNodeRegistry {

    fun register(registry: NodeRegistryImpl) {
        registerEvents(registry)
        registerActions(registry)
        registerConditions(registry)
        registerValues(registry)
        registerLoopNodes(registry)
        registerArrayNodes(registry)
        registerEntityNodes(registry)
        registerVisualEffectNodes(registry)
        registerTeleportNodes(registry)
        registerPlayerStatNodes(registry)
        registerInventoryNodes(registry)
    }

    private fun registerEvents(registry: NodeRegistryImpl) {
        registry.registerEvent(Material.DIAMOND_BLOCK) { OnJoinEvent() }
    }

    private fun registerActions(registry: NodeRegistryImpl) {
        registry.registerAction(Material.PAPER) { params -> SendMessageAction(params) }
        registry.registerAction(Material.CLOCK) { params -> WaitAction(params) }
    }

    private fun registerConditions(registry: NodeRegistryImpl) {
        registry.registerCondition(Material.COMPARATOR) { params ->
            EqualsCondition(params["left"], params["right"])
        }
        registry.registerCondition(Material.REPEATER) { params ->
            GreaterThanCondition(params["left"], params["right"])
        }
        registry.registerCondition(Material.DAYLIGHT_DETECTOR) { params ->
            LessThanCondition(params["left"], params["right"])
        }
    }

    private fun registerValues(registry: NodeRegistryImpl) {
        // Arithmetic
        registry.registerValue(Material.GOLD_BLOCK) { params ->
            AddValue(params["left"], params["right"])
        }
        registry.registerValue(Material.IRON_BLOCK) { params ->
            SubtractValue(params["left"], params["right"])
        }
        registry.registerValue(Material.EMERALD_BLOCK) { params ->
            MultiplyValue(params["left"], params["right"])
        }
        registry.registerValue(Material.LAPIS_BLOCK) { params ->
            DivideValue(params["left"], params["right"])
        }
        // Comparisons
        registry.registerValue(Material.REDSTONE_BLOCK) { params ->
            EqualsValue(params["left"], params["right"])
        }
        registry.registerValue(Material.COAL_BLOCK) { params ->
            GreaterThanValue(params["left"], params["right"])
        }
        registry.registerValue(Material.NETHERITE_BLOCK) { params ->
            LessThanValue(params["left"], params["right"])
        }
    }

    private fun registerLoopNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.CHAIN) { params -> ForEachNode(params) }
        registry.registerAction(Material.REPEATING_COMMAND_BLOCK) { params -> RepeatNode(params) }
    }

    private fun registerArrayNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.CHEST) { params -> CreateListNode(params) }
        registry.registerAction(Material.HOPPER) { params -> AddToListNode(params) }
        registry.registerValue(Material.OBSERVER) { params -> GetListSizeNode(params) }
        registry.registerValue(Material.BARREL) { params -> GetListElementNode(params) }
        registry.registerValue(Material.DROPPER) { params -> FilterListNode(params) }
    }

    private fun registerEntityNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.ZOMBIE_HEAD) { params -> SpawnEntityNode(params) }
        registry.registerAction(Material.SKELETON_SKULL) { params -> KillEntityNode(params) }
        registry.registerAction(Material.CREEPER_HEAD) { params -> SetEntityAINode(params) }
        registry.registerAction(Material.WITHER_SKELETON_SKULL) { params -> SetEntityHealthNode(params) }
        registry.registerAction(Material.PLAYER_HEAD) { params -> MoveEntityToNode(params) }
        registry.registerValue(Material.DRAGON_HEAD) { params -> GetNearbyEntitiesNode(params) }
    }

    private fun registerVisualEffectNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.FIREWORK_ROCKET) { params -> SpawnParticleNode(params) }
        registry.registerAction(Material.JUKEBOX) { params -> PlaySoundNode(params) }
        registry.registerAction(Material.GLOWSTONE) { params -> DrawLineNode(params) }
    }

    private fun registerTeleportNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.ENDER_PEARL) { params -> TeleportPlayerNode(params) }
        registry.registerAction(Material.EYE_OF_ENDER) { params -> TeleportToPlayerNode(params) }
        registry.registerAction(Material.FEATHER) { params -> LaunchPlayerNode(params) }
        registry.registerAction(Material.ELYTRA) { params -> SetPlayerFlightNode(params) }
    }

    private fun registerPlayerStatNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.POTION) { params -> ApplyPotionEffectNode(params) }
        registry.registerAction(Material.GLASS_BOTTLE) { params -> RemovePotionEffectNode(params) }
        registry.registerAction(Material.RED_DYE) { params -> SetPlayerHealthNode(params) }
        registry.registerAction(Material.COOKED_BEEF) { params -> SetPlayerFoodLevelNode(params) }
        registry.registerAction(Material.EXPERIENCE_BOTTLE) { params -> GiveExperienceNode(params) }
        registry.registerAction(Material.NETHER_STAR) { params -> SetGameModeNode(params) }
    }

    private fun registerInventoryNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.CHEST_MINECART) { params -> GiveItemNode(params) }
        registry.registerAction(Material.HOPPER_MINECART) { params -> RemoveItemNode(params) }
        registry.registerAction(Material.TNT_MINECART) { params -> ClearInventoryNode(params) }
        registry.registerCondition(Material.ITEM_FRAME) { params -> HasItemNode(params) }
    }
}
