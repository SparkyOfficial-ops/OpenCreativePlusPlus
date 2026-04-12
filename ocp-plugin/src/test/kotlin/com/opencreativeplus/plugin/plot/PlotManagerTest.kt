package com.opencreativeplus.plugin.plot

import com.opencreativeplus.api.plot.ModeManager
import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.api.plot.PlotSettings
import com.opencreativeplus.core.database.PlotPersistence
import com.opencreativeplus.plugin.world.WorldManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.bukkit.entity.Player
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for PlotManagerImpl covering plot creation, loading, permissions, and settings.
 *
32.5, 13.7
 */
class PlotManagerTest {

    private lateinit var plotPersistence: PlotPersistence
    private lateinit var worldManager: WorldManager
    private lateinit var modeManager: ModeManager
    private lateinit var plotManager: PlotManagerImpl

    @BeforeEach
    fun setup() {
        plotPersistence = mockk(relaxed = true)
        worldManager = mockk(relaxed = true)
        modeManager = mockk(relaxed = true)
        plotManager = PlotManagerImpl(plotPersistence, worldManager, modeManager)
    }

    // -------------------------------------------------------------------------
    // Plot creation —  32.1
    // -------------------------------------------------------------------------

    @Test
    fun `createPlot assigns creator as owner`() = runTest {
        val ownerId = UUID.randomUUID()

        val plot = plotManager.createPlot(ownerId)

        assertEquals(ownerId, plot.owner, "Creator should be assigned as plot owner")
    }

    @Test
    fun `createPlot persists plot to database`() = runTest {
        val ownerId = UUID.randomUUID()

        plotManager.createPlot(ownerId)

        coVerify(exactly = 1) { plotPersistence.createPlot(any()) }
    }

    @Test
    fun `createPlot caches plot in memory for immediate retrieval`() = runTest {
        val ownerId = UUID.randomUUID()

        val created = plotManager.createPlot(ownerId)
        val retrieved = plotManager.getPlot(created.id)

        assertNotNull(retrieved)
        assertEquals(created.id, retrieved.id)
    }

    @Test
    fun `createPlot indexes plot by owner for getPlayerPlot lookup`() = runTest {
        val ownerId = UUID.randomUUID()

        val created = plotManager.createPlot(ownerId)
        val retrieved = plotManager.getPlayerPlot(ownerId)

        assertNotNull(retrieved)
        assertEquals(created.id, retrieved.id)
    }

    // -------------------------------------------------------------------------
    // Plot loading —  13.7
    // -------------------------------------------------------------------------

    @Test
    fun `loadPlot restores saved settings from database`() = runTest {
        val savedSettings = PlotSettings(
            biome = "DESERT",
            timeOfDay = 18000L,
            pvpEnabled = true,
            mobSpawningEnabled = true,
            worldBorderSize = 2048
        )
        val plotId = UUID.randomUUID()
        val storedPlot = makePlot(plotId, settings = savedSettings)
        coEvery { plotPersistence.loadPlot(plotId) } returns storedPlot

        val loaded = plotManager.loadPlot(plotId)

        //  13.7: all saved settings must be restored on load
        assertEquals("DESERT", loaded.settings.biome)
        assertEquals(18000L, loaded.settings.timeOfDay)
        assertEquals(true, loaded.settings.pvpEnabled)
        assertEquals(true, loaded.settings.mobSpawningEnabled)
        assertEquals(2048, loaded.settings.worldBorderSize)
    }

    @Test
    fun `loadPlot restores default settings when none were customised`() = runTest {
        val plotId = UUID.randomUUID()
        val storedPlot = makePlot(plotId, settings = PlotSettings())
        coEvery { plotPersistence.loadPlot(plotId) } returns storedPlot

        val loaded = plotManager.loadPlot(plotId)

        assertEquals(PlotSettings(), loaded.settings)
    }

    @Test
    fun `loadPlot caches plot in memory after loading`() = runTest {
        val plotId = UUID.randomUUID()
        val storedPlot = makePlot(plotId)
        coEvery { plotPersistence.loadPlot(plotId) } returns storedPlot

        plotManager.loadPlot(plotId)
        val cached = plotManager.getPlot(plotId)

        assertNotNull(cached)
        assertEquals(plotId, cached.id)
    }

    @Test
    fun `loadPlot indexes plot by owner for getPlayerPlot lookup`() = runTest {
        val ownerId = UUID.randomUUID()
        val plotId = UUID.randomUUID()
        val storedPlot = makePlot(plotId, owner = ownerId)
        coEvery { plotPersistence.loadPlot(plotId) } returns storedPlot

        plotManager.loadPlot(plotId)
        val retrieved = plotManager.getPlayerPlot(ownerId)

        assertNotNull(retrieved)
        assertEquals(plotId, retrieved.id)
    }

