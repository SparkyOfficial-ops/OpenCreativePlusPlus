package com.opencreativeplus.api.plot

import java.util.UUID

/**
 * Data class for plot metadata including tags, ratings, and player count.
 */
data class PlotMetadata(
    val tags: List<String> = emptyList(),
    val rating: Int = 0,
    val ratedBy: Set<UUID> = emptySet(),
    val currentPlayers: Int = 0
)
