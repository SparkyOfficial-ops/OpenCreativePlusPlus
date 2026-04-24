package com.opencreativeplus.plugin

import com.opencreativeplus.core.database.DatabaseConfig
import com.opencreativeplus.core.database.DatabaseIndexManager
import com.opencreativeplus.core.database.MongoConnectionManager
import com.opencreativeplus.core.database.PlotPersistence
import com.opencreativeplus.core.execution.CoroutineConfiguration
import com.opencreativeplus.core.execution.ExecutionEngine
import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.core.input.ChatInputManager
import com.opencreativeplus.core.serialization.ParamSerializer
import com.opencreativeplus.core.trace.TraceManager
import com.opencreativeplus.core.watchdog.TPSMonitor
import com.opencreativeplus.core.watchdog.Watchdog
import com.opencreativeplus.plugin.api.OpenCreativePlusAPI
import com.opencreativeplus.plugin.command.DialogueQuitListener
import com.opencreativeplus.plugin.listener.PlotProtectionListener
import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.scanner.ParameterPlacer
import com.opencreativeplus.plugin.gui.NodeSelectionGUI
import com.opencreativeplus.plugin.command.OcpDialogueCommand
import com.opencreativeplus.plugin.command.PlotCommands
import com.opencreativeplus.plugin.compiler.ASTCompiler
import com.opencreativeplus.plugin.event.EventDispatcher
import com.opencreativeplus.plugin.event.PlotEventListener
import com.opencreativeplus.plugin.gui.PlotBrowserGUI
import com.opencreativeplus.plugin.gui.PlotConfigGUI
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
import com.opencreativeplus.plugin.registry.NodeRegistryImpl
import com.opencreativeplus.plugin.scanner.BlockScanner
import com.opencreativeplus.plugin.visualizer.DevVisualizer
import com.opencreativeplus.plugin.watchdog.TpsMonitorTask
import com.opencreativeplus.plugin.world.WorldManager
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.api.registry.NodeRegistry
import io.papermc.paper.event.player.AsyncChatEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.NamespacedKey
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
    lateinit var chatInputManager: ChatInputManager
        private set
    lateinit var paramSerializer: ParamSerializer
        private set

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
        executionEngine = ExecutionEngine(watchdog, variableManager, coroutineConfig)

        // ── Trace Manager ─────────────────────────────────────────────────────
        val traceManager = TraceManager(this)
        executionEngine.setTraceManager(traceManager)

        val nodeRegistry = NodeRegistryImpl()
        BuiltInNodeRegistry.register(nodeRegistry)
        BuiltInNodeRegistry.registerPluginActions(nodeRegistry, this)
        OpenCreativePlusAPI.initialize(nodeRegistry)

        categoryRegistry = CategoryRegistry()

        val worldManager = WorldManager()
        val plotPersistence = PlotPersistence(database, connectionManager)
        val inventoryManager = InventoryManager(
            database = database,
            connectionManager = connectionManager,
            nodeRegistry = nodeRegistry,
            categoryRegistry = categoryRegistry,
            logger = logger
        )
        val astCompiler = ASTCompiler(nodeRegistry)

        eventDispatcher = EventDispatcher(executionEngine, coroutineConfig.executionScope)

        val devVisualizer = DevVisualizer(
            plugin = this,
            tpsMonitor = tpsMonitor,
            blockScannerFactory = { world -> BlockScanner(world, nodeRegistry) }
        )
        server.pluginManager.registerEvents(devVisualizer, this)

        modeManager = ModeManagerImpl(
            inventoryManager = inventoryManager,
            worldManager = worldManager,
            blockScannerFactory = { plot ->
                val devWorld = worldManager.getLoadedWorlds(plot.id)?.second
                    ?: error("Dev world not loaded for plot ${plot.id}")
                BlockScanner(devWorld, nodeRegistry)
            },
            astCompiler = astCompiler,
            eventDispatcher = eventDispatcher,
            executionEngine = executionEngine,
            devVisualizer = devVisualizer
        )

        plotManager = PlotManagerImpl(plotPersistence, worldManager, modeManager)

        // ── Logging ───────────────────────────────────────────────────────────
        val executionLogger = ExecutionLogger(database, connectionManager)
        val logsCollection = database.getCollection<org.bson.Document>("execution_logs")
        bufferedLogger = BufferedExecutionLogger(logsCollection, coroutineConfig.executionScope)

        // ── TPS task ──────────────────────────────────────────────────────────
        tpsMonitorTask = TpsMonitorTask(this, tpsMonitor, watchdog)
        tpsMonitorTask.start()

        // ── Event listeners ───────────────────────────────────────────────────
        val scope = coroutineConfig.executionScope
        server.pluginManager.registerEvents(
            PlotEventListener(eventDispatcher, plotManager, modeManager, scope), this
        )
        server.pluginManager.registerEvents(PlotBrowserGUI(plotPersistence, plotManager, scope), this)
        server.pluginManager.registerEvents(PlotConfigGUI(plotManager, scope), this)
        server.pluginManager.registerEvents(DialogueQuitListener(), this)

        // Category-based coding UI listeners
        val parameterPlacer = ParameterPlacer(this)
        server.pluginManager.registerEvents(
            NodeSelectionGUI(categoryRegistry, parameterPlacer, this), this
        )
        server.pluginManager.registerEvents(
            PlotProtectionListener(modeManager, categoryRegistry, plotManager, scope, this), this
        )

        // ── ChatInputManager + ParamSerializer ────────────────────────────────
        chatInputManager = ChatInputManager()
        paramSerializer = ParamSerializer { name -> NamespacedKey(this, name) }

        // Chat input listener: intercepts AsyncChatEvent and PlayerQuitEvent
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun onChat(event: AsyncChatEvent) {
                val text = PlainTextComponentSerializer.plainText().serialize(event.message())
                if (chatInputManager.onChatMessage(event.player.uniqueId, text)) {
                    event.isCancelled = true
                }
            }

            @EventHandler
            fun onQuit(event: PlayerQuitEvent) {
                chatInputManager.onPlayerDisconnect(event.player.uniqueId)
            }
        }, this)

        // Action node interact listener: opens SmartGUI on right-click in DEV mode
        server.pluginManager.registerEvents(
            ActionNodeInteractListener(
                nodeRegistry = nodeRegistry,
                modeManager = modeManager,
                plotManager = plotManager,
                chatInputManager = chatInputManager,
                paramSerializer = paramSerializer,
                variableManager = variableManager,
                scope = scope,
                plugin = this
            ), this
        )

        // ── Commands ──────────────────────────────────────────────────────────
        val plotCommands = PlotCommands(plotManager, modeManager, tpsMonitor, scope, traceManager)
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

        connectionManager.close()
        logger.info("[OCP] OpenCreative++ disabled.")
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
    private val chatInputManager: ChatInputManager,
    private val paramSerializer: ParamSerializer,
    private val variableManager: VariableManager,
    private val scope: CoroutineScope,
    private val plugin: JavaPlugin
) : Listener {

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
                chatInputManager = chatInputManager,
                paramSerializer = paramSerializer,
                scope = scope,
                plotId = plot.id,
                variableManager = variableManager,
                itemFactory = { mat, name, lore -> smartGuiMakeItem(mat, name, lore) }
            )
            plugin.server.pluginManager.registerEvents(gui, plugin)
            gui.open()
        }
    }
}