    // -------------------------------------------------------------------------
    // Permission enforcement —  32.5
    // -------------------------------------------------------------------------

    @Test
    fun `canEdit returns true for plot owner`() = runTest {
        val ownerId = UUID.randomUUID()
        val plot = makePlot(owner = ownerId)
        val player = mockPlayer(ownerId)

        assertTrue(plotManager.canEdit(player, plot), "Owner should always be able to edit")
    }

    @Test
    fun `canEdit returns false for non-owner without trust`() = runTest {
        val ownerId = UUID.randomUUID()
        val strangerId = UUID.randomUUID()
        val plot = makePlot(owner = ownerId, trustedPlayers = emptySet())
        val stranger = mockPlayer(strangerId)

        //  32.5: non-owner must be denied dev/build mode access
        assertFalse(plotManager.canEdit(stranger, plot), "Non-owner without trust should be denied")
    }

    @Test
    fun `canEdit returns true for trusted player`() = runTest {
        val ownerId = UUID.randomUUID()
        val trustedId = UUID.randomUUID()
        val plot = makePlot(owner = ownerId, trustedPlayers = setOf(trustedId))
        val trusted = mockPlayer(trustedId)

        //  32.4: trusted players should have edit access
        assertTrue(plotManager.canEdit(trusted, plot), "Trusted player should be able to edit")
    }

    @Test
    fun `canEdit returns false for player who is neither owner nor trusted`() = runTest {
        val ownerId = UUID.randomUUID()
        val trustedId = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        val plot = makePlot(owner = ownerId, trustedPlayers = setOf(trustedId))
        val other = mockPlayer(otherId)

        assertFalse(plotManager.canEdit(other, plot), "Untrusted non-owner should be denied")
    }

    // -------------------------------------------------------------------------
    // Trusted player management —  32.4
    // -------------------------------------------------------------------------

    @Test
    fun `addTrustedPlayer grants edit access to new player`() = runTest {
        val ownerId = UUID.randomUUID()
        val newTrustedId = UUID.randomUUID()
        val created = plotManager.createPlot(ownerId)

        plotManager.addTrustedPlayer(created.id, newTrustedId)

        val updated = plotManager.getPlot(created.id)!!
        val trustedPlayer = mockPlayer(newTrustedId)
        assertTrue(plotManager.canEdit(trustedPlayer, updated))
    }

    @Test
    fun `removeTrustedPlayer revokes edit access`() = runTest {
        val ownerId = UUID.randomUUID()
        val trustedId = UUID.randomUUID()
        val created = plotManager.createPlot(ownerId)
        plotManager.addTrustedPlayer(created.id, trustedId)

        plotManager.removeTrustedPlayer(created.id, trustedId)

        val updated = plotManager.getPlot(created.id)!!
        val removedPlayer = mockPlayer(trustedId)
        assertFalse(plotManager.canEdit(removedPlayer, updated))
    }

    // -------------------------------------------------------------------------
    // Settings persistence —  13.6
    // -------------------------------------------------------------------------

    @Test
    fun `updateSettings persists new settings to database`() = runTest {
        val ownerId = UUID.randomUUID()
        val created = plotManager.createPlot(ownerId)
        val newSettings = PlotSettings(biome = "JUNGLE", pvpEnabled = true)
        // No loaded worlds — updateSettings should still persist without applying to world
        every { worldManager.getLoadedWorlds(created.id) } returns null

        plotManager.updateSettings(created.id, newSettings)

        coVerify { plotPersistence.updatePlot(match { it.settings == newSettings }) }
    }

    @Test
    fun `updateSettings updates in-memory plot state`() = runTest {
        val ownerId = UUID.randomUUID()
        val created = plotManager.createPlot(ownerId)
        val newSettings = PlotSettings(biome = "TAIGA", timeOfDay = 0L, mobSpawningEnabled = true)
        every { worldManager.getLoadedWorlds(created.id) } returns null

        plotManager.updateSettings(created.id, newSettings)

        val updated = plotManager.getPlot(created.id)!!
        assertEquals(newSettings, updated.settings)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makePlot(
        id: UUID = UUID.randomUUID(),
        owner: UUID = UUID.randomUUID(),
        settings: PlotSettings = PlotSettings(),
        trustedPlayers: Set<UUID> = emptySet()
    ): Plot = Plot(
        id = id,
        owner = owner,
        name = "Test Plot",
        description = "",
        mainWorldName = "${id}_main",
        devWorldName = "${id}_dev",
        createdAt = 0L,
        updatedAt = 0L,
        settings = settings,
        metadata = PlotMetadata(),
        trustedPlayers = trustedPlayers
    )

    private fun mockPlayer(id: UUID): Player = mockk<Player> {
        every { uniqueId } returns id
    }
}
