package com.opencreativeplus.plugin.rating

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.core.database.PlotPersistence
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Manages plot ratings (likes) with duplicate prevention.
 *
 * Requirements: 21.2, 21.3, 21.5
 */
class RatingManager(
    private val plotPersistence: PlotPersistence
) {

    /**
     * Rate (like) a plot. Returns false if the player has already rated it.
     * Requirements: 21.2, 21.3
     */
    suspend fun ratePlot(player: Player, plot: Plot): Boolean {
        if (plot.metadata.ratedBy.contains(player.uniqueId)) {
            player.sendMessage("§c[OCP] You have already rated this plot.")
            return false
        }
        plotPersistence.addRating(plot.id, player.uniqueId)
        player.sendMessage("§a[OCP] You liked '${plot.name}'!")
        return true
    }
}
