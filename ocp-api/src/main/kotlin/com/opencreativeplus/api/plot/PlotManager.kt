package com.opencreativeplus.api.plot

import java.util.UUID

/
 * Interface for managing plot lifecycle operations.
 * Handles plot creation, loading, unloading, and retrieval.
 */
interface PlotManager {
    /
     * Create a new plot for the specified owner.
     *
     * @param owner The UUID of the player who owns the plot
     * @return The newly created plot
     */
    suspend fun createPlot(owner: UUID): Plot
    
    /
     * Load an existing plot from the database.
     *
     * @param plotId The UUID of the plot to load
     * @return The loaded plot
     */
    suspend fun loadPlot(plotId: UUID): Plot
    
    /
     * Unload a plot and save its state.
     *
     * @param plotId The UUID of the plot to unload
     */
    suspend fun unloadPlot(plotId: UUID)
    
    /
     * Get a plot by its UUID.
     *
     * @param plotId The UUID of the plot
     * @return The plot, or null if not found
     */
    suspend fun getPlot(plotId: UUID): Plot?
    
    /
     * Get the plot owned by a specific player.
     *
     * @param player The UUID of the player
     * @return The player's plot, or null if not found
     */
    suspend fun getPlayerPlot(player: UUID): Plot?
}
