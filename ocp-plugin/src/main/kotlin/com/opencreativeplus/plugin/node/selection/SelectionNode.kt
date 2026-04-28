package com.opencreativeplus.plugin.node.selection

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import org.bukkit.entity.Entity
import org.bukkit.entity.Player

/**
 * Selection node that modifies the target list in the execution context.
 *
 * Block material: Material.PURPUR_BLOCK
 * Registered in BuiltInNodeRegistry as action node "select_targets".
 *
 * Requirements: 1.3, 1.4, 1.5, 1.6, 1.7
 */
class SelectionNode(
    private val mode: SelectionMode,
    private val radius: Double = 0.0
) : IAction {

    enum class SelectionMode {
        /** Selects all players currently in the same world as the triggering player. Req 1.3 */
        ALL_PLAYERS,
        /** Selects all entities within [radius] of the triggering player. Req 1.4, 1.7 */
        RADIUS,
        /** Selects the killer entity from eventData. Req 1.5 */
        KILLER,
        /** Selects the victim entity from eventData. Req 1.6 */
        VICTIM
    }

    override val nodeId = "select_targets"
    override val displayName = "Select Targets"

    override suspend fun execute(context: ExecutionContext) {
        context.targets.clear()
        when (mode) {
            SelectionMode.ALL_PLAYERS -> {
                // Req 1.3: add all players on the plot (same world as the triggering player)
                val world = context.player?.world
                if (world != null) {
                    context.targets.addAll(world.players)
                }
            }
            SelectionMode.RADIUS -> {
                // Req 1.4, 1.7: find entities within radius; empty list is not an error
                val loc = context.player?.location
                if (loc != null && radius > 0.0) {
                    val nearby = loc.world?.getNearbyEntities(loc, radius, radius, radius)
                    if (nearby != null) {
                        context.targets.addAll(nearby)
                    }
                }
                // If no entities found or player is null — targets stays empty (Req 1.7)
            }
            SelectionMode.KILLER -> {
                // Req 1.5: extract killer from eventData
                val killer = context.eventData["killer"] as? Entity
                if (killer != null) {
                    context.targets.add(killer)
                }
            }
            SelectionMode.VICTIM -> {
                // Req 1.6: extract victim from eventData
                val victim = context.eventData["victim"] as? Entity
                if (victim != null) {
                    context.targets.add(victim)
                }
            }
        }
    }

    companion object {
        /**
         * Factory function for use in BuiltInNodeRegistry.
         * Reads "mode" (String) and "radius" (Double) from params.
         */
        fun fromParams(params: Map<String, Any>): SelectionNode {
            val modeStr = params["mode"] as? String ?: "ALL_PLAYERS"
            val mode = try {
                SelectionMode.valueOf(modeStr.uppercase())
            } catch (e: IllegalArgumentException) {
                SelectionMode.ALL_PLAYERS
            }
            val radius = params["radius"] as? Double ?: 0.0
            return SelectionNode(mode, radius)
        }
    }
}
