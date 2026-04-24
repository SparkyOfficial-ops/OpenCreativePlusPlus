package com.opencreativeplus.plugin.visualizer

import com.opencreativeplus.plugin.scanner.LocationKey
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.EntityType
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.ConcurrentHashMap

/**
 * Displays error holograms (ArmorStand-based) above code blocks on runtime errors.
 * Auto-removes after 10 seconds (200 ticks). Repeated errors on the same block
 * reset the timer and update the message.
 *
 * Requirements: 10.1, 10.2, 10.3, 10.4
 */
class HologramReporter(private val plugin: Plugin) {

    internal data class HologramEntry(val armorStand: ArmorStand, val task: BukkitTask)

    private val active = ConcurrentHashMap<LocationKey, HologramEntry>()

    fun reportError(location: Location, message: String) {
        val key = LocationKey.of(location)
        // Cancel existing timer and remove old hologram (Req 10.4)
        active.remove(key)?.let { entry ->
            entry.task.cancel()
            entry.armorStand.remove()
        }
        // Spawn ArmorStand hologram 2.5 blocks above (Req 10.1, 10.2)
        val hologramLoc = location.clone().add(0.5, 2.5, 0.5)
        val armorStand = spawnHologram(hologramLoc, "§cОшибка: $message")
        // Schedule auto-removal after 200 ticks = 10 seconds (Req 10.3)
        val task = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            armorStand.remove()
            active.remove(key)
        }, 200L)
        active[key] = HologramEntry(armorStand, task)
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
