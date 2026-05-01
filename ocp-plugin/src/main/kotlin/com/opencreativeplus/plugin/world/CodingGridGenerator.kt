package com.opencreativeplus.plugin.world

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.plugin.Plugin

/**
 * Generates the visual coding grid in the Coding_Zone (dev world).
 *
 * Uses batched synchronous scheduling to avoid RunningOnDifferentThreadException
 * and TPS spikes when called from async coroutines.
 *
 * Grid layout (s 3.1–3.5):
 * - [LEVEL_COUNT] vertical levels, Y = 15, 35, 55, ...
 * - [STRIP_COUNT] coding strips per level, spaced [STRIP_SPACING] blocks apart on Z
 * - Each strip: BLUE_STAINED_GLASS at X=0, then alternating WHITE/GRAY for [STRIP_LENGTH]-1 blocks
 * - BLACK_STAINED_GLASS floor (3 wide) under each strip
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
    }

    /**
     * Schedules full grid generation on the main thread.
     * Safe to call from any coroutine context.
     */
    fun generate(world: World) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            for (levelIndex in 0 until LEVEL_COUNT) {
                val y = 15 + levelIndex * LEVEL_SPACING
                generateLevel(world, y)
            }
        })
    }

    private fun generateLevel(world: World, y: Int) {
        for (stripIndex in 0 until STRIP_COUNT) {
            val z = stripIndex * STRIP_SPACING
            // 3-wide black glass floor for visual separation and fall protection
            for (x in -2..STRIP_LENGTH + 1) {
                world.getBlockAt(x, y - 1, z - 1).type = Material.BLACK_STAINED_GLASS
                world.getBlockAt(x, y - 1, z    ).type = Material.BLACK_STAINED_GLASS
                world.getBlockAt(x, y - 1, z + 1).type = Material.BLACK_STAINED_GLASS
            }
            generateStrip(world, y, z)
        }
    }

    private fun generateStrip(world: World, y: Int, z: Int) {
        for (x in 0 until STRIP_LENGTH) {
            val material = when {
                x == 0      -> Material.BLUE_STAINED_GLASS
                x % 2 != 0  -> Material.WHITE_STAINED_GLASS
                else        -> Material.GRAY_STAINED_GLASS
            }
            world.getBlockAt(x, y, z).type = material
        }
    }
}
