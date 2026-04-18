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
import com.opencreativeplus.plugin.node.loop.ForEachNode
import com.opencreativeplus.plugin.node.loop.RepeatNode
import com.opencreativeplus.plugin.node.value.AddValue
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
}
