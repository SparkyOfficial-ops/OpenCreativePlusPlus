package com.opencreativeplus.plugin.world

import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages plot world creation, loading, and unloading.
 *
 * Uses standard Bukkit world management with a hook for SlimeWorldManager
 * when available (s 27.1, 27.2, 27.3).
 *
 1.1, 1.2, 1.3, 1.5, 27.1, 27.2, 27.3, 27.4, 27.5, 35.1, 35.2, 35.3, 35.4
 */
class WorldManager(
    private val codingGridGenerator: CodingGridGenerator = CodingGridGenerator(),
    private val plugin: Plugin = Bukkit.getPluginManager().getPlugin("OpenCreativePlus")
        ?: error("OpenCreativePlus plugin not found")
) {
    /** plotId → (mainWorld, devWorld) */
    private val loadedWorlds = ConcurrentHashMap<UUID, Pair<World, World>>()

    /**
     * Create both worlds for a new plot.
     1.1, 1.2, 1.3, 35.1, 35.2, 35.3, 35.4
     */
    suspend fun createPlotWorlds(plotId: UUID): Pair<World, World> =
        suspendCancellableCoroutine { cont ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                try {
                    val mainWorld = createWorld(plotId.toString(), isDevWorld = false)
                    val devWorld = createWorld("${plotId}_dev", isDevWorld = true)
                    configureMainWorld(mainWorld)
                    configureDevWorld(devWorld)
                    loadedWorlds[plotId] = Pair(mainWorld, devWorld)
                    cont.resume(Pair(mainWorld, devWorld))
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            })
        }

    /**
     * Load existing worlds for a plot (fallback to creation if not found).
     1.5, 27.2, 27.4
     */
    suspend fun loadPlotWorlds(plotId: UUID): Pair<World, World> =
        suspendCancellableCoroutine { cont ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                try {
                    val mainWorldName = plotId.toString()
                    val devWorldName = "${plotId}_dev"
                    val mainWorld = Bukkit.getWorld(mainWorldName)
                        ?: createWorld(mainWorldName, isDevWorld = false).also { configureMainWorld(it) }
                    val devWorld = Bukkit.getWorld(devWorldName)
                        ?: createWorld(devWorldName, isDevWorld = true).also { configureDevWorld(it) }
                    loadedWorlds[plotId] = Pair(mainWorld, devWorld)
                    cont.resume(Pair(mainWorld, devWorld))
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            })
        }

    /**
     * Unload both worlds for a plot, saving state.
     27.5
     */
    suspend fun unloadPlotWorlds(plotId: UUID) =
        suspendCancellableCoroutine<Unit> { cont ->
            val pair = loadedWorlds.remove(plotId)
            if (pair == null) {
                cont.resume(Unit)
                return@suspendCancellableCoroutine
            }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                try {
                    Bukkit.unloadWorld(pair.first, true)
                    Bukkit.unloadWorld(pair.second, true)
                    cont.resume(Unit)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            })
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
            .generateStructures(false)
        if (isDevWorld) {
            // Flat void — no terrain, only our coding grid
            creator.type(WorldType.FLAT)
                .generatorSettings("{\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"structures\":{\"structures\":{}}}")
        } else {
            creator.type(WorldType.FLAT)
        }
        return Bukkit.createWorld(creator)
            ?: error("Failed to create world: $name")
    }

    /**
     * Configure the main plot world: difficulty, PvP off, mob spawning off, world border.
     1.1, 35.1, 35.2, 35.3, 35.4
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
     1.4, 3.1
     */
    private fun configureDevWorld(world: World) {
        world.difficulty = Difficulty.PEACEFUL
        world.pvp = false
        world.setSpawnFlags(false, false)
        world.setGameRuleValue("doMobSpawning", "false")
        world.setGameRuleValue("doDaylightCycle", "false")
        world.setGameRuleValue("doWeatherCycle", "false")
        world.setGameRuleValue("fallDamage", "false")

        // Generate the coding grid (req 3.1)
        codingGridGenerator.generate(world)

        // Spawn player above the first strip at level Y=15 (first level)
        world.setSpawnLocation(0, 16, 0)
    }
}
