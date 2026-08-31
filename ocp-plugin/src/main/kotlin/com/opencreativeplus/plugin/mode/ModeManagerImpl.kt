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

    /** Guards against concurrent mode switches for the same player+plot (spamming /dev //play). */
    private val switching = ConcurrentHashMap.newKeySet<String>()

    override suspend fun switchMode(player: Player, plot: Plot, mode: PlotMode) {
        val oldMode = getCurrentMode(player, plot)
        if (oldMode == mode) return

        val key = modeKey(player.uniqueId, plot.id)
        if (!switching.add(key)) {
            player.sendMessage("§c[OCP] Mode switch is already in progress — please wait.")
            return
        }
        try {
            // Pre-check DEV access BEFORE tearing down the current mode, so a rejected
            // switch never leaves the player stuck in a half-exited mode.
            if (mode == PlotMode.DEV && !hasDevAccess(player, plot)) {
                player.sendMessage("§c[OCP] У вас нет доступа к режиму разработки на этом участке.")
                return
            }

            // Snapshot the current inventory on the main thread, then persist it for the old mode.
            // DEV inventories are always provisioned, so there is nothing worth persisting.
            if (oldMode != PlotMode.DEV) {
                val (contents, armor, offhand) = runOnMain {
                    Triple(
                        player.inventory.contents.clone(),
                        player.inventory.armorContents.clone(),
                        player.inventory.itemInOffHand.clone()
                    )
                }
                inventoryManager.saveInventorySnapshot(player, plot.id, oldMode, contents, armor, offhand)
            }

            onModeExit(player, plot, oldMode)

            val switched = onModeEnter(player, plot, mode)
            if (!switched) {
                // Rollback: re-enter the old mode so scripts/visuals/player state stay
                // consistent with the mode still recorded in currentModes.
                onModeEnter(player, plot, oldMode)
                return
            }

            // DEV inventories are provisioned by applyDevMode — never restore a stale snapshot
            // over them (old snapshots would miss newly added node categories).
            // For BUILD/PLAY always apply the saved state, clearing the inventory when none
            // exists so items from the previous mode never leak across modes.
            if (mode != PlotMode.DEV) {
                val doc = inventoryManager.fetchInventoryDoc(player, plot.id, mode)
                runOnMain { inventoryManager.applyInventoryDoc(player, doc) }
            }

            currentModes[key] = mode
        } finally {
            switching.remove(key)
        }
    }

    private fun hasDevAccess(player: Player, plot: Plot): Boolean {
        val isOwner = plot.owner == player.uniqueId
        val isTrustedWithAccess = plot.trustedPlayers.contains(player.uniqueId) && plot.settings.allowCodingAccess
        return isOwner || isTrustedWithAccess
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
                inventoryManager.unmarkPlayerInDev(player)
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
        // Access is pre-checked in switchMode before the old mode is torn down.
        val worlds = worldManager.getLoadedWorlds(plot.id)
        val spawnLoc: Location? = worlds?.second?.spawnLocation

        runOnMain {
            resetPlayerState(player)
            player.gameMode = GameMode.CREATIVE
            player.allowFlight = true
            if (spawnLoc != null) player.teleport(spawnLoc)
        }

        runOnMain { inventoryManager.provisionDevInventory(player) }
        inventoryManager.markPlayerInDev(player, plot.id)
        runOnMain {
            player.sendMessage("§a[OCP] DEV mode — place blocks on the blue glass strips to code.")
        }

        // Scan async on coroutine thread — scanCodingZoneAsync suspends until main-thread scan is done
        val scanner = blockScannerFactory(plot)
        val codeLines = scanner.scanCodingZoneAsync { runOnMain(it) }
        devVisualizer?.startFor(player, codeLines)
        hologramReporter?.showToPlayer(player)

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
            val intervalTicks = cycleCodeLine.cycleIntervalTicks().coerceAtLeast(1L)
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
