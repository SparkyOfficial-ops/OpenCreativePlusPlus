package com.opencreativeplus.plugin.mode

import com.opencreativeplus.api.plot.ModeManager
import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.core.execution.CycleEntry
import com.opencreativeplus.core.execution.CycleManager
import com.opencreativeplus.core.execution.ExecutionEngine
import com.opencreativeplus.core.execution.FunctionRegistry
import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.plugin.compiler.BytecodeCompiler
import com.opencreativeplus.plugin.compiler.ASTCompiler
import com.opencreativeplus.plugin.compiler.CompilationResult
import com.opencreativeplus.plugin.event.EventDispatcher
import com.opencreativeplus.plugin.inventory.InventoryManager
import com.opencreativeplus.plugin.scanner.BlockScanner
import com.opencreativeplus.plugin.scanner.cycleIntervalTicks
import com.opencreativeplus.plugin.visualizer.DevVisualizer
import com.opencreativeplus.plugin.visualizer.HologramReporter
import com.opencreativeplus.plugin.world.WorldManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ModeManagerImpl(
    private val inventoryManager: InventoryManager,
    private val worldManager: WorldManager,
    private val blockScannerFactory: (plot: Plot) -> BlockScanner,
    private val astCompiler: ASTCompiler,
    private val eventDispatcher: EventDispatcher,
    private val executionEngine: ExecutionEngine,
    private val plugin: Plugin = Bukkit.getPluginManager().getPlugin("OpenCreativePlus")
        ?: error("OpenCreativePlus plugin not found"),
    private val devVisualizer: DevVisualizer? = null,
    private val hologramReporter: HologramReporter? = null,
    private val bytecodeCompiler: BytecodeCompiler? = null,
    private val cycleManager: CycleManager? = null,
    private val functionRegistry: FunctionRegistry? = null,
    private val variableManager: VariableManager? = null,
    private val scope: CoroutineScope? = null,
    private val plotPersistence: com.opencreativeplus.core.database.PlotPersistence? = null
) : ModeManager {

    private val currentModes = ConcurrentHashMap<String, PlotMode>()

    override suspend fun switchMode(player: Player, plot: Plot, mode: PlotMode) {
        val oldMode = getCurrentMode(player, plot)
        if (oldMode == mode) return

        runOnMain {
            val contents = player.inventory.contents.clone()
            val armor = player.inventory.armorContents.clone()
            val offhand = player.inventory.itemInOffHand.clone()
            Triple(contents, armor, offhand)
        }.let { (contents, armor, offhand) ->
            inventoryManager.saveInventorySnapshot(player, plot.id, oldMode, contents, armor, offhand)
        }

        onModeExit(player, plot, oldMode)

        val switched = onModeEnter(player, plot, mode)
        if (!switched) return

        // Only restore saved inventory if one exists — don't overwrite provisioned DEV inventory
        val doc = inventoryManager.fetchInventoryDoc(player, plot.id, mode)
        if (doc != null) {
            runOnMain { inventoryManager.applyInventoryDoc(player, doc) }
        }

        currentModes[modeKey(player.uniqueId, plot.id)] = mode
    }

    override fun getCurrentMode(player: Player, plot: Plot): PlotMode =
        currentModes[modeKey(player.uniqueId, plot.id)] ?: PlotMode.BUILD

    private suspend fun onModeExit(player: Player, plot: Plot, mode: PlotMode) {
        when (mode) {
            PlotMode.PLAY -> {
                executionEngine.cancelAllExecutions(plot.id)
                eventDispatcher.unregisterScripts(plot.id)
                bytecodeCompiler?.invalidate(plot.id)
                // Req 4.5: stop all cycles when leaving PLAY mode
                cycleManager?.unregisterAll(plot.id)
                // Clear function registry for this plot
                functionRegistry?.clear()
            }
            PlotMode.DEV -> {
                devVisualizer?.stopFor(player)
                hologramReporter?.hideFromPlayer(player)
                hologramReporter?.hideArgHolograms(player)
            }
            PlotMode.BUILD -> {
                runOnMain {
                    player.allowFlight = false
                    player.isFlying = false
                }
            }
        }
    }

    private suspend fun onModeEnter(player: Player, plot: Plot, mode: PlotMode): Boolean {
        return when (mode) {
            PlotMode.BUILD -> { applyBuildMode(player, plot); true }
            PlotMode.DEV   -> applyDevMode(player, plot)
            PlotMode.PLAY  -> applyPlayMode(player, plot)
        }
    }

    private suspend fun applyBuildMode(player: Player, plot: Plot) {
        val mainWorld = worldManager.getLoadedWorlds(plot.id)?.first
        runOnMain {
            resetPlayerState(player)
            if (mainWorld != null) player.teleport(mainWorld.spawnLocation)
            player.gameMode = GameMode.CREATIVE
            player.allowFlight = true
            player.isFlying = true
        }
    }

    private suspend fun applyDevMode(player: Player, plot: Plot): Boolean {
        // Check if player is allowed to enter DEV mode
        val isOwner = plot.owner == player.uniqueId
        val isTrustedWithAccess = plot.trustedPlayers.contains(player.uniqueId) && plot.settings.allowCodingAccess
        if (!isOwner && !isTrustedWithAccess) {
            player.sendMessage("§c[OCP] У вас нет доступа к режиму разработки на этом участке.")
            return false
        }

        val worlds = worldManager.getLoadedWorlds(plot.id)
        if (worlds != null) {
            val spawnLoc: Location = worlds.second.spawnLocation
            runOnMain {
                resetPlayerState(player)
                player.gameMode = GameMode.CREATIVE
                player.allowFlight = true
                player.teleport(spawnLoc)
            }
        } else {
            runOnMain {
                resetPlayerState(player)
                player.gameMode = GameMode.CREATIVE
            }
        }
        // provisionDevInventory — синхронный метод, должен выполняться на main thread
        runOnMain { inventoryManager.provisionDevInventory(player) }
        runOnMain {
            player.sendMessage("§a[OCP] DEV mode — place blocks on the blue glass strips to code.")
        }
        val scanner = blockScannerFactory(plot)
        val codeLines = scanner.scanCodingZoneAsync { runOnMain(it) }
        devVisualizer?.startFor(player, codeLines)
        hologramReporter?.showToPlayer(player)
        // Req 9.1: show arg holograms for all ParamChests when entering DEV mode
        val hr = hologramReporter
        if (hr != null) {
            val devWorld = worldManager.getLoadedWorlds(plot.id)?.second
            if (devWorld != null) {
                for (codeLine in codeLines) {
                    for (node in codeLine.nodes) {
                        val args = runOnMain {
                            val nodeBlock = devWorld.getBlockAt(node.location)
                            val chestBlock = nodeBlock.getRelative(org.bukkit.block.BlockFace.UP)
                            val chest = chestBlock.state as? org.bukkit.block.Chest
                            chest?.inventory?.contents
                                ?.filterNotNull()
                                ?.mapNotNull { item ->
                                    com.opencreativeplus.plugin.scanner.DataContainer.deserializeFrom(item)
                                }
                                ?.let { it to chestBlock.location }
                        } ?: continue
                        if (args.first.isNotEmpty()) {
                            hr.showArgHolograms(player, args.second, args.first)
                        }
                    }
                }
            }
        }
        loadAndRegisterCustomMenus(plot.id)
        return true
    }

    private suspend fun applyPlayMode(player: Player, plot: Plot): Boolean {
        runOnMain { resetPlayerState(player) }
        val scanner = blockScannerFactory(plot)
        val codeLines = scanner.scanCodingZoneAsync { runOnMain(it) }
        val result: CompilationResult = astCompiler.compile(codeLines)

        // Filter out "no blocks" errors — they are expected when the coding zone is empty
        val realErrors = result.errors.filter { "no blocks" !in it.message.lowercase() }

        if (realErrors.isNotEmpty()) {
            player.sendMessage("§c[OCP] Compilation failed with ${realErrors.size} error(s):")
            realErrors.take(5).forEach { err ->
                player.sendMessage("§c  - ${err.location}: ${err.message}")
            }
            return false
        }

        eventDispatcher.registerScripts(plot.id, result.scripts)

        // Dispatch player_join immediately when entering PLAY mode so on_join scripts fire
        // without requiring the player to reconnect to the server
        eventDispatcher.dispatchEvent(
            plotId = plot.id,
            eventType = "player_join",
            eventData = mapOf("player" to player.name),
            player = player
        )

        // Load custom menus from MongoDB and register in PlotMenuRegistry
        loadAndRegisterCustomMenus(plot.id)

        // Req 5.6: subscribe to variable changes for this plot when entering PLAY mode
        if (variableManager != null && scope != null) {
            eventDispatcher.subscribeToVariableChanges(plot.id, variableManager, scope)
        }

        // Req 5.1: load function definitions into registry
        functionRegistry?.loadFromAST(result.scripts)

        // Req 4.1: register cycle entry points with CycleManager
        val cycleEntries = scanner.findCycleEntries(codeLines)
        cycleEntries.forEach { cycleCodeLine ->
            val locationKey = cycleCodeLine.startLocation.let {
                "${it.world?.name ?: "unknown"}:${it.blockX}:${it.blockY}:${it.blockZ}"
            }
            val intervalTicks = cycleCodeLine.cycleIntervalTicks()
            val compiledCycle = astCompiler.compile(listOf(cycleCodeLine))
            val cycleScript = compiledCycle.scripts.firstOrNull()
            if (cycleScript != null) {
                cycleManager?.register(CycleEntry(
                    locationKey = locationKey,
                    plotId = plot.id,
                    intervalTicks = intervalTicks,
                    execute = {
                        executionEngine.executeScript(cycleScript, plot.id, null, emptyMap())
                    }
                ))
            }
        }

        bytecodeCompiler?.scheduleCompile(plot.id)

        val mainWorld = worldManager.getLoadedWorlds(plot.id)?.first
        runOnMain {
            if (mainWorld != null) player.teleport(mainWorld.spawnLocation)
            player.gameMode = GameMode.SURVIVAL
            player.sendMessage("§a[OCP] ${result.scripts.size} script(s) compiled and active.")
        }
        return true
    }

    private suspend fun loadAndRegisterCustomMenus(plotId: UUID) {
        val persistence = plotPersistence ?: return
        runCatching {
            val menuDataList = persistence.loadCustomMenus(plotId)
            menuDataList.forEach { menuData ->
                val slots = menuData.slots.mapValues { (_, slotData) ->
                    val item = try {
                        val bytes = java.util.Base64.getDecoder().decode(slotData.itemData ?: "")
                        org.bukkit.inventory.ItemStack.deserializeBytes(bytes)
                    } catch (_: Exception) {
                        org.bukkit.inventory.ItemStack(
                            org.bukkit.Material.matchMaterial(slotData.itemType) ?: org.bukkit.Material.STONE
                        )
                    }
                    com.opencreativeplus.plugin.node.gui.MenuSlotDefinition(
                        item = item,
                        displayName = slotData.displayName,
                        clickScriptName = slotData.clickScriptName
                    )
                }
                val definition = com.opencreativeplus.plugin.node.gui.CustomMenuDefinition(
                    name = menuData.name,
                    slots = slots
                )
                com.opencreativeplus.plugin.node.gui.PlotMenuRegistry.put(plotId, definition)
            }
        }.onFailure { e ->
            plugin.logger.warning("[OCP] Failed to load custom menus for plot $plotId: ${e.message}")
        }
    }

    private fun resetPlayerState(player: Player) {
        player.activePotionEffects.toList().forEach { player.removePotionEffect(it.type) }
        player.fireTicks = 0
        val maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0
        player.health = maxHealth
        player.foodLevel = 20
        player.velocity = org.bukkit.util.Vector(0.0, 0.0, 0.0)
        player.fallDistance = 0f
    }

    private fun modeKey(playerId: UUID, plotId: UUID) = "$playerId:$plotId"

    private suspend fun <T> runOnMain(block: () -> T): T =
        suspendCancellableCoroutine { cont ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                try { cont.resume(block()) }
                catch (e: Exception) { cont.resumeWithException(e) }
            })
        }
}
