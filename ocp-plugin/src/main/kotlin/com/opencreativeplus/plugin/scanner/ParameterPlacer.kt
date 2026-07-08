package com.opencreativeplus.plugin.scanner

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.TileState
import org.bukkit.plugin.Plugin
import org.bukkit.persistence.PersistentDataType

/**
 * Automatically places and removes a BARREL block directly above a Category_Block
 * to serve as a parameter container.
 *
 * BARREL is used instead of CHEST because two adjacent CHESTs merge into a large chest
 * (54-slot), corrupting PDC and shifting inventory slots. BARRELs never connect.
 *
 * PDC key written to the barrel: ocp:param_chest = "true"
 *
 * Requirements: 8.1, 8.2, 8.3, 8.4
 */
class ParameterPlacer(private val plugin: Plugin) {

    private val keyParamChest = NamespacedKey(plugin, "param_chest")

    /**
     * Places a BARREL directly above [categoryBlock] and tags it with ocp:param_chest = "true".
     *
     * - If the block above is not air, logs a WARNING and returns false (Req 8.2).
     * - If a param barrel already exists above the block, removes it first (Req 8.4).
     *
     * @return true if the barrel was placed successfully, false otherwise.
     */
    fun placeChest(categoryBlock: Block): Boolean {
        if (hasParamChest(categoryBlock)) {
            removeChest(categoryBlock)
        }

        val targetBlock = categoryBlock.getRelative(BlockFace.UP)
        if (targetBlock.type != Material.AIR) {
            plugin.logger.warning(
                "ParameterPlacer: cannot place barrel above ${categoryBlock.location} — block above is ${targetBlock.type}"
            )
            return false
        }

        targetBlock.type = Material.BARREL

        val barrelState = targetBlock.state as? TileState ?: return true
        barrelState.persistentDataContainer.set(keyParamChest, PersistentDataType.STRING, "true")
        barrelState.update()

        return true
    }

    /**
     * Removes the parameter barrel above [categoryBlock] if one exists.
     */
    fun removeChest(categoryBlock: Block) {
        val above = categoryBlock.getRelative(BlockFace.UP)
        if (above.type == Material.BARREL && isParamChest(above)) {
            above.type = Material.AIR
        }
    }

    /**
     * Returns true if the block directly above [categoryBlock] is a BARREL
     * tagged with ocp:param_chest = "true".
     */
    fun hasParamChest(categoryBlock: Block): Boolean =
        isParamChest(categoryBlock.getRelative(BlockFace.UP))

    private fun isParamChest(block: Block): Boolean {
        if (block.type != Material.BARREL) return false
        val tileState = block.state as? TileState ?: return false
        return tileState.persistentDataContainer
            .get(keyParamChest, PersistentDataType.STRING) == "true"
    }
}
