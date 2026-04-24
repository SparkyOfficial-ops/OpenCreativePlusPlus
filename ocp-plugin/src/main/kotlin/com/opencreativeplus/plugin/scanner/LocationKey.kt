package com.opencreativeplus.plugin.scanner

/**
 * Value-based location key for use in visited sets during BFS scanning.
 * Uses world name + block coordinates instead of object identity,
 * ensuring deterministic equality in tests with mock World objects.
 *
 * Feature: ocp-manifest-roadmap, Property 1: Location equality по значению
 */
@JvmInline
value class LocationKey private constructor(private val packed: String) {
    companion object {
        fun of(loc: org.bukkit.Location): LocationKey =
            LocationKey("${loc.world?.name}:${loc.blockX}:${loc.blockY}:${loc.blockZ}")

        fun of(worldName: String, x: Int, y: Int, z: Int): LocationKey =
            LocationKey("$worldName:$x:$y:$z")
    }
}
