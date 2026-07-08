package com.opencreativeplus.plugin.world

import com.opencreativeplus.core.world.PlotTemplate
import com.opencreativeplus.core.world.WorldOperations
import org.apache.commons.io.FileUtils
import org.bukkit.Bukkit
import org.bukkit.WorldCreator
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.UUID

/**
 * Bukkit implementation of [WorldOperations] for the core WorldManager.
 * Physically copies a template world directory and loads it via Bukkit.
 *
 * Requirements: 6.1–6.9
 */
class BukkitWorldOperations(
    private val plugin: Plugin,
    private val pluginWorldManager: WorldManager
) : WorldOperations {

    override fun copyTemplate(
        template: PlotTemplate,
        plotId: UUID,
        onSuccess: (worldName: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val templateDir = File(plugin.dataFolder, "templates/${template.templateName}")
        val destDir = File(plugin.server.worldContainer, plotId.toString())
        val worldName = plotId.toString()

        // Req 6.3: idempotency — if destination already exists, just load the world
        if (destDir.exists()) {
            loadWorldOnMainThread(worldName, onSuccess, onError)
            return
        }

        // Req 6.2: template not found
        if (!templateDir.exists()) {
            onError(Exception("Template '${template.templateName}' not found at ${templateDir.absolutePath}"))
            return
        }

        // Req 6.4: async file copy
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                // Req 6.8: use FileUtils.copyDirectory from commons-io
                FileUtils.copyDirectory(templateDir, destDir)

                // Delete uid.dat so Bukkit generates a fresh world UUID for this plot.
                // If uid.dat is copied from the template, all plots share the same Bukkit
                // world UUID, causing teleportation, entity lookup, and chunk-save corruption.
                val uidFile = File(destDir, "uid.dat")
                if (uidFile.exists()) uidFile.delete()

                // Req 6.5: load world on main thread after copy
                loadWorldOnMainThread(worldName, onSuccess, onError)
            } catch (e: Exception) {
                // Req 6.7: propagate exception via onError
                onError(e)
            }
        })
    }

    /**
     * Loads (or retrieves) a world by name on the main Bukkit thread.
     * Req 6.5, 6.6, 6.9
     */
    private fun loadWorldOnMainThread(
        worldName: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            try {
                val world = Bukkit.getWorld(worldName)
                    ?: Bukkit.createWorld(WorldCreator(worldName))
                    ?: throw Exception("Bukkit.createWorld returned null for '$worldName'")
                onSuccess(world.name)
            } catch (e: Exception) {
                onError(e)
            }
        })
    }

    override fun loadWorld(
        worldName: String,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            try {
                val world = Bukkit.getWorld(worldName)
                if (world != null) {
                    onSuccess()
                } else {
                    onError(Exception("World '$worldName' is not loaded"))
                }
            } catch (e: Exception) {
                onError(e)
            }
        })
    }

    override fun unloadWorld(worldName: String) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            val world = Bukkit.getWorld(worldName) ?: return@Runnable
            Bukkit.unloadWorld(world, true)
        })
    }

    override fun teleportToPlot(player: Player, worldName: String) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            val world = Bukkit.getWorld(worldName) ?: return@Runnable
            player.teleport(world.spawnLocation)
        })
    }
}
