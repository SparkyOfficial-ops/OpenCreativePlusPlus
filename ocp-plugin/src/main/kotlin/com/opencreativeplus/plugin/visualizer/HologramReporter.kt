package com.opencreativeplus.plugin.visualizer

import com.opencreativeplus.plugin.scanner.LocationKey
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.ConcurrentHashMap

/**
 * Displays error holograms (ArmorStand-based) above code blocks on runtime errors.
 * Auto-removes after 30 seconds (600 ticks). Repeated errors on the same block
 * reset the timer and update the message.
 *
 * Requirements: 12.1, 12.2, 12.3, 12.4, 12.6, 12.7
 */
class HologramReporter(private val plugin: Plugin) {

    internal data class HologramEntry(val armorStand: ArmorStand, val task: BukkitTask)

    private val active = ConcurrentHashMap<LocationKey, HologramEntry>()

    fun reportError(location: Location, message: String) {
        val key = LocationKey.of(location)
        // Cancel existing timer and remove old hologram (Req 12.4)
        active.remove(key)?.let { entry ->
            entry.task.cancel()
            entry.armorStand.remove()
        }
        // Truncate message to 40 chars, wrap in §c (Req 12.2)
        val truncated = if (message.length > 40) message.take(40) else message
        val displayText = "§c$truncated"

        // Spawn ArmorStand hologram 2.5 blocks above (Req 12.1)
        val hologramLoc = location.clone().add(0.5, 2.5, 0.5)
        val armorStand = spawnHologram(hologramLoc, displayText)

        // Schedule auto-removal after 600 ticks = 30 seconds (Req 12.3)
        val task = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            armorStand.remove()
            active.remove(key)
        }, 600L)
        active[key] = HologramEntry(armorStand, task)
    }

    /**
     * Show all active hologram ArmorStands to the given player.
     * Called when a player enters dev mode (Req 12.6).
     */
    fun showToPlayer(player: Player) {
        active.values.forEach { entry ->
            player.showEntity(plugin, entry.armorStand)
        }
    }

    /**
     * Hide all active hologram ArmorStands from the given player.
     * Called when a player exits dev mode (Req 12.7).
     */
    fun hideFromPlayer(player: Player) {
        active.values.forEach { entry ->
            player.hideEntity(plugin, entry.armorStand)
        }
    }

    private fun spawnHologram(location: Location, text: String): ArmorStand {
        val world = location.world ?: error("Location has no world")
        @Suppress("DEPRECATION")
        val stand = world.spawnEntity(location, EntityType.ARMOR_STAND) as ArmorStand
        stand.isVisible = false
        stand.isSmall = true
        stand.setGravity(false)
        stand.isCustomNameVisible = true
        stand.customName = text
        stand.isMarker = true
        return stand
    }

    /** Remove all active holograms (e.g. on plugin disable). */
    fun clearAll() {
        active.values.forEach { entry ->
            entry.task.cancel()
            entry.armorStand.remove()
        }
        active.clear()
    }

    /** Returns the number of currently active holograms (for testing). */
    internal fun activeCount(): Int = active.size

    /** Returns the active entry for a location key (for testing). */
    internal fun getEntry(key: LocationKey): HologramEntry? = active[key]
}
