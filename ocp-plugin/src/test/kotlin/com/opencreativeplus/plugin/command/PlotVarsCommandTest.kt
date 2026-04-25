package com.opencreativeplus.plugin.command

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotSettings
import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.core.watchdog.TPSMonitor
import com.opencreativeplus.plugin.mode.ModeManagerImpl
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for the `/plot vars` subcommand.
 *
 * Req 11.4: player not on own plot → error message, no GUI opened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlotVarsCommandTest {

    private lateinit var plotManager: PlotManagerImpl
    private lateinit var modeManager: ModeManagerImpl
    private lateinit var tpsMonitor: TPSMonitor
    private lateinit var variableManager: VariableManager
    private lateinit var plugin: Plugin
    private lateinit var plotCommands: PlotCommands

    @BeforeEach
    fun setup() {
        plotManager = mockk(relaxed = true)
        modeManager = mockk(relaxed = true)
        tpsMonitor = mockk(relaxed = true)
        variableManager = mockk(relaxed = true)
        plugin = mockk(relaxed = true)

        val testScope = kotlinx.coroutines.test.TestScope(UnconfinedTestDispatcher())
        plotCommands = PlotCommands(
            plotManager, modeManager, tpsMonitor, testScope,
            variableManager = variableManager, plugin = plugin
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makePlot(owner: UUID = UUID.randomUUID()): Plot = Plot(
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
        trustedPlayers = emptySet()
    )

    private fun mockPlayer(id: UUID = UUID.randomUUID()): Player = mockk<Player>(relaxed = true) {
        every { uniqueId } returns id
    }

    private fun mockCommand(name: String): Command = mockk<Command>(relaxed = true) {
        every { getName() } returns name
    }

    // -------------------------------------------------------------------------
    // Req 11.4: player has no plot → error, no GUI
    // -------------------------------------------------------------------------

    @Test
    fun `vars command when player has no plot sends error and does not open inventory`() = runTest {
        val player = mockPlayer()
        coEvery { plotManager.getPlayerPlot(player.uniqueId) } returns null

        plotCommands.onCommand(player, mockCommand("plot"), "plot", arrayOf("vars"))

        verify { player.sendMessage(match<String> { it.contains("plot", ignoreCase = true) }) }
        verify(exactly = 0) { player.openInventory(any<Inventory>()) }
    }

    // -------------------------------------------------------------------------
    // Req 11.4: player has a plot but canEdit returns false → error, no GUI
    // -------------------------------------------------------------------------

    @Test
    fun `vars command when canEdit is false sends error and does not open inventory`() = runTest {
        val ownerId = UUID.randomUUID()
        val strangerId = UUID.randomUUID()
        val plot = makePlot(owner = ownerId)
        val stranger = mockPlayer(strangerId)

        coEvery { plotManager.getPlayerPlot(strangerId) } returns plot
        every { plotManager.canEdit(stranger, plot) } returns false

        plotCommands.onCommand(stranger, mockCommand("plot"), "plot", arrayOf("vars"))

        verify { stranger.sendMessage(match<String> { it.contains("plot", ignoreCase = true) }) }
        verify(exactly = 0) { stranger.openInventory(any<Inventory>()) }
    }
}
