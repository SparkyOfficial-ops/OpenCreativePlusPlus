package com.opencreativeplus.api.plot

import java.util.UUID

/**
 * Data class representing a plot with all its properties and metadata.
 */
data class Plot(
    val id: UUID,
    val owner: UUID,
    val name: String,
    val description: String,
    val mainWorldName: String,
    val devWorldName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val settings: PlotSettings,
    val metadata: PlotMetadata,
    val trustedPlayers: Set<UUID> = emptySet()
)
