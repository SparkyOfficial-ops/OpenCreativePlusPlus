package com.opencreativeplus.plugin

import com.opencreativeplus.core.database.DatabaseConfig
import com.opencreativeplus.core.database.DatabaseIndexManager
import com.opencreativeplus.core.database.MongoConnectionManager
import com.opencreativeplus.core.database.PlotPersistence
import com.opencreativeplus.core.execution.CoroutineConfiguration
import com.opencreativeplus.core.execution.CycleManager
import com.opencreativeplus.core.execution.ExecutionEngine
import com.opencreativeplus.core.execution.FunctionRegistry
import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.plugin.input.SignInputManager
import com.opencreativeplus.core.serialization.ParamSerializer
import com.opencreativeplus.core.trace.TraceManager
import com.opencreativeplus.core.watchdog.TPSMonitor
import com.opencreativeplus.core.watchdog.Watchdog
import com.opencreativeplus.plugin.api.OpenCreativePlusAPI
import com.opencreativeplus.plugin.command.DialogueQuitListener
import com.opencreativeplus.plugin.listener.ArgHologramListener
import com.opencreativeplus.plugin.listener.AutoDecorationListener
import com.opencreativeplus.plugin.listener.PlotProtectionListener
import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.registry.NodeCategory
import com.opencreativeplus.plugin.scanner.ParameterPlacer
import com.opencreativeplus.plugin.gui.NodeSelectionGUI
import com.opencreativeplus.plugin.command.OcpDialogueCommand
import com.opencreativeplus.plugin.command.PlotCommands
import com.opencreativeplus.plugin.compiler.ASTCompiler
import com.opencreativeplus.plugin.compiler.BytecodeCompiler
import com.opencreativeplus.plugin.event.EventDispatcher
import com.opencreativeplus.plugin.event.PlotEventListener
import com.opencreativeplus.plugin.gui.MyWorldsGUI
import com.opencreativeplus.plugin.gui.PlotBrowserGUI
import com.opencreativeplus.plugin.gui.WorldBrowserGUI
import com.opencreativeplus.plugin.gui.WorldSettingsGUI
import com.opencreativeplus.plugin.listener.WorldNavigationListener
import com.opencreativeplus.plugin.gui.PlotConfigGUI
import com.opencreativeplus.plugin.gui.PlotTopGUI
import com.opencreativeplus.plugin.gui.SmartGUI
import com.opencreativeplus.plugin.gui.smartGuiMakeItem
import com.opencreativeplus.plugin.inventory.InventoryManager
import com.opencreativeplus.core.logging.BufferedExecutionLogger
import com.opencreativeplus.plugin.logging.ExecutionLogger
import com.opencreativeplus.plugin.logging.LogViewCommand
import com.opencreativeplus.plugin.mode.ModeManagerImpl
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import com.opencreativeplus.plugin.rating.RatingManager
import com.opencreativeplus.plugin.rating.TagManager
import com.opencreativeplus.plugin.registry.BuiltInNodeRegistry
import com.opencreativeplus.plugin.registry.BuiltInDescriptors
import com.opencreativeplus.plugin.registry.NodeRegistryImpl
import com.opencreativeplus.plugin.scanner.BlockScanner
import com.opencreativeplus.plugin.visualizer.DevVisualizer
import com.opencreativeplus.plugin.visualizer.HologramReporter
import com.opencreativeplus.plugin.watchdog.TpsMonitorTask
import com.opencreativeplus.plugin.world.WorldManager
import com.opencreativeplus.core.world.WorldManager as CoreWorldManager
import com.opencreativeplus.plugin.world.BukkitWorldOperations
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.api.registry.NodeRegistry
import com.comphenix.protocol.ProtocolLibrary
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bukkit.NamespacedKey
import org.bukkit.event.HandlerList
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Main plugin class. Wires all components together and manages lifecycle.
 *
 25.2, 34.1, 9.5, 14.5, 26.1, 26.2, 27.5
 */
class OpenCreativePlusPlugin : JavaPlugin() {

    private lateinit var connectionManager: MongoConnectionManager
    private lateinit var coroutineConfig: CoroutineConfiguration
    private lateinit var bufferedLogger: BufferedExecutionLogger
    private lateinit var plotManager: PlotManagerImpl
    private lateinit var modeManager: ModeManagerImpl
    private lateinit var executionEngine: ExecutionEngine
    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var tpsMonitor: TPSMonitor
    private lateinit var watchdog: Watchdog
    private lateinit var tpsMonitorTask: TpsMonitorTask
    private lateinit var categoryRegistry: CategoryRegistry
    private lateinit var hologramReporter: HologramReporter
    private lateinit var bytecodeCompiler: BytecodeCompiler
    lateinit var signInputManager: SignInputManager
        private set
    lateinit var paramSerializer: ParamSerializer
        private set
    private lateinit var cycleManager: CycleManager
    private lateinit var functionRegistry: FunctionRegistry
    private lateinit var coreWorldManager: CoreWorldManager

