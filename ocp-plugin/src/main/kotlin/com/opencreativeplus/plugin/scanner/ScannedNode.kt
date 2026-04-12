package com.opencreativeplus.plugin.scanner

import org.bukkit.Location
import org.bukkit.Material

/
 * Represents a single block found above the glass strip during scanning.
 4.4, 40.2
 */
data class ScannedNode(
    val blockType: Material,
    val location: Location,
    val parameters: Map<String, Any>
)
