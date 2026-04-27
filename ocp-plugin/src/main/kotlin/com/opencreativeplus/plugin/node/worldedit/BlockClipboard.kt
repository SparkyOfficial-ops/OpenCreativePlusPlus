package com.opencreativeplus.plugin.node.worldedit

import org.bukkit.block.data.BlockData

/**
 * Represents a single block snapshot with a relative offset from the copy origin.
 *
 * @param dx relative X offset from the copy origin
 * @param dy relative Y offset from the copy origin
 * @param dz relative Z offset from the copy origin
 * @param data the Bukkit BlockData (material + block state) of the block
 *
 * Requirements: 10.3
 */
data class BlockSnapshot(
    val dx: Int,
    val dy: Int,
    val dz: Int,
    val data: BlockData
)

/**
 * A clipboard holding a list of block snapshots captured from a cuboid region.
 *
 * @param blocks the list of block snapshots with relative offsets
 * @param size the total number of blocks in the clipboard
 *
 * Requirements: 10.3
 */
data class BlockClipboard(
    val blocks: List<BlockSnapshot>,
    val size: Int
)
