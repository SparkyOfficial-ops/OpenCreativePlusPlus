package com.opencreativeplus.plugin.node.worldedit

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.bukkit.Location
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("PasteRegionNode")

/**
 * Pastes a [BlockClipboard] stored in the plot scope at a specified origin location.
 *
 * If the clipboard variable does not exist in the plot scope, a warning is logged and
 * the operation is skipped. Block changes are performed on the main thread via
 * [ExecutionContext.syncContext]. For large clipboards (> 5000 blocks), the work is
 * split into batches of at most 5000 blocks with a 50 ms delay between batches.
 *
 * The total block count is added to [ExecutionContext.operationCount] before execution
 * so the Watchdog can account for the work.
 *
 * Params:
 *   - "origin": String — name of the localScope variable holding the paste origin Location
 *   - "clipboardName": String — name of the plot-scope variable holding the [BlockClipboard]
 *
 * Requirements: 10.2, 10.4, 10.5, 10.6
 */
class PasteRegionNode(params: Map<String, Any>) : IAction {
    override val nodeId = "paste_region"
    override val displayName = "Paste Region"

    private val originVar: String = params["origin"] as? String ?: error("origin param required")
    private val clipboardNameVar: String = params["clipboardName"] as? String ?: error("clipboardName param required")

    override suspend fun execute(context: ExecutionContext) {
        val origin = context.localScope.get(originVar) as? Location ?: return

        // Retrieve clipboard from plot scope (req 10.6)
        val clipboard = context.plotScope.get(clipboardNameVar) as? BlockClipboard
        if (clipboard == null) {
            logger.warning(
                "[OCP] PasteRegionNode: clipboard '$clipboardNameVar' not found in plot scope. Skipping paste."
            )
            return
        }

        val world = origin.world ?: return
        val originX = origin.blockX
        val originY = origin.blockY
        val originZ = origin.blockZ

        val blockCount = clipboard.size

        // Notify watchdog of the total block count before execution (req 10.4)
        context.operationCount.addAndGet(blockCount)

        // Batch execution: ≤ 5000 blocks per tick (req 10.5)
        // Cancellation check per batch — stops paste if script is cancelled by Watchdog or mode-switch.
        val batches = clipboard.blocks.chunked(5000)
        for ((index, batch) in batches.withIndex()) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                logger.warning("[OCP] PasteRegionNode: paste_region cancelled mid-flight (script terminated).")
                break
            }
            context.syncContext {
                for (snapshot in batch) {
                    val block = world.getBlockAt(
                        originX + snapshot.dx,
                        originY + snapshot.dy,
                        originZ + snapshot.dz
                    )
                    block.blockData = snapshot.data.clone()
                }
            }
            if (index < batches.size - 1) {
                delay(50L)
            }
        }
    }
}
