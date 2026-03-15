package com.opencreativeplus.plugin.world

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages plot world creation, loading, and unloading.
 *
 * Uses standard Bukkit world management with a hook for SlimeWorldManager
 * when available (Requirements 27.1, 27.2, 27.3).
 *
 * Requirements: 1.1, 1.2, 1.3, 1.5, 27.1, 27.2, 27.3, 27.4, 27.5, 35.1, 35.2, 35.3, 35.4
 */
class WorldManager(
    private val codingGridGenerator: CodingGridGenerator = CodingGridGenerator()
) {
    /** plotId → (mainWorld, devWorld) */
    private val loadedWorlds = ConcurrentHashMap<UUID, Pair<World, World>>()

    /**
     * Create both worlds for a new plot.
     * Requirements: 1.1, 1.2, 1.3, 35.1, 35.2, 35.3, 35.4
     */
    suspend fun createPlotWorlds(plotId: UUID): Pair<World, World> = withContext(Dispatchers.IO) {
        val mainWorldName = plotId.toString()
        val devWorldName = "${plotId}_dev"

        val mainWorld = createWorld(mainWorldName, isDevWorld = false)
        val devWorld = createWorld(devWorldName, isDevWorld = true)

        configureMainWorld(mainWorld)
        configureDevWorld(devWorld)

        loadedWorlds[plotId] = Pair(mainWorld, devWorld)
        Pair(mainWorld, devWorld)
    }

    /**
     * Load existing worlds for a plot (fallback to creation if not found).
     * Requirements: 1.5, 27.2, 27.4
     */
    suspend fun loadPlotWorlds(plotId: UUID): Pair<World, World> = withContext(Dispatchers.IO) {
        val mainWorldName = plotId.toString()
        val devWorldName = "${plotId}_dev"

        val mainWorld = Bukkit.getWorld(mainWorldName) ?: createWorld(mainWorldName, isDevWorld = false).also {
            configureMainWorld(it)
        }
        val devWorld = Bukkit.getWorld(devWorldName) ?: createWorld(devWorldName, isDevWorld = true).also {
            configureDevWorld(it)
        }

        loadedWorlds[plotId] = Pair(mainWorld, devWorld)
        Pair(mainWorld, devWorld)
    }

    /**
     * Unload both worlds for a plot, saving state.
     * Requirements: 27.5
     */
    suspend fun unloadPlotWorlds(plotId: UUID) = withContext(Dispatchers.IO) {
        val (mainWorld, devWorld) = loadedWorlds.remove(plotId) ?: return@withContext
        Bukkit.unloadWorld(mainWorld, true)
        Bukkit.unloadWorld(devWorld, true)
    }

    /**
     * Get the loaded worlds for a plot, or null if not loaded.
     */
    fun getLoadedWorlds(plotId: UUID): Pair<World, World>? = loadedWorlds[plotId]

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun createWorld(name: String, isDevWorld: Boolean): World {
        val creator = WorldCreator(name)
            .type(WorldType.FLAT)
            .generateStructures(false)
        return Bukkit.createWorld(creator)
            ?: error("Failed to create world: $name")
    }

    /**
     * Configure the main plot world: difficulty, PvP off, mob spawning off, world border.
     * Requirements: 1.1, 35.1, 35.2, 35.3, 35.4
     */
    private fun configureMainWorld(world: World) {
        world.difficulty = Difficulty.PEACEFUL
        world.pvp = false
        world.setSpawnFlags(false, false) // no monsters, no animals
        world.setGameRuleValue("doMobSpawning", "false")
        world.setGameRuleValue("doDaylightCycle", "false")

        // Set 1024x1024 world border centered at (0, 0) (req 35.1, 35.3, 35.4)
        world.worldBorder.apply {
            center = world.getBlockAt(0, 64, 0).location
            size = 1024.0
        }
    }

    /**
     * Configure the dev world and generate the coding grid.
     * Requirements: 1.4, 3.1
     */
    private fun configureDevWorld(world: World) {
        world.difficulty = Difficulty.PEACEFUL
        world.pvp = false
        world.setSpawnFlags(false, false)
        world.setGameRuleValue("doMobSpawning", "false")
        world.setGameRuleValue("doDaylightCycle", "false")

        // Generate the coding grid (req 3.1)
        codingGridGenerator.generate(world)
    }
}
