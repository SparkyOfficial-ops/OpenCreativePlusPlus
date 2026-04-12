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
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.PlayerInventory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/
 * Unit tests for ModeManagerImpl covering:
 * - Inventory save/restore on mode switch (s 2.8, 2.9)
 * - Script compilation trigger when switching to PLAY mode ( 2.6)
 * - Coroutine/execution cancellation when leaving PLAY mode ( 26.2)
 *
2.8, 2.9, 26.2
 */
class ModeManagerTest {

    private lateinit var inventoryManager: InventoryManager
    private lateinit var worldManager: WorldManager
    private lateinit var blockScanner: BlockScanner
    private lateinit var astCompiler: ASTCompiler
    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var executionEngine: ExecutionEngine
    private lateinit var modeManager: ModeManagerImpl

    @BeforeEach
    fun setup() {
        inventoryManager = mockk(relaxed = true)
        worldManager = mockk(relaxed = true)
        blockScanner = mockk(relaxed = true)
        astCompiler = mockk(relaxed = true)
        eventDispatcher = mockk(relaxed = true)
        executionEngine = mockk(relaxed = true)

        // Default: scanner returns empty code lines, compiler returns success with no scripts
        every { blockScanner.scanCodingZone() } returns emptyList()
        every { astCompiler.compile(any()) } returns CompilationResult(emptyList(), emptyList())
        // Default: no worlds loaded (avoids ClassCastException from relaxed mock on Pair<World,World>)
        every { worldManager.getLoadedWorlds(any()) } returns null

        modeManager = ModeManagerImpl(
            inventoryManager = inventoryManager,
            worldManager = worldManager,
            blockScannerFactory = { blockScanner },
            astCompiler = astCompiler,
            eventDispatcher = eventDispatcher,
            executionEngine = executionEngine
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makePlot(id: UUID = UUID.randomUUID()): Plot = Plot(
        id = id,
        owner = UUID.randomUUID(),
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

    // -------------------------------------------------------------------------
    // Inventory save on mode switch —  2.8
    // -------------------------------------------------------------------------

    @Test
    fun `switchMode saves inventory for the old mode before switching`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        // Start in BUILD (default), switch to DEV
        modeManager.switchMode(player, plot, PlotMode.DEV)

        coVerify(exactly = 1) { inventoryManager.saveInventory(player, plot.id, PlotMode.BUILD) }
    }

    @Test
    fun `switchMode saves DEV inventory when switching from DEV to PLAY`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        // First switch to DEV
        modeManager.switchMode(player, plot, PlotMode.DEV)
        clearMocks(inventoryManager, answers = false, recordedCalls = true)

        // Now switch from DEV to PLAY
        modeManager.switchMode(player, plot, PlotMode.PLAY)

        coVerify(exactly = 1) { inventoryManager.saveInventory(player, plot.id, PlotMode.DEV) }
    }

    @Test
    fun `switchMode saves PLAY inventory when switching from PLAY to BUILD`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        // Get into PLAY mode first
        modeManager.switchMode(player, plot, PlotMode.PLAY)
        clearMocks(inventoryManager, answers = false, recordedCalls = true)

        // Switch from PLAY to BUILD
        modeManager.switchMode(player, plot, PlotMode.BUILD)

        coVerify(exactly = 1) { inventoryManager.saveInventory(player, plot.id, PlotMode.PLAY) }
    }

    @Test
    fun `switchMode does not save inventory when mode is unchanged`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        // Default is BUILD, switching to BUILD again should be a no-op
        modeManager.switchMode(player, plot, PlotMode.BUILD)

        coVerify(exactly = 0) { inventoryManager.saveInventory(any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Inventory restore on mode switch —  2.9
    // -------------------------------------------------------------------------

    @Test
    fun `switchMode restores inventory for the target mode after switching`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        modeManager.switchMode(player, plot, PlotMode.DEV)

        coVerify(exactly = 1) { inventoryManager.loadInventory(player, plot.id, PlotMode.DEV) }
    }

    @Test
    fun `switchMode restores PLAY inventory when switching to PLAY mode`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        modeManager.switchMode(player, plot, PlotMode.PLAY)

        coVerify(exactly = 1) { inventoryManager.loadInventory(player, plot.id, PlotMode.PLAY) }
    }

    @Test
    fun `switchMode restores BUILD inventory when switching to BUILD mode`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        // Go to DEV first, then back to BUILD
        modeManager.switchMode(player, plot, PlotMode.DEV)
        clearMocks(inventoryManager, answers = false, recordedCalls = true)

