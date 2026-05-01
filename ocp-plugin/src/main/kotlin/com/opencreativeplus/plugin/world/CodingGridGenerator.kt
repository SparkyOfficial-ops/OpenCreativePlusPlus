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
     * Generates one level per tick (every 2 ticks).
     */
    fun generate(world: World) {
        // Set world border for the dev world
        world.worldBorder.apply {
            center = world.getBlockAt(STRIP_LENGTH / 2, 64, (STRIP_COUNT * STRIP_SPACING) / 2).location
            size = DEV_BORDER_SIZE
            warningDistance = 5
            warningTime = 0
        }

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

        // Fill the entire floor with WHITE_STAINED_GLASS (between and under all strips)
        for (x in -2..STRIP_LENGTH + 1) {
            for (z in -2..totalWidth + 1) {
                world.getBlockAt(x, y - 1, z).type = Material.WHITE_STAINED_GLASS
            }
        }

        // Place the coding strips on top of the floor
        for (stripIndex in 0 until STRIP_COUNT) {
            val z = stripIndex * STRIP_SPACING
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
