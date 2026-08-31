package com.opencreativeplus.plugin.mode

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.api.plot.PlotSettings
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.execution.ExecutionEngine
import com.opencreativeplus.plugin.compiler.ASTCompiler
import com.opencreativeplus.plugin.compiler.CompilationError
import com.opencreativeplus.plugin.compiler.CompilationResult
import com.opencreativeplus.plugin.event.EventDispatcher
import com.opencreativeplus.plugin.inventory.InventoryManager
import com.opencreativeplus.plugin.scanner.BlockScanner
import com.opencreativeplus.plugin.scanner.CodeLine
import com.opencreativeplus.plugin.world.WorldManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.PlayerInventory
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class ModeManagerTest {

    private lateinit var inventoryManager: InventoryManager
    private lateinit var worldManager: WorldManager
    private lateinit var blockScanner: BlockScanner
    private lateinit var astCompiler: ASTCompiler
    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var executionEngine: ExecutionEngine
    private lateinit var plugin: Plugin
    private lateinit var modeManager: ModeManagerImpl

    @BeforeEach
    fun setup() {
        mockkStatic(Bukkit::class)

        plugin = mockk(relaxed = true)
        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.getPlugin(any()) } returns plugin
        every { Bukkit.getPluginManager() } returns pluginManager

        val stubbedTask = mockk<BukkitTask>(relaxed = true)
        val scheduler = mockk<BukkitScheduler>(relaxed = true)
        every { Bukkit.getScheduler() } returns scheduler
        val runnableSlot = slot<Runnable>()
        every { scheduler.runTask(any<Plugin>(), capture(runnableSlot)) } answers {
            runnableSlot.captured.run()
            stubbedTask
        }

        inventoryManager = mockk(relaxed = true)
        worldManager = mockk(relaxed = true)
        blockScanner = mockk(relaxed = true)
        astCompiler = mockk(relaxed = true)
        eventDispatcher = mockk(relaxed = true)
        executionEngine = mockk(relaxed = true)

        every { blockScanner.scanCodingZone() } returns emptyList()
        coEvery { blockScanner.scanCodingZoneAsync(any()) } returns emptyList()
        every { astCompiler.compile(any()) } returns CompilationResult(emptyList(), emptyList())
        every { worldManager.getLoadedWorlds(any()) } returns null
        coEvery { inventoryManager.fetchInventoryDoc(any(), any(), any()) } returns null

        modeManager = ModeManagerImpl(
            inventoryManager = inventoryManager,
            worldManager = worldManager,
            blockScannerFactory = { _ -> blockScanner },
            astCompiler = astCompiler,
            eventDispatcher = eventDispatcher,
            executionEngine = executionEngine,
            plugin = plugin
        )
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    private fun makePlot(id: UUID = UUID.randomUUID(), owner: UUID = UUID.randomUUID()): Plot = Plot(
        id = id,
        owner = owner,
        name = "Test Plot",
        description = "",
        mainWorldName = "${id}_main",
        devWorldName = "${id}_dev",
        createdAt = 0L,
        updatedAt = 0L,
        settings = PlotSettings(),
        metadata = PlotMetadata()
    )

    private fun mockPlayer(id: UUID = UUID.randomUUID()): Player {
        val inventory = mockk<PlayerInventory>(relaxed = true)
        val world = mockk<World>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        every { location.world } returns world
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns id
        every { player.inventory } returns inventory
        every { player.location } returns location
        return player
    }

    private fun makeScript(): CompiledScript {
        val event = mockk<com.opencreativeplus.api.node.IEvent>(relaxed = true)
        every { event.eventType } returns "player_join"
        return CompiledScript(event = event, actions = emptyList(), sourceLocation = "test@0,0,0")
    }

    // --- Inventory save/restore (Req 2.8, 2.9) ---

    @Test
    fun `switchMode saves old inventory and restores new inventory across multiple switches`() = runTest {
        val playerId = UUID.randomUUID()
        val player = mockPlayer(playerId); val plot = makePlot(owner = playerId)
        val savedModes = mutableListOf<PlotMode>()
        val loadedModes = mutableListOf<PlotMode>()
        coEvery { inventoryManager.saveInventorySnapshot(any(), any(), capture(savedModes), any(), any(), any()) } just Runs
        coEvery { inventoryManager.fetchInventoryDoc(any(), any(), capture(loadedModes)) } returns null
        modeManager.switchMode(player, plot, PlotMode.DEV)
        modeManager.switchMode(player, plot, PlotMode.PLAY)
        modeManager.switchMode(player, plot, PlotMode.BUILD)
        // DEV inventories are provisioned — never saved or restored.
        // BUILD→DEV saves BUILD; DEV→PLAY restores PLAY; PLAY→BUILD saves PLAY then restores BUILD.
        assertEquals(listOf(PlotMode.BUILD, PlotMode.PLAY), savedModes)
        assertEquals(listOf(PlotMode.PLAY, PlotMode.BUILD), loadedModes)
    }

    @Test
    fun `switchMode does not save inventory when mode is unchanged`() = runTest {
        val player = mockPlayer(); val plot = makePlot()
        modeManager.switchMode(player, plot, PlotMode.BUILD)
        coVerify(exactly = 0) { inventoryManager.saveInventorySnapshot(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `switchMode saves old inventory before restoring new inventory`() = runTest {
        val playerId = UUID.randomUUID()
        val player = mockPlayer(playerId); val plot = makePlot(owner = playerId)
        val callOrder = mutableListOf<String>()
        coEvery { inventoryManager.saveInventorySnapshot(any(), any(), any(), any(), any(), any()) } answers { callOrder.add("save") }
        coEvery { inventoryManager.fetchInventoryDoc(any(), any(), any()) } answers { callOrder.add("load"); null }
        modeManager.switchMode(player, plot, PlotMode.PLAY)
        assert(callOrder.indexOf("save") >= 0) { "saveInventorySnapshot should be called" }
        assert(callOrder.indexOf("load") >= 0) { "fetchInventoryDoc should be called" }
        assert(callOrder.indexOf("save") < callOrder.indexOf("load")) {
            "save must happen before load, got: $callOrder"
        }
    }

    // --- Script compilation (Req 2.6 / 5.1) ---

    @Test
    fun `switchMode triggers block scan and AST compilation when switching to PLAY mode`() = runTest {
        val playerId = UUID.randomUUID()
        val player = mockPlayer(playerId); val plot = makePlot(owner = playerId)
        val codeLines = listOf(mockk<CodeLine>(relaxed = true))
        every { blockScanner.scanCodingZone() } returns codeLines
        coEvery { blockScanner.scanCodingZoneAsync(any()) } returns codeLines
        modeManager.switchMode(player, plot, PlotMode.PLAY)
        coVerify(exactly = 1) { blockScanner.scanCodingZoneAsync(any()) }
        verify(exactly = 1) { astCompiler.compile(codeLines) }
    }

    @Test
    fun `switchMode does not trigger compilation when switching to non-PLAY modes`() = runTest {
        val playerId = UUID.randomUUID()
        val player = mockPlayer(playerId); val plot = makePlot(owner = playerId)
        modeManager.switchMode(player, plot, PlotMode.DEV)
        modeManager.switchMode(player, plot, PlotMode.BUILD)
        verify(exactly = 0) { astCompiler.compile(any()) }
    }

    @Test
    fun `switchMode registers compiled scripts with EventDispatcher on successful compilation`() = runTest {
        val playerId = UUID.randomUUID()
        val player = mockPlayer(playerId); val plot = makePlot(owner = playerId)
        val scripts = listOf(makeScript(), makeScript())
        every { astCompiler.compile(any()) } returns CompilationResult(scripts, emptyList())
        modeManager.switchMode(player, plot, PlotMode.PLAY)
        verify(exactly = 1) { eventDispatcher.registerScripts(plot.id, scripts) }
    }

    @Test
    fun `switchMode does not register scripts and reverts to previous mode when compilation fails`() = runTest {
        val playerId = UUID.randomUUID()
        val player = mockPlayer(playerId); val plot = makePlot(owner = playerId)
        val error = CompilationError(mockk(relaxed = true), "Unknown block")
        every { astCompiler.compile(any()) } returns CompilationResult(emptyList(), listOf(error))
        modeManager.switchMode(player, plot, PlotMode.DEV)
        modeManager.switchMode(player, plot, PlotMode.PLAY)
        verify(exactly = 0) { eventDispatcher.registerScripts(any(), any()) }
        assertEquals(PlotMode.DEV, modeManager.getCurrentMode(player, plot))
    }

    // --- Execution cancellation (Req 26.2) ---

    @Test
    fun `switchMode cancels executions and unregisters scripts when leaving PLAY mode`() = runTest {
        val playerId = UUID.randomUUID()
        val player = mockPlayer(playerId); val plot = makePlot(owner = playerId)
        modeManager.switchMode(player, plot, PlotMode.PLAY)
        for (target in listOf(PlotMode.BUILD, PlotMode.DEV)) {
            clearMocks(executionEngine, eventDispatcher, answers = false, recordedCalls = true)
            modeManager.switchMode(player, plot, PlotMode.PLAY)
            modeManager.switchMode(player, plot, target)
            verify(exactly = 1) { executionEngine.cancelAllExecutions(plot.id) }
            verify(exactly = 1) { eventDispatcher.unregisterScripts(plot.id) }
        }
    }

    @Test
    fun `switchMode does not cancel executions when switching between non-PLAY modes`() = runTest {
        val playerId = UUID.randomUUID()
        val player = mockPlayer(playerId); val plot = makePlot(owner = playerId)
        modeManager.switchMode(player, plot, PlotMode.DEV)
        modeManager.switchMode(player, plot, PlotMode.BUILD)
        verify(exactly = 0) { executionEngine.cancelAllExecutions(any()) }
    }

    @Test
    fun `switchMode cancels executions before restoring inventory when leaving PLAY mode`() = runTest {
        val playerId = UUID.randomUUID()
        val player = mockPlayer(playerId); val plot = makePlot(owner = playerId)
        val callOrder = mutableListOf<String>()
        every { executionEngine.cancelAllExecutions(any()) } answers { callOrder.add("cancel") }
        coEvery { inventoryManager.fetchInventoryDoc(any(), any(), any()) } answers { callOrder.add("load"); null }
        modeManager.switchMode(player, plot, PlotMode.PLAY)
        callOrder.clear()
        modeManager.switchMode(player, plot, PlotMode.BUILD)
        val cancelIdx = callOrder.indexOf("cancel")
        val loadIdx = callOrder.indexOf("load")
        assert(cancelIdx >= 0) { "cancelAllExecutions should be called" }
        assert(loadIdx >= 0) { "fetchInventoryDoc should be called" }
        assert(cancelIdx < loadIdx) { "Executions must be cancelled before inventory is restored" }
    }

    // --- getCurrentMode ---

    @Test
    fun `getCurrentMode returns BUILD by default`() {
        val player = mockPlayer(); val plot = makePlot()
        assertEquals(PlotMode.BUILD, modeManager.getCurrentMode(player, plot))
    }

    @Test
    fun `getCurrentMode reflects the mode after a successful switch`() = runTest {
        val playerId = UUID.randomUUID()
        val player = mockPlayer(playerId); val plot = makePlot(owner = playerId)
        modeManager.switchMode(player, plot, PlotMode.DEV)
        assertEquals(PlotMode.DEV, modeManager.getCurrentMode(player, plot))
    }

    @Test
    fun `getCurrentMode is independent per player`() = runTest {
        val playerA = mockPlayer(); val playerB = mockPlayer()
        val plot = makePlot(owner = playerA.uniqueId)
        modeManager.switchMode(playerA, plot, PlotMode.DEV)
        assertEquals(PlotMode.DEV, modeManager.getCurrentMode(playerA, plot))
        assertEquals(PlotMode.BUILD, modeManager.getCurrentMode(playerB, plot))
    }

    @Test
    fun `getCurrentMode is independent per plot`() = runTest {
        val playerId = UUID.randomUUID()
        val player = mockPlayer(playerId); val plotA = makePlot(owner = playerId); val plotB = makePlot(owner = playerId)
        modeManager.switchMode(player, plotA, PlotMode.DEV)
        assertEquals(PlotMode.DEV, modeManager.getCurrentMode(player, plotA))
        assertEquals(PlotMode.BUILD, modeManager.getCurrentMode(player, plotB))
    }
}
