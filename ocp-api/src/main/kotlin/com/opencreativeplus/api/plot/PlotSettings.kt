package com.opencreativeplus.api.plot

/**
 * Data class for plot configuration settings.
 */
data class PlotSettings(
    val biome: String = "PLAINS",
    val timeOfDay: Long = 6000,
    val pvpEnabled: Boolean = false,
    val mobSpawningEnabled: Boolean = false,
    val worldBorderSize: Int = 1024,
    val isPublic: Boolean = true,
    val allowInteractions: Boolean = true,
    val allowExplosions: Boolean = false,
    val allowFire: Boolean = false,
    val allowCodingAccess: Boolean = false  // trusted players can enter /dev mode
)
