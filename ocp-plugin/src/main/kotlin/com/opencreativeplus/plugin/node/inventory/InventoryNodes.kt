package com.opencreativeplus.plugin.node.inventory

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("InventoryNodes")

/**
 * Gives an item to a player's inventory.
 * If the inventory is full, drops remaining items at the player's feet.
 * Params: "player" (String var name), "material" (String Material name, default "STONE"),
 *         "amount" (Int, default 1), "name" (String optional display name, default null)
 * s: 17.1, 17.5
 */
class GiveItemNode(params: Map<String, Any>) : IAction {
    override val nodeId = "give_item"
    override val displayName = "Give Item"
    private val playerVar: String = params["player"] as? String ?: error("player param required")
    private val materialName: String = params["material"] as? String ?: "STONE"
    private val amount: Int = params["amount"] as? Int ?: 1
    private val displayName_: String? = params["name"] as? String

    override suspend fun execute(context: ExecutionContext) {
        val player = context.localScope.get(playerVar) as? Player ?: return
        val material = try {
            Material.valueOf(materialName.uppercase())
        } catch (e: IllegalArgumentException) {
            logger.warning("Unknown Material: $materialName")
            return
        }
        context.syncContext {
            val itemStack = ItemStack(material, amount)
            if (displayName_ != null) {
                val meta = itemStack.itemMeta
                if (meta != null) {
                    meta.setDisplayName(displayName_)
                    itemStack.itemMeta = meta
                }
            }
            val leftover = player.inventory.addItem(itemStack)
            if (leftover.isNotEmpty()) {
                leftover.values.forEach { item ->
                    player.world.dropItem(player.location, item)
                }
            }
        }
    }
}

/**
 * Removes a specified amount of a material from a player's inventory.
 * Params: "player" (String var name), "material" (String Material name), "amount" (Int, default 1)
 * s: 17.2
 */
class RemoveItemNode(params: Map<String, Any>) : IAction {
    override val nodeId = "remove_item"
    override val displayName = "Remove Item"
    private val playerVar: String = params["player"] as? String ?: error("player param required")
    private val materialName: String = params["material"] as? String ?: error("material param required")
    private val amount: Int = params["amount"] as? Int ?: 1

    override suspend fun execute(context: ExecutionContext) {
        val player = context.localScope.get(playerVar) as? Player ?: return
        val material = try {
            Material.valueOf(materialName.uppercase())
        } catch (e: IllegalArgumentException) {
            logger.warning("Unknown Material: $materialName")
            return
        }
        context.syncContext {
            player.inventory.removeItem(ItemStack(material, amount))
        }
    }
}

/**
 * Clears all items from a player's inventory.
 * Params: "player" (String var name)
 * s: 17.3
 */
class ClearInventoryNode(params: Map<String, Any>) : IAction {
    override val nodeId = "clear_inventory"
    override val displayName = "Clear Inventory"
    private val playerVar: String = params["player"] as? String ?: error("player param required")

    override suspend fun execute(context: ExecutionContext) {
        val player = context.localScope.get(playerVar) as? Player ?: return
        context.syncContext { player.inventory.clear() }
    }
}

/**
 * Condition node that evaluates to true if a player has at least a specified amount of a material.
 * Params: "player" (String var name), "material" (String Material name), "amount" (Int, default 1)
 * s: 17.4
 */
class HasItemNode(params: Map<String, Any>) : ICondition {
    override val nodeId = "has_item"
    override val displayName = "Has Item"
    private val playerVar: String = params["player"] as? String ?: error("player param required")
    private val materialName: String = params["material"] as? String ?: error("material param required")
    private val amount: Int = params["amount"] as? Int ?: 1

    override suspend fun evaluate(context: ExecutionContext): Boolean {
        val player = context.localScope.get(playerVar) as? Player ?: return false
        val material = try {
            Material.valueOf(materialName.uppercase())
        } catch (e: IllegalArgumentException) {
            logger.warning("Unknown Material: $materialName")
            return false
        }
        return context.syncContext {
            val count = player.inventory.contents
                .filterNotNull()
                .filter { it.type == material }
                .sumOf { it.amount }
            count >= amount
        }
    }
}
