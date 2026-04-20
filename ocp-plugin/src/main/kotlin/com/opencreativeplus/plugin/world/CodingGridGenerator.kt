package com.opencreativeplus.plugin.world

import org.bukkit.Material
import org.bukkit.World

/**
 * Generates the visual coding grid in the Coding_Zone (dev world).
 *
 * Grid layout (s 3.1, 3.2, 3.3, 3.4, 3.5):
 * - Multiple vertical levels spaced 5 blocks apart (Y = 5, 10, 15, ...)
 * - At each level, 32 coding strips spaced 2 blocks apart along Z axis
 * - Each strip starts with a BLUE_STAINED_GLASS block at X=0
 * - Followed by alternating WHITE and GRAY stained glass for 63 more blocks
 * - Total strip length: 64 blocks
 */
class CodingGridGenerator {

    companion object {
        const val STRIP_LENGTH = 64
        const val STRIP_COUNT = 32
        const val LEVEL_SPACING = 15 // 15 blocks between levels — plenty of headroom
        const val STRIP_SPACING = 4  // 4 blocks between strips — easier to navigate
        const val LEVEL_COUNT = 10   // 10 levels: Y=15,30,...,150
    }

    /**
     * Generate the full coding grid in [world].
     3.1, 3.2, 3.3, 3.4, 3.5
     */
    fun generate(world: World) {
        for (levelIndex in 0 until LEVEL_COUNT) {
            val y = (levelIndex + 1) * LEVEL_SPACING
            generateLevel(world, y)
        }
    }

    /**
     * Generate all strips at a single Y level.
     */
    private fun generateLevel(world: World, y: Int) {
        for (stripIndex in 0 until STRIP_COUNT) {
            val z = stripIndex * STRIP_SPACING
            // White glass floor under each strip so you don't fall into the void
            for (x in -2 until STRIP_LENGTH + 2) {
                world.getBlockAt(x, y - 1, z).type = Material.WHITE_STAINED_GLASS
            }
            generateStrip(world, y, z)
        }
    }

    /**
     * Generate a single coding strip starting at (0, y, z).
     * First block is BLUE_STAINED_GLASS, rest alternate WHITE/GRAY.
     3.2, 3.3
     */
    private fun generateStrip(world: World, y: Int, z: Int) {
        for (x in 0 until STRIP_LENGTH) {
            val material = when (x) {
                0 -> Material.BLUE_STAINED_GLASS
                else -> if (x % 2 == 1) Material.WHITE_STAINED_GLASS else Material.GRAY_STAINED_GLASS
            }
            world.getBlockAt(x, y, z).type = material
        }
    }
}
