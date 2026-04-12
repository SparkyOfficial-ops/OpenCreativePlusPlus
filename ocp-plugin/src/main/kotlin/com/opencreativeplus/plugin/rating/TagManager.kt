package com.opencreativeplus.plugin.rating

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.core.database.PlotPersistence
import org.bukkit.entity.Player

/
 * Manages plot tags with a maximum of 5 tags per plot.
 *
 22.1, 22.2, 22.3, 22.5
 */
class TagManager(
    private val plotPersistence: PlotPersistence
) {

    companion object {
        const val MAX_TAGS = 5
    }

    /
     * Add a tag to a plot. Only the owner can add tags.
     * Returns false if the tag limit is reached or tag already exists.
     22.1, 22.2, 22.5
     */
    suspend fun addTag(player: Player, plot: Plot, tag: String): Boolean {
        if (plot.owner != player.uniqueId) {
            player.sendMessage("§c[OCP] Only the plot owner can manage tags.")
            return false
        }
        val currentTags = plot.metadata.tags
        if (currentTags.size >= MAX_TAGS) {
            player.sendMessage("§c[OCP] Maximum of $MAX_TAGS tags allowed.")
            return false
        }
        if (tag in currentTags) {
            player.sendMessage("§c[OCP] Tag '$tag' already exists.")
            return false
        }
        val newTags = currentTags + tag
        plotPersistence.updatePlotMetadata(plot.id, tags = newTags)
        player.sendMessage("§a[OCP] Tag '$tag' added.")
        return true
    }

    /
     * Remove a tag from a plot. Only the owner can remove tags.
     22.3
     */
    suspend fun removeTag(player: Player, plot: Plot, tag: String): Boolean {
        if (plot.owner != player.uniqueId) {
            player.sendMessage("§c[OCP] Only the plot owner can manage tags.")
            return false
        }
        val newTags = plot.metadata.tags - tag
        plotPersistence.updatePlotMetadata(plot.id, tags = newTags)
        player.sendMessage("§a[OCP] Tag '$tag' removed.")
        return true
    }
}