        modeManager.switchMode(player, plot, PlotMode.BUILD)

        coVerify(exactly = 1) { inventoryManager.loadInventory(player, plot.id, PlotMode.BUILD) }
    }

    @Test
    fun `switchMode saves old inventory before restoring new inventory`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        val callOrder = mutableListOf<String>()
        coEvery { inventoryManager.saveInventory(any(), any(), any()) } answers { callOrder.add("save") }
        coEvery { inventoryManager.loadInventory(any(), any(), any()) } answers { callOrder.add("load") }

        modeManager.switchMode(player, plot, PlotMode.DEV)

        assertEquals(listOf("save", "load"), callOrder,
            "Inventory must be saved before the new mode's inventory is loaded (Req 2.8, 2.9)")
    }

    @Test
    fun `switchMode saves and restores correct mode-specific inventories across multiple switches`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        val savedModes = mutableListOf<PlotMode>()
        val loadedModes = mutableListOf<PlotMode>()
        coEvery { inventoryManager.saveInventory(any(), any(), capture(savedModes)) } just Runs
        coEvery { inventoryManager.loadInventory(any(), any(), capture(loadedModes)) } just Runs

        // BUILD → DEV → PLAY → BUILD
        modeManager.switchMode(player, plot, PlotMode.DEV)
        modeManager.switchMode(player, plot, PlotMode.PLAY)
        modeManager.switchMode(player, plot, PlotMode.BUILD)

        assertEquals(listOf(PlotMode.BUILD, PlotMode.DEV, PlotMode.PLAY), savedModes,
            "Should save BUILD, DEV, PLAY inventories in order")
        assertEquals(listOf(PlotMode.DEV, PlotMode.PLAY, PlotMode.BUILD), loadedModes,
            "Should load DEV, PLAY, BUILD inventories in order")
    }

    // -------------------------------------------------------------------------
    // Script compilation trigger —  2.6 / 5.1
    // -------------------------------------------------------------------------

    @Test
    fun `switchMode triggers block scan when switching to PLAY mode`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        modeManager.switchMode(player, plot, PlotMode.PLAY)

        verify(exactly = 1) { blockScanner.scanCodingZone() }
    }

    @Test
    fun `switchMode triggers AST compilation when switching to PLAY mode`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()
        val codeLines = listOf(mockk<CodeLine>(relaxed = true))
        every { blockScanner.scanCodingZone() } returns codeLines

        modeManager.switchMode(player, plot, PlotMode.PLAY)

        verify(exactly = 1) { astCompiler.compile(codeLines) }
    }

    @Test
    fun `switchMode does not trigger compilation when switching to BUILD mode`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        // Start in DEV, switch to BUILD
        modeManager.switchMode(player, plot, PlotMode.DEV)
        modeManager.switchMode(player, plot, PlotMode.BUILD)

        verify(exactly = 0) { astCompiler.compile(any()) }
    }

    @Test
    fun `switchMode does not trigger compilation when switching to DEV mode`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        modeManager.switchMode(player, plot, PlotMode.DEV)

        verify(exactly = 0) { astCompiler.compile(any()) }
    }

    @Test
    fun `switchMode registers compiled scripts with EventDispatcher on successful compilation`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()
        val scripts = listOf(makeScript(), makeScript())
        every { astCompiler.compile(any()) } returns CompilationResult(scripts, emptyList())

        modeManager.switchMode(player, plot, PlotMode.PLAY)

        verify(exactly = 1) { eventDispatcher.registerScripts(plot.id, scripts) }
    }

    @Test
    fun `switchMode does not register scripts when compilation has errors`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()
        val error = CompilationError(mockk(relaxed = true), "Unknown block")
        every { astCompiler.compile(any()) } returns CompilationResult(emptyList(), listOf(error))

        modeManager.switchMode(player, plot, PlotMode.PLAY)

        verify(exactly = 0) { eventDispatcher.registerScripts(any(), any()) }
    }

    @Test
    fun `switchMode reverts to DEV mode when compilation fails`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()
        val error = CompilationError(mockk(relaxed = true), "Unknown block")
        every { astCompiler.compile(any()) } returns CompilationResult(emptyList(), listOf(error))

        // Start in DEV, attempt to switch to PLAY
        modeManager.switchMode(player, plot, PlotMode.DEV)
        modeManager.switchMode(player, plot, PlotMode.PLAY)

        // Should remain in DEV after failed compilation
        assertEquals(PlotMode.DEV, modeManager.getCurrentMode(player, plot),
            "Mode should revert to DEV when compilation fails (Req 23.4)")
    }

    // -------------------------------------------------------------------------
    // Coroutine cancellation when leaving PLAY mode —  26.2
    // -------------------------------------------------------------------------

    @Test
    fun `switchMode cancels all executions when leaving PLAY mode for BUILD`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        // Enter PLAY mode
        modeManager.switchMode(player, plot, PlotMode.PLAY)
        // Leave PLAY mode
        modeManager.switchMode(player, plot, PlotMode.BUILD)

        verify(exactly = 1) { executionEngine.cancelAllExecutions(plot.id) }
    }

    @Test
    fun `switchMode cancels all executions when leaving PLAY mode for DEV`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        modeManager.switchMode(player, plot, PlotMode.PLAY)
        modeManager.switchMode(player, plot, PlotMode.DEV)

        verify(exactly = 1) { executionEngine.cancelAllExecutions(plot.id) }
    }

    @Test
    fun `switchMode cancels executions for the correct plot ID`() = runTest {
        val player = mockPlayer()
        val plotA = makePlot()
        val plotB = makePlot()

        modeManager.switchMode(player, plotA, PlotMode.PLAY)
        modeManager.switchMode(player, plotA, PlotMode.BUILD)

        verify(exactly = 1) { executionEngine.cancelAllExecutions(plotA.id) }
        verify(exactly = 0) { executionEngine.cancelAllExecutions(plotB.id) }
    }

    @Test
    fun `switchMode does not cancel executions when switching between non-PLAY modes`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        // BUILD → DEV (no PLAY involved)
        modeManager.switchMode(player, plot, PlotMode.DEV)
        modeManager.switchMode(player, plot, PlotMode.BUILD)

        verify(exactly = 0) { executionEngine.cancelAllExecutions(any()) }
    }

    @Test
    fun `switchMode unregisters scripts from EventDispatcher when leaving PLAY mode`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        modeManager.switchMode(player, plot, PlotMode.PLAY)
        modeManager.switchMode(player, plot, PlotMode.BUILD)

        verify(exactly = 1) { eventDispatcher.unregisterScripts(plot.id) }
    }

    @Test
    fun `switchMode cancels executions before restoring inventory for new mode`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        val callOrder = mutableListOf<String>()
        every { executionEngine.cancelAllExecutions(any()) } answers { callOrder.add("cancel") }
        coEvery { inventoryManager.loadInventory(any(), any(), any()) } answers { callOrder.add("load") }

        modeManager.switchMode(player, plot, PlotMode.PLAY)
        callOrder.clear()

        modeManager.switchMode(player, plot, PlotMode.BUILD)

        val cancelIdx = callOrder.indexOf("cancel")
        val loadIdx = callOrder.indexOf("load")
        assert(cancelIdx >= 0) { "cancelAllExecutions should be called" }
        assert(loadIdx >= 0) { "loadInventory should be called" }
        assert(cancelIdx < loadIdx) { "Executions must be cancelled before inventory is restored" }
    }

    // -------------------------------------------------------------------------
    // getCurrentMode — default and after switch
    // -------------------------------------------------------------------------

    @Test
    fun `getCurrentMode returns BUILD by default`() {
        val player = mockPlayer()
        val plot = makePlot()

        assertEquals(PlotMode.BUILD, modeManager.getCurrentMode(player, plot))
    }

    @Test
    fun `getCurrentMode reflects the mode after a successful switch`() = runTest {
        val player = mockPlayer()
        val plot = makePlot()

        modeManager.switchMode(player, plot, PlotMode.DEV)

        assertEquals(PlotMode.DEV, modeManager.getCurrentMode(player, plot))
    }

    @Test
    fun `getCurrentMode is independent per player`() = runTest {
        val playerA = mockPlayer()
        val playerB = mockPlayer()
        val plot = makePlot()

        modeManager.switchMode(playerA, plot, PlotMode.DEV)

        assertEquals(PlotMode.DEV, modeManager.getCurrentMode(playerA, plot))
        assertEquals(PlotMode.BUILD, modeManager.getCurrentMode(playerB, plot),
            "Player B's mode should be unaffected by Player A's switch")
    }

    @Test
    fun `getCurrentMode is independent per plot`() = runTest {
        val player = mockPlayer()
        val plotA = makePlot()
        val plotB = makePlot()

        modeManager.switchMode(player, plotA, PlotMode.DEV)

        assertEquals(PlotMode.DEV, modeManager.getCurrentMode(player, plotA))
        assertEquals(PlotMode.BUILD, modeManager.getCurrentMode(player, plotB),
            "Plot B's mode should be unaffected by Plot A's switch")
    }
}
