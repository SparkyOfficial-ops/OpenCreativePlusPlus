package com.opencreativeplus.plugin.node.worldedit

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import org.bukkit.Location
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("CopyRegionNode")

/**
 * Copies a cuboid region defined by two corner locations into a named clipboard variable
 * stored in the plot scope.
 *
 * The copy is performed on the auxiliary coroutine thread (already in the coroutine
 * dispatcher — no explicit thread switch needed for world reads in this context).
 * The resulting [BlockClipboard] is stored in [ExecutionContext.plotScope] under
 * [clipboardNameVar].
 *
 * Params:
 *   - "corner1": String — name of the localScope variable holding the first corner Location
 *   - "corner2": String — name of the localScope variable holding the second corner Location
 *   - "clipboardName": String — name of the plot-scope variable to store the clipboard in
 *
 * Requirements: 10.1, 10.3, 10.7
 */
class CopyRegionNode(params: Map<String, Any>) : IAction {
    override val nodeId = "copy_region"
    override val displayName = "Copy Region"

    private val corner1Var: String = params["corner1"] as? String ?: error("corner1 param required")
    private val corner2Var: String = params["corner2"] as? String ?: error("corner2 param required")
    private val clipboardNameVar: String = params["clipboardName"] as? String ?: error("clipboardName param required")

    override suspend fun execute(context: ExecutionContext) {
        val corner1 = context.localScope.get(corner1Var) as? Location ?: return
        val corner2 = context.localScope.get(corner2Var) as? Location ?: return

        val world = corner1.world ?: return

        val minX = minOf(corner1.blockX, corner2.blockX)
        val minY = minOf(corner1.blockY, corner2.blockY)
        val minZ = minOf(corner1.blockZ, corner2.blockZ)
        val maxX = maxOf(corner1.blockX, corner2.blockX)
        val maxY = maxOf(corner1.blockY, corner2.blockY)
        val maxZ = maxOf(corner1.blockZ, corner2.blockZ)

        // Read blocks on the main thread to ensure thread-safe world access (req 10.7)
        val snapshots = context.syncContext {
            val list = mutableListOf<BlockSnapshot>()
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    for (z in minZ..maxZ) {
                        val block = world.getBlockAt(x, y, z)
                        list.add(
                            BlockSnapshot(
                                dx = x - minX,
                                dy = y - minY,
                                dz = z - minZ,
                                data = block.blockData.clone()
                            )
                        )
                    }
                }
            }
            list
        }

        val clipboard = BlockClipboard(blocks = snapshots, size = snapshots.size)

        // Store in plot scope under the clipboard variable name (req 10.3)
        context.plotScope.set(clipboardNameVar, clipboard)

        logger.info("[OCP] CopyRegionNode: copied ${clipboard.size} blocks into clipboard '$clipboardNameVar'")
    }
}