    override fun onEnable() {
        saveDefaultConfig()

        // ── Database ──────────────────────────────────────────────────────────
        val dbUri = config.getString("database.uri", "mongodb://localhost:27017")!!
        val dbName = config.getString("database.name", "opencreativeplus")!!
        val maxRetries = config.getInt("database.max_retries", 3)
        val retryDelay = config.getLong("database.retry_delay_ms", 1000L)

        val dbConfig = DatabaseConfig(
            connectionString = dbUri,
            databaseName = dbName,
            maxRetries = maxRetries,
            retryDelayMs = retryDelay
        )
        connectionManager = MongoConnectionManager(dbConfig)

        runBlocking {
            connectionManager.connect()
        }

        val database = connectionManager.getDatabase()

        runBlocking {
            DatabaseIndexManager(database).createIndexes()
        }

        // ── Coroutines ────────────────────────────────────────────────────────
        coroutineConfig = CoroutineConfiguration { block ->
            server.scheduler.runTask(this@OpenCreativePlusPlugin) { _ -> block() }
        }

        // ── Watchdog ──────────────────────────────────────────────────────────
        tpsMonitor = TPSMonitor()
        watchdog = Watchdog(tpsMonitor)

        // ── Core components ───────────────────────────────────────────────────
        val variableManager = VariableManager(database)
        hologramReporter = HologramReporter(this)

        // ── Cycle & Function systems ──────────────────────────────────────────
        cycleManager = CycleManager(plugin = this, scope = coroutineConfig.executionScope)
        functionRegistry = FunctionRegistry()

        executionEngine = ExecutionEngine(
            watchdog = watchdog,
            variableManager = variableManager,
            coroutineConfig = coroutineConfig,
            errorReporter = { sourceLocation, message ->
                // ArmorStand spawning requires the main thread
                server.scheduler.runTask(this@OpenCreativePlusPlugin) { _ ->
                    parseLocation(sourceLocation)?.let { loc ->
                        hologramReporter.reportError(loc, message)
                    }
                }
            },
            compiledScriptProvider = { plotId ->
                if (::bytecodeCompiler.isInitialized) bytecodeCompiler.getCompiled(plotId) else null
            },
            functionRegistry = functionRegistry
        )

        // ── Trace Manager ─────────────────────────────────────────────────────
        val traceManager = TraceManager(this)
        executionEngine.setTraceManager(traceManager)

        val nodeRegistry = NodeRegistryImpl()
        BuiltInNodeRegistry.register(nodeRegistry)
        BuiltInNodeRegistry.registerPluginActions(nodeRegistry, this)
        BuiltInNodeRegistry.registerVariableNodes(nodeRegistry, variableManager)
        OpenCreativePlusAPI.initialize(nodeRegistry)

        categoryRegistry = CategoryRegistry()
        BuiltInDescriptors.register(categoryRegistry)

        // Diagnostic: verify descriptors loaded correctly
        val totalDescriptors = NodeCategory.entries.sumOf { categoryRegistry.getDescriptors(it).size }
        logger.info("[OCP] CategoryRegistry loaded: $totalDescriptors descriptors across ${NodeCategory.entries.size} categories")
        NodeCategory.entries.forEach { cat ->
            logger.info("[OCP]   ${cat.name}: ${categoryRegistry.getDescriptors(cat).size} actions")
        }

        val worldManager = WorldManager()
        val plotPersistence = PlotPersistence(database, connectionManager)
        val worldOps = BukkitWorldOperations(this, worldManager)
        coreWorldManager = CoreWorldManager(
            plugin = this,
            plotPersistence = plotPersistence,
            worldOps = worldOps,
            scope = coroutineConfig.executionScope,
            logger = logger
        )
        val inventoryManager = InventoryManager(
            database = database,
            connectionManager = connectionManager,
            nodeRegistry = nodeRegistry,
            categoryRegistry = categoryRegistry,
            logger = logger
        )
        val astCompiler = ASTCompiler(nodeRegistry)

        eventDispatcher = EventDispatcher(executionEngine, coroutineConfig.executionScope)

        bytecodeCompiler = BytecodeCompiler(
            tpsMonitor = tpsMonitor,
            scope = coroutineConfig.executionScope,
            scriptProvider = { plotId ->
                eventDispatcher.getCompiledScripts(plotId).firstOrNull()
            },
            logger = logger
        )
        val devVisualizer = DevVisualizer(
            plugin = this,
            traceManager = traceManager,
            blockScannerFactory = { world -> BlockScanner(world, nodeRegistry) }
        )
        server.pluginManager.registerEvents(devVisualizer, this)

        modeManager = ModeManagerImpl(
            inventoryManager = inventoryManager,
            worldManager = worldManager,
            blockScannerFactory = { plot ->
                val devWorld = worldManager.getLoadedWorlds(plot.id)?.second
                    ?: server.worlds.firstOrNull()
                    ?: error("No worlds available for plot ${plot.id}")
                BlockScanner(devWorld, nodeRegistry)
            },
            astCompiler = astCompiler,
            eventDispatcher = eventDispatcher,
            executionEngine = executionEngine,
            devVisualizer = devVisualizer,
            hologramReporter = hologramReporter,
            bytecodeCompiler = bytecodeCompiler,
            cycleManager = cycleManager,
            functionRegistry = functionRegistry,
            variableManager = variableManager,
            scope = coroutineConfig.executionScope,
            plotPersistence = plotPersistence
        )

        plotManager = PlotManagerImpl(plotPersistence, worldManager, modeManager)

        BuiltInNodeRegistry.registerGUINodes(nodeRegistry, this, modeManager, plotManager, eventDispatcher, coroutineConfig.executionScope, plotPersistence)

        // ── Logging ───────────────────────────────────────────────────────────
        val executionLogger = ExecutionLogger(database, connectionManager)
        val logsCollection = database.getCollection<org.bson.Document>("execution_logs")
        bufferedLogger = BufferedExecutionLogger(logsCollection, coroutineConfig.executionScope)

        // ── TPS task ──────────────────────────────────────────────────────────
        tpsMonitorTask = TpsMonitorTask(this, tpsMonitor, watchdog, bytecodeCompiler = bytecodeCompiler)
        tpsMonitorTask.start()

        // ── Event listeners ───────────────────────────────────────────────────
        val scope = coroutineConfig.executionScope
        server.pluginManager.registerEvents(
            PlotEventListener(eventDispatcher, plotManager, modeManager, scope), this
        )
        server.pluginManager.registerEvents(PlotBrowserGUI(plotPersistence, plotManager, scope), this)

        // Req 7.5, 7.6: track player join/leave for WorldManager auto-unload
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun onPlayerJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
                val player = event.player
                scope.launch {
                    val plot = plotManager.getPlayerPlot(player.uniqueId) ?: return@launch
                    coreWorldManager.onPlayerJoin(plot.id, player.uniqueId)
                }
            }
            @EventHandler
            fun onPlayerQuit(event: PlayerQuitEvent) {
                val player = event.player
                scope.launch {
                    val plot = plotManager.getPlayerPlot(player.uniqueId) ?: return@launch
                    coreWorldManager.onPlayerLeave(plot.id, player.uniqueId)
                }
            }
        }, this)
        server.pluginManager.registerEvents(PlotConfigGUI(plotManager, scope), this)
        server.pluginManager.registerEvents(DialogueQuitListener(), this)

        val plotTopGUI = PlotTopGUI(plotPersistence, plotManager, scope)
        server.pluginManager.registerEvents(plotTopGUI, this)

        // Category-based coding UI listeners
        val parameterPlacer = ParameterPlacer(this)
        server.pluginManager.registerEvents(
            NodeSelectionGUI(categoryRegistry, parameterPlacer, this), this
        )
        server.pluginManager.registerEvents(
            PlotProtectionListener(modeManager, categoryRegistry, plotManager, scope, this), this
        )
        // Auto-decoration: places a coloured glowing sign when a Category_Block lands on a strip
        server.pluginManager.registerEvents(
            AutoDecorationListener(
                modeManager = modeManager,
                categoryRegistry = categoryRegistry,
                plotManager = plotManager,
                scope = scope,
                plugin = this
            ), this
        )
        // Req 9.3: update arg holograms when chest contents change
        server.pluginManager.registerEvents(
            ArgHologramListener(
                plugin = this,
                hologramReporter = hologramReporter
            ), this
        )

        // ── SignInputManager + ParamSerializer ────────────────────────────────
        val protocolManager = ProtocolLibrary.getProtocolManager()
        signInputManager = SignInputManager(this, protocolManager)
        paramSerializer = ParamSerializer { name -> NamespacedKey(this, name) }

        // PlayerQuitEvent listener: cleans up sign input sessions
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun onQuit(event: PlayerQuitEvent) {
                signInputManager.onPlayerQuit(event.player.uniqueId)
            }
        }, this)

        // World management GUIs (after signInputManager is initialized)
        val myWorldsGUI = MyWorldsGUI(plotManager, plotPersistence, modeManager, scope, this)
        val worldSettingsGUI = WorldSettingsGUI(plotManager, scope, this)
        val worldBrowserGUI = WorldBrowserGUI(plotManager, plotPersistence, signInputManager, scope, this)
        server.pluginManager.registerEvents(myWorldsGUI, this)
        server.pluginManager.registerEvents(worldSettingsGUI, this)
        server.pluginManager.registerEvents(worldBrowserGUI, this)
        server.pluginManager.registerEvents(
            WorldNavigationListener(myWorldsGUI, worldBrowserGUI, scope), this
        )

        // Action node interact listener: opens SmartGUI on right-click in DEV mode
        server.pluginManager.registerEvents(
            ActionNodeInteractListener(
                nodeRegistry = nodeRegistry,
                modeManager = modeManager,
                plotManager = plotManager,
                signInputManager = signInputManager,
                paramSerializer = paramSerializer,
                variableManager = variableManager,
                scope = scope,
                plugin = this
            ), this
        )

        // ── Commands ──────────────────────────────────────────────────────────
        val plotCommands = PlotCommands(plotManager, modeManager, tpsMonitor, scope, traceManager, variableManager = variableManager, plugin = this, plotTopGUI = plotTopGUI, coreWorldManager = coreWorldManager, signInputManager = signInputManager)
        listOf("build", "dev", "play", "plot", "ocptps", "ocp").forEach { cmd ->
            getCommand(cmd)?.setExecutor(plotCommands)
        }
        getCommand("ocplogs")?.setExecutor(LogViewCommand(executionLogger, plotManager, scope))
        getCommand("ocp_dialogue")?.setExecutor(OcpDialogueCommand())

        logger.info("[OCP] OpenCreative++ enabled.")
    }

    override fun onDisable() {
        logger.info("[OCP] Shutting down...")

        tpsMonitorTask.stop()

        runBlocking {
            bufferedLogger.flush()
        }

        coroutineConfig.close()

        runBlocking {
            plotManager.getAllLoadedPlots().forEach { plot ->
                runCatching { plotManager.unloadPlot(plot.id) }
            }
        }

        hologramReporter.clearAll()
        coreWorldManager.cancelAllTimers()
        connectionManager.close()
        logger.info("[OCP] OpenCreative++ disabled.")
    }

    private fun parseLocation(sourceLocation: String): org.bukkit.Location? {
        return try {
            val atIdx = sourceLocation.indexOf('@')
            if (atIdx < 0) return null
            val worldName = sourceLocation.substring(0, atIdx)
            val coords = sourceLocation.substring(atIdx + 1).split(",")
            if (coords.size < 3) return null
            val world = server.getWorld(worldName) ?: return null
            org.bukkit.Location(world, coords[0].trim().toDouble(), coords[1].trim().toDouble(), coords[2].trim().toDouble())
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Listens for PlayerInteractEvent and opens SmartGUI when a player in DEV mode
 * right-clicks a block registered as an action node.
 *
 * s: 1.1
 */
class ActionNodeInteractListener(
    private val nodeRegistry: NodeRegistry,
    private val modeManager: ModeManagerImpl,
    private val plotManager: PlotManagerImpl,
    private val signInputManager: SignInputManager,
    private val paramSerializer: ParamSerializer,
    private val variableManager: VariableManager,
    private val scope: CoroutineScope,
    private val plugin: JavaPlugin
) : Listener {

    private val activeGuis = ConcurrentHashMap<java.util.UUID, SmartGUI>()

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        val player = event.player

        scope.launch {
            val plot = plotManager.getPlayerPlot(player.uniqueId) ?: return@launch
            if (modeManager.getCurrentMode(player, plot) != PlotMode.DEV) return@launch
            if (nodeRegistry.getActionFactory(block.type) == null) return@launch

            event.isCancelled = true
            val gui = SmartGUI(
                player = player,
                block = block,
                nodeRegistry = nodeRegistry,
                signInputManager = signInputManager,
                paramSerializer = paramSerializer,
                scope = scope,
                plotId = plot.id,
                variableManager = variableManager,
                itemFactory = { mat, name, lore -> smartGuiMakeItem(mat, name, lore) }
            )
            activeGuis[player.uniqueId]?.let { HandlerList.unregisterAll(it) }
            activeGuis[player.uniqueId] = gui
            plugin.server.pluginManager.registerEvents(gui, plugin)
            gui.open()
        }
    }
}
