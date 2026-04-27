package com.opencreativeplus.plugin.node.worldedit

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import kotlinx.coroutines.delay
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("FillRegionNode")

/**
 * Fills a cuboid region defined by two corner locations with a specified material.
 *
 * The region is clamped to the plot boundary before execution. Block changes are
 * performed on the main thread via [ExecutionContext.syncContext]. For large regions
 * (> 5000 blocks), the work is split into batches of at most 5000 blocks with a
 * 50 ms delay between batches to avoid blocking the main thread for too long.
 *
 * The total block count is added to [ExecutionContext.operationCount] before execution
 * so the Watchdog can account for the work.
 *
 * Params:
 *   - "corner1": String — name of the localScope variable holding the first corner Location
 *   - "corner2": String — name of the localScope variable holding the second corner Location
 *   - "material": String — Material name to fill with
 *   - "plotBounds": BoundingBox — the plot boundary used for clamping (optional; if absent, no clamping)
 *
 * Requirements: 9.1, 9.2, 9.3, 9.4, 9.5
 */
class FillRegionNode(params: Map<String, Any>) : IAction {
    override val nodeId = "fill_region"
    override val displayName = "Fill Region"

    private val corner1Var: String = params["corner1"] as? String ?: error("corner1 param required")
    private val corner2Var: String = params["corner2"] as? String ?: error("corner2 param required")
    private val materialName: String = params["material"] as? String ?: error("material param required")
    private val plotBounds: BoundingBox? = params["plotBounds"] as? BoundingBox

    override suspend fun execute(context: ExecutionContext) {
        val corner1 = context.localScope.get(corner1Var) as? Location ?: return
        val corner2 = context.localScope.get(corner2Var) as? Location ?: return

        val material = runCatching { Material.valueOf(materialName.uppercase()) }.getOrElse {
            logger.warning("[OCP] FillRegionNode: unknown material '$materialName'")
            return
        }

        val world = corner1.world ?: return

        // Compute raw region bounds
        var minX = minOf(corner1.blockX, corner2.blockX)
        var minY = minOf(corner1.blockY, corner2.blockY)
        var minZ = minOf(corner1.blockZ, corner2.blockZ)
        var maxX = maxOf(corner1.blockX, corner2.blockX)
        var maxY = maxOf(corner1.blockY, corner2.blockY)
        var maxZ = maxOf(corner1.blockZ, corner2.blockZ)

        // Clamp to plot boundary if provided
        if (plotBounds != null) {
            val clampedMinX = maxOf(minX, plotBounds.minX.toInt())
            val clampedMinY = maxOf(minY, plotBounds.minY.toInt())
            val clampedMinZ = maxOf(minZ, plotBounds.minZ.toInt())
            val clampedMaxX = minOf(maxX, (plotBounds.maxX - 1).toInt())
            val clampedMaxY = minOf(maxY, (plotBounds.maxY - 1).toInt())
            val clampedMaxZ = minOf(maxZ, (plotBounds.maxZ - 1).toInt())

            if (clampedMinX != minX || clampedMinY != minY || clampedMinZ != minZ ||
                clampedMaxX != maxX || clampedMaxY != maxY || clampedMaxZ != maxZ
            ) {
                logger.warning(
                    "[OCP] FillRegionNode: region clamped to plot boundary. " +
                        "Original: ($minX,$minY,$minZ)-($maxX,$maxY,$maxZ), " +
                        "Clamped: ($clampedMinX,$clampedMinY,$clampedMinZ)-($clampedMaxX,$clampedMaxY,$clampedMaxZ)"
                )
            }

            minX = clampedMinX
            minY = clampedMinY
            minZ = clampedMinZ
            maxX = clampedMaxX
            maxY = clampedMaxY
            maxZ = clampedMaxZ
        }

        // Bail out if clamped region is empty
        if (minX > maxX || minY > maxY || minZ > maxZ) return

        // Build list of all block positions
        val positions = mutableListOf<Triple<Int, Int, Int>>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    positions.add(Triple(x, y, z))
                }
            }
        }

        val blockCount = positions.size

        // Notify watchdog of the total block count before execution (req 9.2)
        context.operationCount.addAndGet(blockCount)

        // Batch execution: ≤ 5000 blocks per tick (req 9.3, 9.4)
        val batches = positions.chunked(5000)
        batches.forEachIndexed { index, batch ->
            context.syncContext {
                for ((x, y, z) in batch) {
                    world.getBlockAt(x, y, z).type = material
                }
            }
            // Delay between batches (not after the last one)
            if (index < batches.size - 1) {
                delay(50L)
            }
        }
    }
}
