package com.opencreativeplus.plugin

import com.opencreativeplus.core.database.DatabaseConfig
import com.opencreativeplus.core.database.DatabaseIndexManager
import com.opencreativeplus.core.database.MongoConnectionManager
import com.opencreativeplus.core.database.PlotPersistence
import com.opencreativeplus.core.execution.CoroutineConfiguration
import com.opencreativeplus.core.execution.ExecutionEngine
import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.core.trace.TraceManager
import com.opencreativeplus.core.watchdog.TPSMonitor
import com.opencreativeplus.core.watchdog.Watchdog
import com.opencreativeplus.plugin.api.OpenCreativePlusAPI
import com.opencreativeplus.plugin.command.PlotCommands
import com.opencreativeplus.plugin.compiler.ASTCompiler
import com.opencreativeplus.plugin.event.EventDispatcher
import com.opencreativeplus.plugin.event.PlotEventListener
import com.opencreativeplus.plugin.gui.PlotBrowserGUI
import com.opencreativeplus.plugin.gui.PlotConfigGUI
import com.opencreativeplus.plugin.inventory.InventoryManager
import com.opencreativeplus.plugin.logging.ExecutionLogger
import com.opencreativeplus.plugin.logging.LogViewCommand
import com.opencreativeplus.plugin.mode.ModeManagerImpl
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import com.opencreativeplus.plugin.rating.RatingManager
import com.opencreativeplus.plugin.rating.TagManager
import com.opencreativeplus.plugin.registry.BuiltInNodeRegistry
import com.opencreativeplus.plugin.registry.NodeRegistryImpl
import com.opencreativeplus.plugin.scanner.BlockScanner
import com.opencreativeplus.plugin.watchdog.TpsMonitorTask
import com.opencreativeplus.plugin.world.WorldManager
import kotlinx.coroutines.runBlocking
import org.bukkit.plugin.java.JavaPlugin

/**
 * Main plugin class. Wires all components together and manages lifecycle.
 *
 25.2, 34.1, 9.5, 14.5, 26.1, 26.2, 27.5
 */
class OpenCreativePlusPlugin : JavaPlugin() {

    private lateinit var connectionManager: MongoConnectionManager
    private lateinit var coroutineConfig: CoroutineConfiguration
    private lateinit var plotManager: PlotManagerImpl
    private lateinit var modeManager: ModeManagerImpl
    private lateinit var executionEngine: ExecutionEngine
    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var tpsMonitor: TPSMonitor
    private lateinit var watchdog: Watchdog
    private lateinit var tpsMonitorTask: TpsMonitorTask

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
        OpenCreativePlusAPI.initialize(nodeRegistry)

        val worldManager = WorldManager()
        val plotPersistence = PlotPersistence(database, connectionManager)
        val inventoryManager = InventoryManager(database, connectionManager, nodeRegistry)
        val astCompiler = ASTCompiler(nodeRegistry)

        eventDispatcher = EventDispatcher(executionEngine, coroutineConfig.executionScope)

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
            executionEngine = executionEngine
        )

        plotManager = PlotManagerImpl(plotPersistence, worldManager, modeManager)

        // ── Logging ───────────────────────────────────────────────────────────
        val executionLogger = ExecutionLogger(database, connectionManager)

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

        // ── Commands ──────────────────────────────────────────────────────────
        val plotCommands = PlotCommands(plotManager, modeManager, tpsMonitor, scope, traceManager)
        listOf("build", "dev", "play", "plot", "ocptps", "ocp").forEach { cmd ->
            getCommand(cmd)?.setExecutor(plotCommands)
        }
        getCommand("ocplogs")?.setExecutor(LogViewCommand(executionLogger, plotManager, scope))

        logger.info("[OCP] OpenCreative++ enabled.")
    }

    override fun onDisable() {
        logger.info("[OCP] Shutting down...")

        tpsMonitorTask.stop()
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
