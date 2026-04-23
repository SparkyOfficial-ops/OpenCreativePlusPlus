package com.opencreativeplus.plugin.scanner

import org.bukkit.Location
import org.bukkit.Material

/**
 * Represents a single block found above the glass strip during scanning.
 * 4.4, 40.2
 *
 * [nodeId] is populated from the block's PDC key `ocp:action_id` when present;
 * otherwise it is resolved via [Material]-based registry lookup.
 * Requirements: 4.1, 4.2, 4.3, 4.4
 */
data class ScannedNode(
    val blockType: Material,
    val location: Location,
    val parameters: Map<String, Any>,
    val nodeId: String? = null   // NEW: populated from ocp:action_id PDC
)
