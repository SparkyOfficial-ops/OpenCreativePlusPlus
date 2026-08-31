package com.opencreativeplus.plugin.plot

import com.opencreativeplus.api.plot.ModeManager
import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotManager
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.api.plot.PlotSettings
import com.opencreativeplus.core.database.PlotPersistence
import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.plugin.world.WorldManager
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Implementation of [PlotManager] that coordinates world creation, database persistence,
 * and in-memory plot state.
 *
 1.1, 1.2, 17.1, 32.1, 32.2, 32.3, 32.4, 32.5,
 *               13.1, 13.2, 13.3, 13.4, 13.5, 13.6, 13.7
 */
class PlotManagerImpl(
    private val plotPersistence: PlotPersistence,
    private val worldManager: WorldManager,
    private val modeManager: ModeManager,
    /** Injected to flush saved-scope variables to MongoDB on unload. Null in tests that don't need persistence. */
    private val variableManager: VariableManager? = null
) : PlotManager {

    private val logger = Logger.getLogger("PlotManagerImpl")

    /** In-memory cache of loaded plots */
    private val loadedPlots = ConcurrentHashMap<UUID, Plot>()

    /** playerId → plotId for quick lookup */
    private val playerPlotIndex = ConcurrentHashMap<UUID, UUID>()

    // -------------------------------------------------------------------------
    // PlotManager interface
    // -------------------------------------------------------------------------

    /**
     * Create a new plot for [owner]: generate worlds, persist to DB, cache in memory.
     1.1, 1.2, 17.1, 32.1
     */
    override suspend fun createPlot(owner: UUID): Plot {
        val plotId = UUID.randomUUID()
        val now = System.currentTimeMillis()
        val mainWorldName = plotId.toString()
        val devWorldName = "${plotId}_dev"

        val plot = Plot(
            id = plotId,
            owner = owner,
            name = "Plot ${plotId.toString().take(8)}",
            description = "",
            mainWorldName = mainWorldName,
            devWorldName = devWorldName,
            createdAt = now,
            updatedAt = now,
            settings = PlotSettings(),
            metadata = PlotMetadata()
        )

        worldManager.createPlotWorlds(plotId)
        plotPersistence.createPlot(plot)

        loadedPlots[plotId] = plot
        playerPlotIndex[owner] = plotId

        return plot
    }

    /**
     * Load a plot from the database and its worlds.
     17.1, 17.6
     */
    override suspend fun loadPlot(plotId: UUID): Plot {
        val plot = plotPersistence.loadPlot(plotId)
            ?: error("Plot $plotId not found in database")

        worldManager.loadPlotWorlds(plotId)
        loadedPlots[plotId] = plot
        playerPlotIndex[plot.owner] = plotId

        return plot
    }

    /**
     * Unload a plot: flush saved variables, persist state, unload worlds, remove from cache.
     * 27.5
     */
    override suspend fun unloadPlot(plotId: UUID) {
        val plot = loadedPlots[plotId] ?: return

        // Auto-save saved-scope variables BEFORE anything else so no player data is lost
        // on server restart, /clean, or automatic world unload. Req: saved-variable persistence.
        runCatching {
            variableManager?.savePlotVariables(plotId)
        }.onFailure { e ->
            logger.severe("[OCP] Failed to auto-save variables for plot $plotId before unload: ${e.message}")
        }

        plotPersistence.updatePlot(plot.copy(updatedAt = System.currentTimeMillis()))
        worldManager.unloadPlotWorlds(plotId)
        loadedPlots.remove(plotId)
        com.opencreativeplus.plugin.node.gui.PlotMenuRegistry.clearPlot(plotId)
        playerPlotIndex.remove(plot.owner)
    }

    /**
     * Get a loaded plot by ID.
     */
    override suspend fun getPlot(plotId: UUID): Plot? = loadedPlots[plotId]

    /**
     * Get the plot owned by [player].
     */
    override suspend fun getPlayerPlot(player: UUID): Plot? {
        val plotId = playerPlotIndex[player] ?: return null
        return loadedPlots[plotId]
    }

    // -------------------------------------------------------------------------
    // Permission helpers (s 32.1–32.5)
    // -------------------------------------------------------------------------

    /**
     * Returns true if [player] can edit the plot (owner or trusted).
     32.2, 32.3, 32.4
     */
    fun canEdit(player: Player, plot: Plot): Boolean =
        plot.owner == player.uniqueId || plot.trustedPlayers.contains(player.uniqueId)

    /**
     * Add a trusted player to the plot.
     32.4
     */
    suspend fun addTrustedPlayer(plotId: UUID, playerId: UUID) {
        val plot = loadedPlots[plotId] ?: return
        val updated = plot.copy(
            trustedPlayers = plot.trustedPlayers + playerId,
            updatedAt = System.currentTimeMillis()
        )
        loadedPlots[plotId] = updated
        plotPersistence.updatePlot(updated)
    }

    /**
     * Remove a trusted player from the plot.
     32.4
     */
    suspend fun removeTrustedPlayer(plotId: UUID, playerId: UUID) {
        val plot = loadedPlots[plotId] ?: return
        val updated = plot.copy(
            trustedPlayers = plot.trustedPlayers - playerId,
            updatedAt = System.currentTimeMillis()
        )
        loadedPlots[plotId] = updated
        plotPersistence.updatePlot(updated)
    }

    // -------------------------------------------------------------------------
    // Plot configuration (s 13.1–13.7)
    // -------------------------------------------------------------------------

    /**
     * Update plot settings and apply them immediately to the main world.
     13.1, 13.2, 13.3, 13.4, 13.5, 13.6
     */
    suspend fun updateSettings(plotId: UUID, settings: PlotSettings) {
        val plot = loadedPlots[plotId] ?: return
        val updated = plot.copy(settings = settings, updatedAt = System.currentTimeMillis())
        loadedPlots[plotId] = updated
        plotPersistence.updatePlot(updated)

        // Apply immediately to the main world (req 13.6)
        val worlds = worldManager.getLoadedWorlds(plotId) ?: return
        val mainWorld = worlds.first
        mainWorld.pvp = settings.pvpEnabled
        mainWorld.setGameRuleValue("doMobSpawning", settings.mobSpawningEnabled.toString())
        mainWorld.time = settings.timeOfDay
    }

    /**
     * Get the current mode for a plot (delegates to ModeManager).
     13.7
     */
    fun getCurrentMode(plot: Plot): PlotMode {
        val owner = Bukkit.getPlayer(plot.owner) ?: return PlotMode.BUILD
        return modeManager.getCurrentMode(owner, plot)
    }

    /**
     * Return all currently loaded plots.
     * Synchronous — safe to call from the main thread (event handlers).
     * 10.2
     */
    fun getAllLoadedPlots(): List<Plot> = loadedPlots.values.toList()

    /**
     * Synchronous variant of [getAllLoadedPlots] — alias for use in event handlers.
     * Safe to call from the Bukkit main thread without suspending.
     */
    fun getAllLoadedPlotsSync(): List<Plot> = loadedPlots.values.toList()

    /**
     * Synchronous lookup of the plot for [playerId] — safe to call from event handlers
     * on the main thread. Returns null if the player has no loaded plot.
     */
    fun getPlayerPlotSync(playerId: UUID): Plot? {
        val plotId = playerPlotIndex[playerId] ?: return null
        return loadedPlots[plotId]
    }

    /**
     * Find the loaded plot whose main or dev world matches [worldName].
     * Used to resolve which plot a player is standing on (permission checks).
     * Synchronous — safe to call from the main thread.
     */
    fun getPlotByWorld(worldName: String): Plot? =
        loadedPlots.values.firstOrNull { it.mainWorldName == worldName || it.devWorldName == worldName }

    /**
     * Ensure a plot's worlds are loaded, loading them from DB if needed.
     * Returns the (mainWorld, devWorld) pair, or null on failure.
     10.6
     */
    suspend fun ensurePlotLoaded(plotId: UUID): Pair<org.bukkit.World, org.bukkit.World>? {
        if (!loadedPlots.containsKey(plotId)) {
            runCatching { loadPlot(plotId) }.onFailure { return null }
        }
        return worldManager.getLoadedWorlds(plotId)
            ?: runCatching { worldManager.loadPlotWorlds(plotId) }.getOrNull()
    }

    // -------------------------------------------------------------------------
    // World management
    // -------------------------------------------------------------------------

    /** Default max plots per player (can be overridden by permission) */
    companion object {
        const val DEFAULT_MAX_PLOTS = 3
    }

    /**
     * Check if player can create another plot.
     */
    suspend fun canCreatePlot(playerId: UUID): Boolean {
        val count = plotPersistence.countPlotsByOwner(playerId)
        return count < DEFAULT_MAX_PLOTS
    }

    /**
     * Delete a plot completely: unload worlds, delete from DB, remove files.
     */
    suspend fun deletePlot(plotId: UUID, @Suppress("UNUSED_PARAMETER") plugin: org.bukkit.plugin.Plugin) {
        val plot = loadedPlots[plotId] ?: plotPersistence.loadPlot(plotId) ?: return
        
        // Unload the plot if loaded
        if (loadedPlots.containsKey(plotId)) {
            unloadPlot(plotId)
        }
        
        // Delete from database
        plotPersistence.deletePlotAllData(plotId)
        
        // Delete world folders from disk
        val worldContainer = org.bukkit.Bukkit.getWorldContainer()
        val mainWorldFolder = java.io.File(worldContainer, plot.mainWorldName)
        val devWorldFolder = java.io.File(worldContainer, plot.devWorldName)
        
        mainWorldFolder.deleteRecursively()
        devWorldFolder.deleteRecursively()
    }

    /**
     * Get all plots owned by a player (from DB, not just loaded).
     */
    suspend fun getPlotsByOwner(ownerId: UUID): List<Plot> {
        return plotPersistence.getPlotsByOwner(ownerId)
    }

    /**
     * Search plots by name.
     */
    suspend fun searchPlots(query: String, page: Int = 0): List<Plot> {
        return plotPersistence.searchPlots(query, page, 45)
    }
}
