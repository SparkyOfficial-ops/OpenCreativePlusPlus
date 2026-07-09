package com.opencreativeplus.plugin.world

import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.World
import org.bukkit.WorldCreator
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
    fun createPlotWorldsSync(plotId: UUID): Pair<World, World> {
        val mainWorld = createWorld(plotId.toString(), isDevWorld = false)
        val devWorld = createWorld("${plotId}_dev", isDevWorld = true)
        configureMainWorld(mainWorld)
        configureDevWorld(devWorld)
        loadedWorlds[plotId] = Pair(mainWorld, devWorld)
        return Pair(mainWorld, devWorld)
    }

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

    private fun createWorld(name: String, isDevWorld: Boolean): World {
        // Pre-create the world folder with a level.dat that has SpawnX/Y/Z = 0/64/0.
        // This prevents Bukkit from calling setInitialSpawn → getOverworldRespawnPos,
        // which synchronously generates chunks on the main thread causing 10-40s Watchdog freezes.
        val worldFolder = java.io.File(Bukkit.getWorldContainer(), name)
        if (!worldFolder.exists()) {
            prepareLevelDat(worldFolder)
        }

        val creator = WorldCreator(name)
            .generateStructures(false)
            .environment(World.Environment.NORMAL)
            .generator(VoidGenerator())

        val world = Bukkit.createWorld(creator)
            ?: error("Failed to create world: $name")

        world.setKeepSpawnInMemory(false)
        world.setSpawnLocation(0, 64, 0)
        return world
    }

    /**
     * Writes a minimal level.dat NBT file into [worldFolder] that pre-sets the spawn
     * coordinates to (0, 64, 0).  When Bukkit loads this folder it reads the existing
     * SpawnX/Y/Z values from level.dat and skips the expensive `setInitialSpawn` scan.
     *
     * The NBT is written using the vanilla-compatible format that Paper 1.20.1 accepts.
     * If anything fails (e.g. NBT libraries not accessible) we log and continue — Bukkit
     * will fall back to the slow path but won't crash.
     */
    private fun prepareLevelDat(worldFolder: java.io.File) {
        try {
            worldFolder.mkdirs()
            val levelDat = java.io.File(worldFolder, "level.dat")
            if (levelDat.exists()) return

            // Write a minimal compound NBT: {Data:{SpawnX:0,SpawnY:64,SpawnZ:0}}
            // Using raw NBT bytes — avoids depending on server-internal NMS classes.
            // Gzip-compressed NBT compound as Paper/Vanilla expects.
            val nbt = buildMinimalLevelDat()
            java.io.FileOutputStream(levelDat).use { fos ->
                java.util.zip.GZIPOutputStream(fos).use { gzip ->
                    gzip.write(nbt)
                }
            }
        } catch (e: Exception) {
            // Non-fatal: Bukkit will create level.dat itself (with slow spawn scan)
            plugin.logger.warning("[OCP] Could not pre-create level.dat for $worldFolder: ${e.message}")
        }
    }

    /**
     * Returns a minimal gzip-compatible NBT byte array with SpawnX=0, SpawnY=64, SpawnZ=0.
     * Format: TAG_Compound("") { TAG_Compound("Data") { TAG_Int("SpawnX",0), TAG_Int("SpawnY",64), TAG_Int("SpawnZ",0) } }
     */
    private fun buildMinimalLevelDat(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(out)
        // TAG_Compound (id=10), name=""
        dos.writeByte(10); dos.writeShort(0)
        // TAG_Compound (id=10), name="Data"
        dos.writeByte(10); dos.writeShort(4); dos.write("Data".toByteArray(Charsets.UTF_8))
        // TAG_Int SpawnX = 0
        dos.writeByte(3); dos.writeShort(6); dos.write("SpawnX".toByteArray(Charsets.UTF_8)); dos.writeInt(0)
        // TAG_Int SpawnY = 64
        dos.writeByte(3); dos.writeShort(6); dos.write("SpawnY".toByteArray(Charsets.UTF_8)); dos.writeInt(64)
        // TAG_Int SpawnZ = 0
        dos.writeByte(3); dos.writeShort(6); dos.write("SpawnZ".toByteArray(Charsets.UTF_8)); dos.writeInt(0)
        // TAG_Int DataVersion (required by Paper 1.20.1 to not re-init world)
        dos.writeByte(3); dos.writeShort(11); dos.write("DataVersion".toByteArray(Charsets.UTF_8)); dos.writeInt(3465)
        // End of "Data" compound
        dos.writeByte(0)
        // End of root compound
        dos.writeByte(0)
        dos.flush()
        return out.toByteArray()
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
