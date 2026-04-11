package com.opencreativeplus.plugin.rating

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotSettings
import com.opencreativeplus.core.database.PlotPersistence
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.bukkit.entity.Player
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for RatingManager and TagManager.
 *
21.3, 22.5
 */
class RatingAndTaggingTest {

    private lateinit var plotPersistence: PlotPersistence
    private lateinit var ratingManager: RatingManager
    private lateinit var tagManager: TagManager

    @BeforeEach
    fun setup() {
        plotPersistence = mockk(relaxed = true)
        ratingManager = RatingManager(plotPersistence)
        tagManager = TagManager(plotPersistence)
    }

    // -------------------------------------------------------------------------
    // Duplicate rating prevention — Requirement 21.3
    // -------------------------------------------------------------------------

    @Test
    fun `ratePlot returns true and persists when player has not yet rated`() = runTest {
        val playerId = UUID.randomUUID()
        val player = mockPlayer(playerId)
        val plot = makePlot(ratedBy = emptySet())

        val result = ratingManager.ratePlot(player, plot)

        assertTrue(result, "First rating should succeed")
        coVerify(exactly = 1) { plotPersistence.addRating(plot.id, playerId) }
    }

    @Test
    fun `ratePlot returns false when player has already rated the plot`() = runTest {
        val playerId = UUID.randomUUID()
        val player = mockPlayer(playerId)
        // Plot already contains this player in ratedBy
        val plot = makePlot(ratedBy = setOf(playerId))

        val result = ratingManager.ratePlot(player, plot)

        // Requirement 21.3: duplicate rating must be prevented
        assertFalse(result, "Duplicate rating should be rejected")
    }

    @Test
    fun `ratePlot does not call persistence when player has already rated`() = runTest {
        val playerId = UUID.randomUUID()
        val player = mockPlayer(playerId)
        val plot = makePlot(ratedBy = setOf(playerId))

        ratingManager.ratePlot(player, plot)

        coVerify(exactly = 0) { plotPersistence.addRating(any(), any()) }
    }

    @Test
    fun `ratePlot allows different players to rate the same plot`() = runTest {
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val firstPlayer = mockPlayer(firstId)
        val secondPlayer = mockPlayer(secondId)
        // Only firstId has already rated
        val plot = makePlot(ratedBy = setOf(firstId))

        val firstResult = ratingManager.ratePlot(firstPlayer, plot)
        val secondResult = ratingManager.ratePlot(secondPlayer, plot)

        assertFalse(firstResult, "Already-rated player should be rejected")
        assertTrue(secondResult, "New player should be allowed to rate")
    }

    // -------------------------------------------------------------------------
    // Tag limit enforcement — Requirement 22.5
    // -------------------------------------------------------------------------

    @Test
    fun `addTag returns true when plot has fewer than 5 tags`() = runTest {
        val ownerId = UUID.randomUUID()
        val owner = mockPlayer(ownerId)
        val plot = makePlot(owner = ownerId, tags = listOf("adventure", "pvp"))

        val result = tagManager.addTag(owner, plot, "puzzle")

        assertTrue(result, "Adding a tag when under the limit should succeed")
        coVerify(exactly = 1) { plotPersistence.updatePlotMetadata(plot.id, tags = any()) }
    }

    @Test
    fun `addTag returns false when plot already has 5 tags`() = runTest {
        val ownerId = UUID.randomUUID()
        val owner = mockPlayer(ownerId)
        val plot = makePlot(
            owner = ownerId,
            tags = listOf("adventure", "pvp", "puzzle", "survival", "creative")
        )

        // Requirement 22.5: 6th tag must be rejected
        val result = tagManager.addTag(owner, plot, "horror")

        assertFalse(result, "Adding a 6th tag should be rejected")
    }

    @Test
    fun `addTag does not call persistence when tag limit is reached`() = runTest {
        val ownerId = UUID.randomUUID()
        val owner = mockPlayer(ownerId)
        val plot = makePlot(
            owner = ownerId,
            tags = listOf("t1", "t2", "t3", "t4", "t5")
        )

        tagManager.addTag(owner, plot, "t6")

        coVerify(exactly = 0) { plotPersistence.updatePlotMetadata(any(), tags = any()) }
    }

    @Test
    fun `addTag returns true when adding the 5th tag exactly at the limit`() = runTest {
        val ownerId = UUID.randomUUID()
        val owner = mockPlayer(ownerId)
        val plot = makePlot(
            owner = ownerId,
            tags = listOf("t1", "t2", "t3", "t4")
        )

        val result = tagManager.addTag(owner, plot, "t5")

        assertTrue(result, "Adding the 5th tag should be allowed")
    }

    @Test
    fun `addTag returns false when tag already exists on the plot`() = runTest {
        val ownerId = UUID.randomUUID()
        val owner = mockPlayer(ownerId)
        val plot = makePlot(owner = ownerId, tags = listOf("adventure"))

        val result = tagManager.addTag(owner, plot, "adventure")

        assertFalse(result, "Duplicate tag should be rejected")
    }

    @Test
    fun `addTag returns false when player is not the plot owner`() = runTest {
        val ownerId = UUID.randomUUID()
        val strangerId = UUID.randomUUID()
        val stranger = mockPlayer(strangerId)
        val plot = makePlot(owner = ownerId, tags = emptyList())

        val result = tagManager.addTag(stranger, plot, "adventure")

        assertFalse(result, "Non-owner should not be able to add tags")
    }

    @Test
    fun `removeTag returns true and persists when owner removes existing tag`() = runTest {
        val ownerId = UUID.randomUUID()
        val owner = mockPlayer(ownerId)
        val plot = makePlot(owner = ownerId, tags = listOf("adventure", "pvp"))

        val result = tagManager.removeTag(owner, plot, "adventure")

        assertTrue(result, "Owner should be able to remove an existing tag")
        coVerify(exactly = 1) { plotPersistence.updatePlotMetadata(plot.id, tags = any()) }
    }

    @Test
    fun `removeTag returns false when player is not the plot owner`() = runTest {
        val ownerId = UUID.randomUUID()
        val strangerId = UUID.randomUUID()
        val stranger = mockPlayer(strangerId)
        val plot = makePlot(owner = ownerId, tags = listOf("adventure"))

        val result = tagManager.removeTag(stranger, plot, "adventure")

        assertFalse(result, "Non-owner should not be able to remove tags")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makePlot(
        id: UUID = UUID.randomUUID(),
        owner: UUID = UUID.randomUUID(),
        tags: List<String> = emptyList(),
        rating: Int = 0,
        ratedBy: Set<UUID> = emptySet()
    ): Plot = Plot(
        id = id,
        owner = owner,
        name = "Test Plot",
        description = "",
        mainWorldName = "${id}_main",
        devWorldName = "${id}_dev",
        createdAt = 0L,
        updatedAt = 0L,
        settings = PlotSettings(),
        metadata = PlotMetadata(
            tags = tags,
            rating = rating,
            ratedBy = ratedBy
        )
    )

    private fun mockPlayer(id: UUID): Player = mockk<Player>(relaxed = true) {
        every { uniqueId } returns id
    }
}
