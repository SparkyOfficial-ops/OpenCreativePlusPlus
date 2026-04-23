package com.opencreativeplus.plugin.scanner

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.TileState
import org.bukkit.plugin.Plugin
import org.bukkit.persistence.PersistentDataType

/**
 * Automatically places and removes a CHEST block directly above a Category_Block
 * to serve as a parameter container.
 *
 * PDC key written to the chest: ocp:param_chest = "true"
 *
 * Requirements: 8.1, 8.2, 8.3, 8.4
 */
class ParameterPlacer(private val plugin: Plugin) {

    private val keyParamChest = NamespacedKey(plugin, "param_chest")

    /**
     * Places a CHEST directly above [categoryBlock] and tags it with ocp:param_chest = "true".
     *
     * - If the block above is not air, logs a WARNING and returns false (Req 8.2).
     * - If a param chest already exists above the block, removes it first, then places a new one (Req 8.4).
     *
     * @return true if the chest was placed successfully, false otherwise.
     */
    fun placeChest(categoryBlock: Block): Boolean {
        // If there's already a param chest, remove it first (Req 8.4)
        if (hasParamChest(categoryBlock)) {
            removeChest(categoryBlock)
        }

        // After potential removal, re-check the block above
        val targetBlock = categoryBlock.getRelative(BlockFace.UP)
        if (targetBlock.type != Material.AIR) {
            plugin.logger.warning(
                "ParameterPlacer: cannot place chest above ${categoryBlock.location} — block above is ${targetBlock.type}"
            )
            return false
        }

        // Place the chest (Req 8.1)
        targetBlock.type = Material.CHEST

        // Write PDC tag to the chest block's tile entity (Req 8.3)
        val chestState = targetBlock.state as? TileState ?: return true
        chestState.persistentDataContainer.set(keyParamChest, PersistentDataType.STRING, "true")
        chestState.update()

        return true
    }

    /**
     * Removes the parameter chest above [categoryBlock] if one exists.
     */
    fun removeChest(categoryBlock: Block) {
        val above = categoryBlock.getRelative(BlockFace.UP)
        if (above.type == Material.CHEST && isParamChest(above)) {
            above.type = Material.AIR
        }
    }

    /**
     * Returns true if the block directly above [categoryBlock] is a CHEST
     * tagged with ocp:param_chest = "true".
     */
    fun hasParamChest(categoryBlock: Block): Boolean =
        isParamChest(categoryBlock.getRelative(BlockFace.UP))

    // ── private helpers ──────────────────────────────────────────────────────

    private fun isParamChest(block: Block): Boolean {
        if (block.type != Material.CHEST) return false
        val tileState = block.state as? TileState ?: return false
        val value = tileState.persistentDataContainer
            .get(keyParamChest, PersistentDataType.STRING)
        return value == "true"
    }
}
