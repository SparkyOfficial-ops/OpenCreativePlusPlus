package com.opencreativeplus.plugin.visualizer

import com.opencreativeplus.plugin.scanner.DataContainer
import com.opencreativeplus.plugin.scanner.LocationKey
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Displays error holograms (ArmorStand-based) above code blocks on runtime errors.
 * Auto-removes after 30 seconds (600 ticks). Repeated errors on the same block
 * reset the timer and update the message.
 *
 * Also displays per-player argument holograms above ParamChests, showing the
 * type and value of each DataContainer argument (Requirements 9.1–9.5).
 *
 * Requirements: 12.1, 12.2, 12.3, 12.4, 12.6, 12.7, 9.1, 9.2, 9.3, 9.4, 9.5
 */
class HologramReporter(private val plugin: Plugin) {

    // -------------------------------------------------------------------------
    // Error holograms (existing functionality)
    // -------------------------------------------------------------------------

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
     * Show all active error hologram ArmorStands to the given player.
     * Called when a player enters dev mode (Req 12.6).
     */
    fun showToPlayer(player: Player) {
        active.values.forEach { entry ->
            player.showEntity(plugin, entry.armorStand)
        }
    }

    /**
     * Hide all active error hologram ArmorStands from the given player.
     * Called when a player exits dev mode (Req 12.7).
     */
    fun hideFromPlayer(player: Player) {
        active.values.forEach { entry ->
            player.hideEntity(plugin, entry.armorStand)
        }
    }

    // -------------------------------------------------------------------------
    // Arg holograms (Requirements 9.1–9.5)
    // -------------------------------------------------------------------------

    /**
     * Holds the list of ArmorStands spawned for a single chest location for a single player.
     * Multiple stands are used — one per argument line — stacked vertically.
     *
     * Requirements: 9.1, 9.2
     */
    internal data class ArgHologramEntry(val armorStands: List<ArmorStand>)

    /**
     * Per-player, per-chest-location arg hologram entries.
     * Outer key: player UUID. Inner key: chest LocationKey.
     *
     * Requirements: 9.5
     */
    private val argHolograms = ConcurrentHashMap<UUID, ConcurrentHashMap<LocationKey, ArgHologramEntry>>()

    /**
     * Show argument holograms for [player] above [chestLoc], one line per [DataContainer] in [args].
     *
     * Line format: "§e[i]: §f<TypeLabel>: §7<Value>" (Req 9.2)
     * Lines are stacked vertically starting at chestLoc + (0.5, 1.5, 0.5), each 0.3 blocks higher.
     * Each ArmorStand is hidden from all players then shown only to [player] (Req 9.5).
     *
     * Requirements: 9.1, 9.2, 9.5
     */
    fun showArgHolograms(player: Player, chestLoc: Location, args: List<DataContainer>) {
        // Remove any existing arg holograms at this location for this player first
        hideArgHolograms(player, chestLoc)

        val stands = mutableListOf<ArmorStand>()
        args.forEachIndexed { lineIndex, dc ->
            val line = "§e[${lineIndex}]: §f${dc.typeLabel()}: §7${dc.displayValue()}"
            val standLoc = chestLoc.clone().add(0.5, 1.5 + lineIndex * 0.3, 0.5)
            val stand = spawnHologram(standLoc, line)

            // Hide from all players on the server, then show only to the target player (Req 9.5)
            standLoc.world?.players?.forEach { p -> p.hideEntity(plugin, stand) }
            player.showEntity(plugin, stand)

            stands.add(stand)
        }

        val playerMap = argHolograms.getOrPut(player.uniqueId) { ConcurrentHashMap() }
        playerMap[LocationKey.of(chestLoc)] = ArgHologramEntry(stands)
    }

    /**
     * Remove arg holograms for [player] at a specific [chestLoc].
     * Called internally before re-spawning updated holograms (Req 9.3).
     */
    private fun hideArgHolograms(player: Player, chestLoc: Location) {
        val playerMap = argHolograms[player.uniqueId] ?: return
        val key = LocationKey.of(chestLoc)
        playerMap.remove(key)?.armorStands?.forEach { stand -> stand.remove() }
    }

    /**
     * Remove ALL arg holograms for [player] (called on EDIT/DEV mode exit, Req 9.4).
     *
     * Requirements: 9.4
     */
    fun hideArgHolograms(player: Player) {
        val playerMap = argHolograms.remove(player.uniqueId) ?: return
        playerMap.values.forEach { entry ->
            entry.armorStands.forEach { stand -> stand.remove() }
        }
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun spawnHologram(location: Location, text: String): ArmorStand {
        val world = location.world ?: error("Location has no world")
        val stand = world.spawnEntity(location, EntityType.ARMOR_STAND) as ArmorStand
        stand.isVisible = false
        stand.isSmall = true
        stand.setGravity(false)
        stand.isCustomNameVisible = true
        stand.customName = text
        stand.isMarker = true
        return stand
    }

    /** Remove all active holograms (error and arg) — e.g. on plugin disable. */
    fun clearAll() {
        active.values.forEach { entry ->
            entry.task.cancel()
            entry.armorStand.remove()
        }
        active.clear()

        argHolograms.values.forEach { playerMap ->
            playerMap.values.forEach { entry ->
                entry.armorStands.forEach { stand -> stand.remove() }
            }
        }
        argHolograms.clear()
    }

    /** Returns the number of currently active error holograms (for testing). */
    internal fun activeCount(): Int = active.size

    /** Returns the active error entry for a location key (for testing). */
    internal fun getEntry(key: LocationKey): HologramEntry? = active[key]

    /** Returns the arg hologram entry for a player + chest location (for testing). */
    internal fun getArgEntry(playerId: UUID, chestKey: LocationKey): ArgHologramEntry? =
        argHolograms[playerId]?.get(chestKey)

    /** Returns the number of arg hologram entries for a player (for testing). */
    internal fun argHologramCount(playerId: UUID): Int =
        argHolograms[playerId]?.size ?: 0
}
