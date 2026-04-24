package com.opencreativeplus.plugin.registry

import com.opencreativeplus.plugin.event.EventDispatcher
import com.opencreativeplus.plugin.mode.ModeManagerImpl
import com.opencreativeplus.plugin.node.action.SendMessageAction
import com.opencreativeplus.plugin.node.action.WaitAction
import com.opencreativeplus.plugin.node.gui.GUIDesignerNode
import com.opencreativeplus.plugin.node.gui.OpenMenuNode
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import kotlinx.coroutines.CoroutineScope
import org.bukkit.plugin.Plugin
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
import com.opencreativeplus.plugin.node.world.SetBlockNode
import com.opencreativeplus.plugin.node.world.GetBlockNode
import com.opencreativeplus.plugin.node.world.SetWeatherNode
import com.opencreativeplus.plugin.node.world.SetTimeNode
import com.opencreativeplus.plugin.node.world.CreateExplosionNode
import com.opencreativeplus.plugin.node.ui.SendTitleNode
import com.opencreativeplus.plugin.node.ui.SendActionBarNode
import com.opencreativeplus.plugin.node.ui.PlayAnimationNode
import com.opencreativeplus.plugin.node.ui.SendBossBarNode
import com.opencreativeplus.plugin.node.scoreboard.CreateScoreboardNode
import com.opencreativeplus.plugin.node.scoreboard.SetScoreboardLineNode
import com.opencreativeplus.plugin.node.scoreboard.ShowScoreboardNode
import com.opencreativeplus.plugin.node.scoreboard.HideScoreboardNode
import com.opencreativeplus.plugin.node.dialogue.SendDialogueNode
import com.opencreativeplus.plugin.node.variable.GetVariableNode
import com.opencreativeplus.plugin.node.variable.SetVariableNode
import com.opencreativeplus.plugin.node.value.DivideValue
import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.plugin.node.value.EqualsValue
import com.opencreativeplus.plugin.node.value.GreaterThanValue
import com.opencreativeplus.plugin.node.value.LessThanValue
import com.opencreativeplus.plugin.node.value.MultiplyValue
import com.opencreativeplus.plugin.node.value.SubtractValue
import org.bukkit.Material

