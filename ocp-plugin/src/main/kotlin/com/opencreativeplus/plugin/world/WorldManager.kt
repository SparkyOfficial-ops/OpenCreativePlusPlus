package com.opencreativeplus.plugin.world

import com.opencreativeplus.core.world.PlotTemplate
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.Material
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
     * Create both worlds for a new plot synchronously.
     * MUST be called from the Bukkit main thread.
     */
    fun createPlotWorldsSync(plotId: UUID, template: PlotTemplate = PlotTemplate.VOID): Pair<World, World> {
        val mainWorld = createWorld(plotId.toString(), isDevWorld = false, template = template)
        // Dev world is always void — coding grid is placed on top
        val devWorld = createWorld("${plotId}_dev", isDevWorld = true, template = PlotTemplate.VOID)
        configureMainWorld(mainWorld)
        configureDevWorld(devWorld)
        loadedWorlds[plotId] = Pair(mainWorld, devWorld)
        return Pair(mainWorld, devWorld)
    }

    /**
     * Create both worlds for a new plot.
     1.1, 1.2, 1.3, 35.1, 35.2, 35.3, 35.4
     */
    suspend fun createPlotWorlds(plotId: UUID, template: PlotTemplate = PlotTemplate.VOID): Pair<World, World> =
        suspendCancellableCoroutine { cont ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                try {
                    val mainWorld = createWorld(plotId.toString(), isDevWorld = false, template = template)
                    val devWorld = createWorld("${plotId}_dev", isDevWorld = true, template = PlotTemplate.VOID)
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
     * Does NOT regenerate the coding grid — grid is generated only on creation.
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
                    // For dev world on load: configure settings but DON'T regenerate the grid
                    // (grid already exists from createPlotWorlds — regenerating it every load is the #1 cause of slowness)
                    val devWorld = Bukkit.getWorld(devWorldName)
                        ?: createWorld(devWorldName, isDevWorld = true).also { configureDevWorldSettings(it) }
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

    private fun createWorld(name: String, isDevWorld: Boolean, template: PlotTemplate = PlotTemplate.VOID): World {
        val creator = WorldCreator(name)
            .environment(World.Environment.NORMAL)

        // Choose generator based on template. Dev worlds are always void.
        when {
            isDevWorld || template == PlotTemplate.VOID -> {
                creator.generateStructures(false)
                creator.generator(VoidGenerator())
            }
            template == PlotTemplate.FLAT -> {
                creator.generateStructures(false)
                creator.type(WorldType.FLAT)
            }
            template == PlotTemplate.SURVIVAL -> {
                creator.generateStructures(true)
                creator.type(WorldType.NORMAL)
            }
        }

        val world = Bukkit.createWorld(creator)
            ?: error("Failed to create world: $name")

        world.setKeepSpawnInMemory(false)
        val spawnY = if (isDevWorld) 16 else 64
        world.setSpawnLocation(0, spawnY, 0)

        // For void worlds, place a 3x3 stone platform at spawn to prevent the player
        // from falling into the void. Without any blocks, Paper's PlayerRespawnLogic
        // scans thousands of chunks on the main thread, causing 45-second freezes.
        if (!isDevWorld && template == PlotTemplate.VOID) {
            for (x in -1..1) {
                for (z in -1..1) {
                    world.getBlockAt(x, spawnY - 1, z).type = Material.SMOOTH_STONE
                }
            }
        }

        return world
    }

    /**
     * Configure the main plot world: difficulty, PvP off, mob spawning off, world border.
     1.1, 35.1, 35.2, 35.3, 35.4
     */
    private fun configureMainWorld(world: World) {
        world.setKeepSpawnInMemory(false)
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
     * Called ONLY during plot creation (createPlotWorlds).
     1.4, 3.1
     */
    private fun configureDevWorld(world: World) {
        configureDevWorldSettings(world)
        // Generate the coding grid (req 3.1) — only on first creation
        codingGridGenerator.generate(world)
    }

    /**
     * Apply dev world game rules and settings WITHOUT regenerating the grid.
     * Called during loadPlotWorlds to avoid regenerating blocks every server restart.
     */
    private fun configureDevWorldSettings(world: World) {
        world.setKeepSpawnInMemory(false)
        world.difficulty = Difficulty.PEACEFUL
        world.pvp = false
        world.setSpawnFlags(false, false)
        world.setGameRuleValue("doMobSpawning", "false")
        world.setGameRuleValue("doDaylightCycle", "false")
        world.setGameRuleValue("doWeatherCycle", "false")
        world.setGameRuleValue("fallDamage", "false")
        world.setSpawnLocation(0, 16, 0)
    }
}

/**
 * Minimal void chunk generator — produces completely empty chunks.
 * Replaces the legacy generatorSettings JSON that broke on Paper 1.18+
 * ("No key layers in MapLike[{}]" / "Unknown biome" errors).
 */
class VoidGenerator : org.bukkit.generator.ChunkGenerator() {
    override fun generateNoise(
        worldInfo: org.bukkit.generator.WorldInfo,
        random: java.util.Random,
        chunkX: Int,
        chunkZ: Int,
        chunkData: ChunkData
    ) {
        // Leave chunk empty — no blocks placed
    }
}
