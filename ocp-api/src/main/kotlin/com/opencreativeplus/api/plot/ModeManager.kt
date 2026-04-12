package com.opencreativeplus.api.plot

import org.bukkit.entity.Player

/
 * Interface for managing plot mode switching.
 * Handles transitions between BUILD, DEV, and PLAY modes.
 */
interface ModeManager {
    /
     * Switch a player to a different mode on their plot.
     *
     * @param player The player to switch
     * @param plot The plot to switch mode on
     * @param mode The target mode
     */
    suspend fun switchMode(player: Player, plot: Plot, mode: PlotMode)
    
    /
     * Get the current mode for a player on a plot.
     *
     * @param player The player
     * @param plot The plot
     * @return The current mode
     */
    fun getCurrentMode(player: Player, plot: Plot): PlotMode
}
