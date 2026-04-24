package com.opencreativeplus.plugin.visualizer

import com.opencreativeplus.core.watchdog.TPSMonitor
import com.opencreativeplus.plugin.scanner.BlockScanner
import com.opencreativeplus.plugin.scanner.CodeLine
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders particle lines between consecutive code blocks for players in DEV mode.
 *
 * Requirements: 9.1, 9.2, 9.3, 9.4
 */
class DevVisualizer(
    private val plugin: Plugin,
    private val tpsMonitor: TPSMonitor,
    private val blockScannerFactory: (World) -> BlockScanner
) : Listener {

    /** playerId → active particle rendering task */
    private val tasks = ConcurrentHashMap<UUID, BukkitTask>()

    /** playerId → pending rescan debounce task */
    private val rescanTasks = ConcurrentHashMap<UUID, BukkitTask>()

    /** playerId → last known codeLines (for rescan reference) */
    private val playerCodeLines = ConcurrentHashMap<UUID, List<CodeLine>>()

    /**
     * Start rendering particles for [player] based on [codeLines].
     * Cancels any existing task before starting a new one.
     * Req 9.1
     */
    fun startFor(player: Player, codeLines: List<CodeLine>) {
        stopFor(player)
        playerCodeLines[player.uniqueId] = codeLines
        val task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (!player.isOnline) {
                stopFor(player)
                return@Runnable
            }
            renderParticles(player, codeLines)
        }, 0L, 10L) // every 10 ticks = 0.5s
        tasks[player.uniqueId] = task
    }

    /**
     * Stop rendering particles for [player].
     * Req 9.2
     */
    fun stopFor(player: Player) {
        tasks.remove(player.uniqueId)?.cancel()
        rescanTasks.remove(player.uniqueId)?.cancel()
        playerCodeLines.remove(player.uniqueId)
    }

    /**
     * Returns particle density based on current TPS.
     * Normal: 5 particles/block, TPS < 15: 2 particles/block.
     * Req 9.4
     */
    internal fun particleDensity(): Int = if (tpsMonitor.getCurrentTPS() < 15.0) 2 else 5

    /**
     * Render particle lines between consecutive node locations in each CodeLine.
     */
    private fun renderParticles(player: Player, codeLines: List<CodeLine>) {
        val density = particleDensity()
        for (codeLine in codeLines) {
            renderCodeLine(player, codeLine, density)
        }
    }

    private fun renderCodeLine(player: Player, codeLine: CodeLine, density: Int) {
        val nodes = codeLine.nodes
        if (nodes.size < 2) return

        for (i in 0 until nodes.size - 1) {
            val from = nodes[i].location
            val to = nodes[i + 1].location

            val dx = to.x - from.x
            val dy = to.y - from.y
            val dz = to.z - from.z
            val distance = from.distance(to)
            if (distance == 0.0) continue

            val steps = (distance * density).toInt().coerceAtLeast(1)
            for (step in 0..steps) {
                val t = step.toDouble() / steps
                val px = from.x + dx * t
                val py = from.y + dy * t
                val pz = from.z + dz * t
                player.spawnParticle(Particle.END_ROD, px, py + 1.0, pz, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }

        // Recurse into children
        for (child in codeLine.children) {
            renderCodeLine(player, child, density)
        }
    }

    /**
     * When a player in DEV mode places a block, schedule a rescan after 1 second (20 ticks).
     * Req 9.3
     */
    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        if (tasks.containsKey(player.uniqueId)) {
            scheduleRescan(player, event.block.world)
        }
    }

    /**
     * When a player in DEV mode breaks a block, schedule a rescan after 1 second (20 ticks).
     * Req 9.3
     */
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (tasks.containsKey(player.uniqueId)) {
            scheduleRescan(player, event.block.world)
        }
    }

    /**
     * Debounced rescan: cancels any pending rescan and schedules a new one 20 ticks later.
     */
    private fun scheduleRescan(player: Player, world: World) {
        rescanTasks.remove(player.uniqueId)?.cancel()
        val task = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            rescanTasks.remove(player.uniqueId)
            if (!player.isOnline || !tasks.containsKey(player.uniqueId)) return@Runnable
            val newCodeLines = blockScannerFactory(world).scanCodingZone()
            startFor(player, newCodeLines)
        }, 20L) // 1 second = 20 ticks
        rescanTasks[player.uniqueId] = task
    }
}
