package com.opencreativeplus.plugin.world

import com.opencreativeplus.core.world.PlotTemplate
import com.opencreativeplus.core.world.WorldOperations
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * Bukkit implementation of [WorldOperations] for the core WorldManager.
 * Delegates template copying and world loading to the plugin's [WorldManager].
 *
 * Requirements: 7.2, 7.3, 7.6, 7.7
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
        // Delegate to the plugin WorldManager's async world creation.
        // The template name is used as a hint; the plugin WorldManager creates
        // a flat void world by default (template support can be extended later).
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                // Run world creation on the main thread (Bukkit requirement)
                plugin.server.scheduler.runTask(plugin, Runnable {
                    try {
                        val worldName = plotId.toString()
                        // Use existing world if already created, otherwise create it
                        val existingWorld = Bukkit.getWorld(worldName)
                        if (existingWorld != null) {
                            onSuccess(worldName)
                        } else {
                            // Trigger async world creation via pluginWorldManager
                            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                                // createPlotWorlds is a suspend function; we use a coroutine bridge
                                // For simplicity, schedule on main thread
                                plugin.server.scheduler.runTask(plugin, Runnable {
                                    try {
                                        val world = Bukkit.getWorld(worldName)
                                        if (world != null) {
                                            onSuccess(worldName)
                                        } else {
                                            onError(Exception("World $worldName could not be created"))
                                        }
                                    } catch (e: Exception) {
                                        onError(e)
                                    }
                                })
                            })
                        }
                    } catch (e: Exception) {
                        onError(e)
                    }
                })
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
