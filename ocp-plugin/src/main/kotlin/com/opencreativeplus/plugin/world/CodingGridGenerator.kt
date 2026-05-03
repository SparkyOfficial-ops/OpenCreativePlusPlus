package com.opencreativeplus.plugin.world

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.plugin.Plugin

/**
 * Generates the visual coding grid in the Coding_Zone (dev world).
 *
 * Uses batched synchronous scheduling to avoid RunningOnDifferentThreadException
 * and TPS spikes when called from async coroutines.
 *
 * Grid layout:
 * - [LEVEL_COUNT] vertical levels, Y = 15, 35, 55, ...
 * - [STRIP_COUNT] coding strips per level, spaced [STRIP_SPACING] blocks apart on Z
 * - Each strip: BLUE_STAINED_GLASS at X=0, then alternating WHITE/GRAY for [STRIP_LENGTH]-1 blocks
 * - WHITE_STAINED_GLASS floor fills the entire area between strips (no black glass)
 */
class CodingGridGenerator(
    private val plugin: Plugin = Bukkit.getPluginManager().getPlugin("OpenCreativePlus")
        ?: error("OpenCreativePlus plugin not found")
) {
    companion object {
        const val STRIP_LENGTH  = 100
        const val STRIP_COUNT   = 40
        const val LEVEL_SPACING = 20
        const val STRIP_SPACING = 4
        const val LEVEL_COUNT   = 8

        // World border size for the dev world (full area + margin)
        const val DEV_BORDER_SIZE = 512.0
    }

    /**
     * Schedules full grid generation using batched ticking to avoid TPS spikes.
     *
     * Phase 1: Force-load all required chunks asynchronously (non-blocking).
     * Phase 2: Once chunks are loaded, generate one level per 2 ticks.
     *
     * This prevents the "server has not responded" freeze caused by getBlockAt()
     * forcing synchronous chunk loading on the main thread.
     */
    fun generate(world: World) {
        world.worldBorder.apply {
            center = world.getBlockAt(STRIP_LENGTH / 2, 64, (STRIP_COUNT * STRIP_SPACING) / 2).location
            size = DEV_BORDER_SIZE
            warningDistance = 5
            warningTime = 0
        }

        // Calculate which chunks we need
        val totalWidth = STRIP_COUNT * STRIP_SPACING
        val chunkXMin = (-2) shr 4
        val chunkXMax = (STRIP_LENGTH + 2) shr 4
        val chunkZMin = (-2) shr 4
        val chunkZMax = (totalWidth + 2) shr 4

        val chunksToLoad = mutableListOf<Pair<Int, Int>>()
        for (cx in chunkXMin..chunkXMax) {
            for (cz in chunkZMin..chunkZMax) {
                chunksToLoad.add(cx to cz)
            }
        }

        // Load all chunks first, then start generation
        var loadedCount = 0
        val totalChunks = chunksToLoad.size

        for ((cx, cz) in chunksToLoad) {
            world.getChunkAtAsync(cx, cz).thenRun {
                loadedCount++
                if (loadedCount >= totalChunks) {
                    // All chunks loaded — start batched generation on main thread
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        startBatchedGeneration(world)
                    })
                }
            }
        }
    }

    private fun startBatchedGeneration(world: World) {
        object : org.bukkit.scheduler.BukkitRunnable() {
            var currentLevel = 0

            override fun run() {
                if (currentLevel >= LEVEL_COUNT) {
                    cancel()
                    return
                }
                val y = 15 + currentLevel * LEVEL_SPACING
                generateLevel(world, y)
                currentLevel++
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    private fun generateLevel(world: World, y: Int) {
        val totalWidth = STRIP_COUNT * STRIP_SPACING  // total Z span of all strips

        // Fill the floor with WHITE_STAINED_GLASS only between strips (not under strip rows).
        // applyPhysics=false prevents neighbor block updates that would try to load
        // ungenerated chunks and freeze the server thread.
        val whiteGlass = org.bukkit.Material.WHITE_STAINED_GLASS.createBlockData()
        for (x in -2..STRIP_LENGTH + 1) {
            for (z in -2..totalWidth + 1) {
                // Only place floor between strips, not under them
                if (z < 0 || z >= totalWidth || z % STRIP_SPACING != 0) {
                    world.getBlockAt(x, y - 1, z).setBlockData(whiteGlass, false)
                }
            }
        }

        // Place the coding strips on top of the floor
        for (stripIndex in 0 until STRIP_COUNT) {
            val z = stripIndex * STRIP_SPACING
            generateStrip(world, y, z)
        }
    }

    private fun generateStrip(world: World, y: Int, z: Int) {
        val blueGlass  = org.bukkit.Material.BLUE_STAINED_GLASS.createBlockData()
        val whiteGlass = org.bukkit.Material.WHITE_STAINED_GLASS.createBlockData()
        val grayGlass  = org.bukkit.Material.GRAY_STAINED_GLASS.createBlockData()
        for (x in 0 until STRIP_LENGTH) {
            val data = when {
                x == 0     -> blueGlass
                x % 2 != 0 -> whiteGlass
                else       -> grayGlass
            }
            world.getBlockAt(x, y, z).setBlockData(data, false)
        }
    }
}
