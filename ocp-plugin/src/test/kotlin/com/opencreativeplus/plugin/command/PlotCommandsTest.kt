package com.opencreativeplus.plugin.command

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.api.plot.PlotSettings
import com.opencreativeplus.core.watchdog.TPSMonitor
import com.opencreativeplus.plugin.mode.ModeManagerImpl
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertTrue

/**
 * Unit tests for PlotCommands covering permission enforcement and mode switching.
 *
 * Validates: Requirements 32.2, 32.5
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlotCommandsTest {

    private lateinit var plotManager: PlotManagerImpl
    private lateinit var modeManager: ModeManagerImpl
    private lateinit var tpsMonitor: TPSMonitor
    private lateinit var plotCommands: PlotCommands

    @BeforeEach
    fun setup() {
        plotManager = mockk(relaxed = true)
        modeManager = mockk(relaxed = true)
        tpsMonitor = mockk(relaxed = true)

        // Use UnconfinedTestDispatcher so scope.launch runs eagerly in tests
        val testScope = kotlinx.coroutines.test.TestScope(UnconfinedTestDispatcher())
        plotCommands = PlotCommands(plotManager, modeManager, tpsMonitor, testScope)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makePlot(
        owner: UUID = UUID.randomUUID(),
        trustedPlayers: Set<UUID> = emptySet()
    ): Plot = Plot(
        id = UUID.randomUUID(),
        owner = owner,
        name = "Test Plot",
        description = "",
        mainWorldName = "main",
        devWorldName = "dev",
        createdAt = 0L,
        updatedAt = 0L,
        settings = PlotSettings(),
        metadata = PlotMetadata(),
        trustedPlayers = trustedPlayers
    )

    private fun mockPlayer(id: UUID = UUID.randomUUID()): Player = mockk<Player>(relaxed = true) {
        every { uniqueId } returns id
    }

    private fun mockCommand(name: String): Command = mockk<Command>(relaxed = true) {
        every { getName() } returns name
    }

    // -------------------------------------------------------------------------
    // Requirement 32.5: Non-owner denied /dev and /build with a message
    // -------------------------------------------------------------------------

    @Test
    fun `non-owner is denied dev command and receives denial message`() = runTest {
        val ownerId = UUID.randomUUID()
        val strangerId = UUID.randomUUID()
        val plot = makePlot(owner = ownerId)
        val stranger = mockPlayer(strangerId)

        coEvery { plotManager.getPlayerPlot(strangerId) } returns plot
        every { plotManager.canEdit(stranger, plot) } returns false

        plotCommands.onCommand(stranger, mockCommand("dev"), "dev", emptyArray())

        // Requirement 32.5: denial message must be sent
        verify { stranger.sendMessage(match<String> { it.contains("permission", ignoreCase = true) }) }
        // Mode switch must NOT be triggered
        coVerify(exactly = 0) { modeManager.switchMode(any(), any(), any()) }
    }

    @Test
    fun `non-owner is denied build command and receives denial message`() = runTest {
        val ownerId = UUID.randomUUID()
        val strangerId = UUID.randomUUID()
        val plot = makePlot(owner = ownerId)
        val stranger = mockPlayer(strangerId)

        coEvery { plotManager.getPlayerPlot(strangerId) } returns plot
        every { plotManager.canEdit(stranger, plot) } returns false

        plotCommands.onCommand(stranger, mockCommand("build"), "build", emptyArray())

        verify { stranger.sendMessage(match<String> { it.contains("permission", ignoreCase = true) }) }
        coVerify(exactly = 0) { modeManager.switchMode(any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Requirement 32.2: Plot owner can execute /dev and /build
    // -------------------------------------------------------------------------

    @Test
    fun `plot owner can execute dev command and mode switch is triggered`() = runTest {
        val ownerId = UUID.randomUUID()
        val plot = makePlot(owner = ownerId)
        val owner = mockPlayer(ownerId)

        coEvery { plotManager.getPlayerPlot(ownerId) } returns plot
        every { plotManager.canEdit(owner, plot) } returns true

        plotCommands.onCommand(owner, mockCommand("dev"), "dev", emptyArray())

        coVerify(exactly = 1) { modeManager.switchMode(owner, plot, PlotMode.DEV) }
    }

    @Test
    fun `plot owner can execute build command and mode switch is triggered`() = runTest {
        val ownerId = UUID.randomUUID()
        val plot = makePlot(owner = ownerId)
        val owner = mockPlayer(ownerId)

        coEvery { plotManager.getPlayerPlot(ownerId) } returns plot
        every { plotManager.canEdit(owner, plot) } returns true

        plotCommands.onCommand(owner, mockCommand("build"), "build", emptyArray())

        coVerify(exactly = 1) { modeManager.switchMode(owner, plot, PlotMode.BUILD) }
    }

    // -------------------------------------------------------------------------
    // Mode switching via /build, /dev, /play triggers correct mode
    // -------------------------------------------------------------------------

    @Test
    fun `play command triggers PLAY mode switch`() = runTest {
        val ownerId = UUID.randomUUID()
        val plot = makePlot(owner = ownerId)
        val owner = mockPlayer(ownerId)

        coEvery { plotManager.getPlayerPlot(ownerId) } returns plot
        every { plotManager.canEdit(owner, plot) } returns true

        plotCommands.onCommand(owner, mockCommand("play"), "play", emptyArray())

        coVerify(exactly = 1) { modeManager.switchMode(owner, plot, PlotMode.PLAY) }
    }

    @Test
    fun `dev command triggers DEV mode switch`() = runTest {
        val ownerId = UUID.randomUUID()
        val plot = makePlot(owner = ownerId)
        val owner = mockPlayer(ownerId)

        coEvery { plotManager.getPlayerPlot(ownerId) } returns plot
        every { plotManager.canEdit(owner, plot) } returns true

        plotCommands.onCommand(owner, mockCommand("dev"), "dev", emptyArray())

        coVerify(exactly = 1) { modeManager.switchMode(owner, plot, PlotMode.DEV) }
    }

    @Test
    fun `build command triggers BUILD mode switch`() = runTest {
        val ownerId = UUID.randomUUID()
        val plot = makePlot(owner = ownerId)
        val owner = mockPlayer(ownerId)

        coEvery { plotManager.getPlayerPlot(ownerId) } returns plot
        every { plotManager.canEdit(owner, plot) } returns true

        plotCommands.onCommand(owner, mockCommand("build"), "build", emptyArray())

        coVerify(exactly = 1) { modeManager.switchMode(owner, plot, PlotMode.BUILD) }
    }

    // -------------------------------------------------------------------------
    // Trusted players with edit permissions can use /dev and /build
    // -------------------------------------------------------------------------

    @Test
    fun `trusted player can execute dev command`() = runTest {
        val ownerId = UUID.randomUUID()
        val trustedId = UUID.randomUUID()
        val plot = makePlot(owner = ownerId, trustedPlayers = setOf(trustedId))
        val trusted = mockPlayer(trustedId)

        coEvery { plotManager.getPlayerPlot(trustedId) } returns plot
        every { plotManager.canEdit(trusted, plot) } returns true

        plotCommands.onCommand(trusted, mockCommand("dev"), "dev", emptyArray())

        coVerify(exactly = 1) { modeManager.switchMode(trusted, plot, PlotMode.DEV) }
    }

    @Test
    fun `trusted player can execute build command`() = runTest {
        val ownerId = UUID.randomUUID()
        val trustedId = UUID.randomUUID()
        val plot = makePlot(owner = ownerId, trustedPlayers = setOf(trustedId))
        val trusted = mockPlayer(trustedId)

        coEvery { plotManager.getPlayerPlot(trustedId) } returns plot
        every { plotManager.canEdit(trusted, plot) } returns true

        plotCommands.onCommand(trusted, mockCommand("build"), "build", emptyArray())

        coVerify(exactly = 1) { modeManager.switchMode(trusted, plot, PlotMode.BUILD) }
    }

    // -------------------------------------------------------------------------
    // Edge cases: no plot, non-player sender
    // -------------------------------------------------------------------------

    @Test
    fun `player without a plot receives informational message and no mode switch occurs`() = runTest {
        val playerId = UUID.randomUUID()
        val player = mockPlayer(playerId)

        coEvery { plotManager.getPlayerPlot(playerId) } returns null

        plotCommands.onCommand(player, mockCommand("dev"), "dev", emptyArray())

        coVerify(exactly = 0) { modeManager.switchMode(any(), any(), any()) }
        verify { player.sendMessage(match<String> { it.contains("plot", ignoreCase = true) }) }
    }

    @Test
    fun `non-player sender receives player-only message`() {
        val consoleSender = mockk<org.bukkit.command.ConsoleCommandSender>(relaxed = true)

        plotCommands.onCommand(consoleSender, mockCommand("dev"), "dev", emptyArray())

        verify { consoleSender.sendMessage(match<String> { it.contains("Player only", ignoreCase = true) }) }
        coVerify(exactly = 0) { modeManager.switchMode(any(), any(), any()) }
    }

    @Test
    fun `onCommand always returns true`() {
        val player = mockPlayer()
        coEvery { plotManager.getPlayerPlot(any()) } returns null

        val result = plotCommands.onCommand(player, mockCommand("dev"), "dev", emptyArray())

        assertTrue(result)
    }
}