/**
 * Registers all built-in nodes into the NodeRegistry.
 * All registrations use the explicit nodeId overload — factory is never called during registration.
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
        registerWorldNodes(registry)
        registerUINodes(registry)
        registerScoreboardNodes(registry)
        registerDialogueNodes(registry)
    }

    /**
     * Registers action nodes that require a Plugin instance (e.g. BukkitScheduler access).
     * Call this after [register] once the plugin is available.
     *
     * Requirements: 11.1
     */
    fun registerPluginActions(registry: NodeRegistryImpl, plugin: Plugin) {
        registry.registerAction(Material.CLOCK, "wait") { params -> WaitAction(params, plugin) }
    }

    /**
     * Registers GUI nodes that require runtime service dependencies.
     * Call this after [register] once the plugin services are available.
     *
     * Requirements: 1.1, 1.2
     */
    fun registerGUINodes(
        registry: NodeRegistryImpl,
        plugin: Plugin,
        modeManager: ModeManagerImpl,
        plotManager: PlotManagerImpl,
        eventDispatcher: EventDispatcher,
        scope: CoroutineScope
    ) {
        // GUIDesignerNode — opens the 54-slot editor for the player in DEV mode (req 1.2)
        registry.registerAction(Material.CRAFTING_TABLE, "gui_designer") { params ->
            GUIDesignerNode(params, plugin, modeManager, plotManager)
        }

        // OpenMenuNode — opens a custom menu for a target player (req 1.1)
        registry.registerAction(Material.ENDER_CHEST, "open_menu") { params ->
            OpenMenuNode(params, eventDispatcher, scope, plugin)
        }
    }

    private fun registerEvents(registry: NodeRegistryImpl) {
        registry.registerEvent(Material.DIAMOND_BLOCK) { OnJoinEvent() }
    }

    private fun registerActions(registry: NodeRegistryImpl) {
        registry.registerAction(Material.PAPER, "send_message") { params -> SendMessageAction(params) }
        // WaitAction requires a Plugin instance — registered via registerPluginActions()
    }

    private fun registerConditions(registry: NodeRegistryImpl) {
        registry.registerCondition(Material.COMPARATOR, "equals") { params ->
            EqualsCondition(params["left"], params["right"])
        }
        registry.registerCondition(Material.REPEATER, "greater_than") { params ->
            GreaterThanCondition(params["left"], params["right"])
        }
        registry.registerCondition(Material.DAYLIGHT_DETECTOR, "less_than") { params ->
            LessThanCondition(params["left"], params["right"])
        }
    }

    private fun registerValues(registry: NodeRegistryImpl) {
        // Arithmetic
        registry.registerValue(Material.GOLD_BLOCK, "add") { params ->
            AddValue(params["left"], params["right"])
        }
        registry.registerValue(Material.COPPER_BLOCK, "subtract") { params ->
            SubtractValue(params["left"], params["right"])
        }
        registry.registerValue(Material.AMETHYST_BLOCK, "multiply") { params ->
            MultiplyValue(params["left"], params["right"])
        }
        registry.registerValue(Material.QUARTZ_BLOCK, "divide") { params ->
            DivideValue(params["left"], params["right"])
        }
        // Comparisons
        registry.registerValue(Material.REDSTONE_BLOCK, "equals_value") { params ->
            EqualsValue(params["left"], params["right"])
        }
        registry.registerValue(Material.COAL_BLOCK, "greater_than_value") { params ->
            GreaterThanValue(params["left"], params["right"])
        }
        registry.registerValue(Material.NETHERITE_BLOCK, "less_than_value") { params ->
            LessThanValue(params["left"], params["right"])
        }
    }

    private fun registerLoopNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.CHAIN, "foreach") { params -> ForEachNode(params) }
        registry.registerAction(Material.REPEATING_COMMAND_BLOCK, "repeat") { params -> RepeatNode(params) }
    }

    private fun registerArrayNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.CHEST, "create_list") { params -> CreateListNode(params) }
        registry.registerAction(Material.HOPPER, "add_to_list") { params -> AddToListNode(params) }
        registry.registerValue(Material.OBSERVER, "get_list_size") { params -> GetListSizeNode(params) }
        registry.registerValue(Material.BARREL, "get_list_element") { params -> GetListElementNode(params) }
        registry.registerValue(Material.DROPPER, "filter_list") { params -> FilterListNode(params) }
    }

    private fun registerEntityNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.ZOMBIE_HEAD, "spawn_entity") { params -> SpawnEntityNode(params) }
        registry.registerAction(Material.SKELETON_SKULL, "kill_entity") { params -> KillEntityNode(params) }
        registry.registerAction(Material.CREEPER_HEAD, "set_entity_ai") { params -> SetEntityAINode(params) }
        registry.registerAction(Material.WITHER_SKELETON_SKULL, "set_entity_health") { params -> SetEntityHealthNode(params) }
        registry.registerAction(Material.PLAYER_HEAD, "move_entity_to") { params -> MoveEntityToNode(params) }
        registry.registerValue(Material.DRAGON_HEAD, "get_nearby_entities") { params -> GetNearbyEntitiesNode(params) }
    }

    private fun registerVisualEffectNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.FIREWORK_ROCKET, "spawn_particle") { params -> SpawnParticleNode(params) }
        registry.registerAction(Material.JUKEBOX, "play_sound") { params -> PlaySoundNode(params) }
        registry.registerAction(Material.GLOWSTONE, "draw_line") { params -> DrawLineNode(params) }
    }

    private fun registerTeleportNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.ENDER_PEARL, "teleport_player") { params -> TeleportPlayerNode(params) }
        registry.registerAction(Material.ENDER_EYE, "teleport_to_player") { params -> TeleportToPlayerNode(params) }
        registry.registerAction(Material.FEATHER, "launch_player") { params -> LaunchPlayerNode(params) }
        registry.registerAction(Material.ELYTRA, "set_player_flight") { params -> SetPlayerFlightNode(params) }
    }

    private fun registerPlayerStatNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.POTION, "apply_potion_effect") { params -> ApplyPotionEffectNode(params) }
        registry.registerAction(Material.GLASS_BOTTLE, "remove_potion_effect") { params -> RemovePotionEffectNode(params) }
        registry.registerAction(Material.RED_DYE, "set_player_health") { params -> SetPlayerHealthNode(params) }
        registry.registerAction(Material.COOKED_BEEF, "set_player_food_level") { params -> SetPlayerFoodLevelNode(params) }
        registry.registerAction(Material.EXPERIENCE_BOTTLE, "give_experience") { params -> GiveExperienceNode(params) }
        registry.registerAction(Material.NETHER_STAR, "set_game_mode") { params -> SetGameModeNode(params) }
    }

    private fun registerInventoryNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.CHEST_MINECART, "give_item") { params -> GiveItemNode(params) }
        registry.registerAction(Material.HOPPER_MINECART, "remove_item") { params -> RemoveItemNode(params) }
        registry.registerAction(Material.TNT_MINECART, "clear_inventory") { params -> ClearInventoryNode(params) }
        registry.registerCondition(Material.ITEM_FRAME, "has_item") { params -> HasItemNode(params) }
    }

    private fun registerWorldNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.GRASS_BLOCK, "set_block") { params -> SetBlockNode(params) }
        registry.registerValue(Material.STONE, "get_block") { params -> GetBlockNode(params) }
        registry.registerAction(Material.LIGHTNING_ROD, "set_weather") { params -> SetWeatherNode(params) }
        registry.registerAction(Material.SUNFLOWER, "set_time") { params -> SetTimeNode(params) }
        registry.registerAction(Material.TNT, "create_explosion") { params -> CreateExplosionNode(params) }
    }

    private fun registerUINodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.BOOK, "send_title") { params -> SendTitleNode(params) }
        registry.registerAction(Material.WRITABLE_BOOK, "send_action_bar") { params -> SendActionBarNode(params) }
        registry.registerAction(Material.BLAZE_ROD, "play_animation") { params -> PlayAnimationNode(params) }
        registry.registerAction(Material.DRAGON_EGG, "send_boss_bar") { params -> SendBossBarNode(params) }
    }

    private fun registerScoreboardNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.OAK_SIGN, "create_scoreboard") { params -> CreateScoreboardNode(params) }
        registry.registerAction(Material.BIRCH_SIGN, "set_scoreboard_line") { params -> SetScoreboardLineNode(params) }
        registry.registerAction(Material.SPRUCE_SIGN, "show_scoreboard") { params -> ShowScoreboardNode(params) }
        registry.registerAction(Material.JUNGLE_SIGN, "hide_scoreboard") { params -> HideScoreboardNode(params) }
    }

    private fun registerDialogueNodes(registry: NodeRegistryImpl) {
        registry.registerAction(Material.WRITTEN_BOOK, "send_dialogue") { params -> SendDialogueNode(params) }
    }

    /**
     * Registers variable nodes that require a VariableManager instance.
     * Call this after [register] once the VariableManager is available.
     *
     * Requirements: 6.1, 6.2
     */
    fun registerVariableNodes(registry: NodeRegistryImpl, variableManager: VariableManager) {
        registry.registerAction(Material.IRON_BLOCK, "set_variable") { params ->
            SetVariableNode(params, variableManager)
        }
        registry.registerValue(Material.IRON_ORE, "get_variable") { params ->
            GetVariableNode(params, variableManager)
        }
    }
}
