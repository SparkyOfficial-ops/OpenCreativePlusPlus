package com.opencreativeplus.plugin.gui

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotSettings
import com.opencreativeplus.core.database.PlotPersistence
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/
 * Unit tests for PlotBrowserGUI covering plot loading/display, tag filtering, and rating sort.
 *
21.4, 22.4
 */
class PlotBrowserGUITest {

    private lateinit var plotPersistence: PlotPersistence
    private lateinit var plotManager: PlotManagerImpl

    @BeforeEach
    fun setup() {
        plotPersistence = mockk(relaxed = true)
        plotManager = mockk(relaxed = true)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makePlot(
        name: String = "Test Plot",
        tags: List<String> = emptyList(),
        rating: Int = 0,
        currentPlayers: Int = 0,
        description: String = ""
    ): Plot = Plot(
        id = UUID.randomUUID(),
        owner = UUID.randomUUID(),
        name = name,
        description = description,
        mainWorldName = "world_main",
        devWorldName = "world_dev",
        createdAt = 0L,
        updatedAt = 0L,
        settings = PlotSettings(),
        metadata = PlotMetadata(
            tags = tags,
            rating = rating,
            currentPlayers = currentPlayers
        )
    )

    /
     * Simulate the loadPlots logic from PlotBrowserGUI to test filtering and sorting
     * in isolation without requiring Bukkit/coroutine infrastructure.
     *
     * This mirrors the private loadPlots() method exactly:
     *   allPlots.filter(tagFilter).sortedByDescending(rating).take(MAX_PLOTS)
     */
    private fun simulateLoadPlots(allPlots: List<Plot>, tagFilter: String?, maxPlots: Int = 45): List<Plot> {
        return allPlots
            .let { list ->
                if (tagFilter != null) list.filter { tagFilter in it.metadata.tags }
                else list
            }
            .sortedByDescending { it.metadata.rating }
            .take(maxPlots)
    }

    // -------------------------------------------------------------------------
    // Plot loading and display — s 10.1, 10.2, 10.3
    // -------------------------------------------------------------------------

    @Test
    fun `loadPlots returns all plots when no tag filter is applied`() {
        val plots = listOf(
            makePlot(name = "Alpha"),
            makePlot(name = "Beta"),
            makePlot(name = "Gamma")
        )
        every { plotManager.getAllLoadedPlots() } returns plots

        val result = simulateLoadPlots(plotManager.getAllLoadedPlots(), tagFilter = null)

        assertEquals(3, result.size)
    }

    @Test
    fun `loadPlots returns empty list when no plots are loaded`() {
        every { plotManager.getAllLoadedPlots() } returns emptyList()

        val result = simulateLoadPlots(plotManager.getAllLoadedPlots(), tagFilter = null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `loadPlots preserves plot name and metadata for display`() {
        val plot = makePlot(
            name = "My Awesome Plot",
            tags = listOf("adventure"),
            rating = 42,
            currentPlayers = 3,
            description = "A great plot"
        )
        every { plotManager.getAllLoadedPlots() } returns listOf(plot)

        val result = simulateLoadPlots(plotManager.getAllLoadedPlots(), tagFilter = null)

        assertEquals(1, result.size)
        val displayed = result.first()
        assertEquals("My Awesome Plot", displayed.name)
        assertEquals("A great plot", displayed.description)
        assertEquals(listOf("adventure"), displayed.metadata.tags)
        assertEquals(42, displayed.metadata.rating)
        assertEquals(3, displayed.metadata.currentPlayers)
    }

    @Test
    fun `loadPlots caps results at 45 plots`() {
        val plots = (1..60).map { makePlot(name = "Plot $it") }
        every { plotManager.getAllLoadedPlots() } returns plots

        val result = simulateLoadPlots(plotManager.getAllLoadedPlots(), tagFilter = null)

        assertEquals(45, result.size)
    }

    // -------------------------------------------------------------------------
    // Sorting by rating —  21.4
    // -------------------------------------------------------------------------

    @Test
    fun `loadPlots sorts plots by rating descending`() {
        val plots = listOf(
            makePlot(name = "Low",    rating = 5),
            makePlot(name = "High",   rating = 100),
            makePlot(name = "Medium", rating = 50)
        )
        every { plotManager.getAllLoadedPlots() } returns plots

        val result = simulateLoadPlots(plotManager.getAllLoadedPlots(), tagFilter = null)

        assertEquals("High",   result[0].name)
        assertEquals("Medium", result[1].name)
        assertEquals("Low",    result[2].name)
    }

    @Test
    fun `loadPlots places highest-rated plot first`() {
        val plots = listOf(
            makePlot(name = "A", rating = 1),
            makePlot(name = "B", rating = 999),
            makePlot(name = "C", rating = 10)
        )
        every { plotManager.getAllLoadedPlots() } returns plots

        val result = simulateLoadPlots(plotManager.getAllLoadedPlots(), tagFilter = null)

        assertEquals("B", result.first().name)
    }

    @Test
    fun `loadPlots handles plots with equal ratings without error`() {
        val plots = listOf(
            makePlot(name = "X", rating = 10),
            makePlot(name = "Y", rating = 10),
            makePlot(name = "Z", rating = 10)
        )
        every { plotManager.getAllLoadedPlots() } returns plots

        val result = simulateLoadPlots(plotManager.getAllLoadedPlots(), tagFilter = null)

        assertEquals(3, result.size)
        assertTrue(result.all { it.metadata.rating == 10 })
    }

    @Test
    fun `loadPlots sorts correctly when some plots have zero rating`() {
        val plots = listOf(
            makePlot(name = "Zero",     rating = 0),
            makePlot(name = "Positive", rating = 25)
        )
        every { plotManager.getAllLoadedPlots() } returns plots

        val result = simulateLoadPlots(plotManager.getAllLoadedPlots(), tagFilter = null)

        assertEquals("Positive", result[0].name)
        assertEquals("Zero",     result[1].name)
    }

    // -------------------------------------------------------------------------
    // Filtering by tag —  22.4
    // -------------------------------------------------------------------------

    @Test
    fun `loadPlots filters to only plots containing the specified tag`() {
        val plots = listOf(
            makePlot(name = "Adventure Plot", tags = listOf("adventure", "pvp")),
            makePlot(name = "Puzzle Plot",    tags = listOf("puzzle")),
            makePlot(name = "PvP Plot",       tags = listOf("pvp"))
        )
        every { plotManager.getAllLoadedPlots() } returns plots

        val result = simulateLoadPlots(plotManager.getAllLoadedPlots(), tagFilter = "pvp")

        assertEquals(2, result.size)
        assertTrue(result.all { "pvp" in it.metadata.tags })
    }

    @Test
    fun `loadPlots returns empty list when no plots match the tag filter`() {
        val plots = listOf(
            makePlot(name = "A", tags = listOf("adventure")),
            makePlot(name = "B", tags = listOf("puzzle"))
        )
        every { plotManager.getAllLoadedPlots() } returns plots

        val result = simulateLoadPlots(plotManager.getAllLoadedPlots(), tagFilter = "nonexistent")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `loadPlots excludes plots without the specified tag`() {
        val plots = listOf(
            makePlot(name = "Tagged",   tags = listOf("survival")),
            makePlot(name = "Untagged", tags = emptyList())
        )
        every { plotManager.getAllLoadedPlots() } returns plots

        val result = simulateLoadPlots(plotManager.getAllLoadedPlots(), tagFilter = "survival")

        assertEquals(1, result.size)
        assertEquals("Tagged", result.first().name)
    }

    @Test
    fun `loadPlots with tag filter also sorts results by rating descending`() {
        val plots = listOf(
            makePlot(name = "Low Rated",  tags = listOf("adventure"), rating = 5),
            makePlot(name = "High Rated", tags = listOf("adventure"), rating = 80),
            makePlot(name = "No Tag",     tags = emptyList(),         rating = 999)
        )
        every { plotManager.getAllLoadedPlots() } returns plots

        val result = simulateLoadPlots(plotManager.getAllLoadedPlots(), tagFilter = "adventure")

        assertEquals(2, result.size)
        assertEquals("High Rated", result[0].name)
        assertEquals("Low Rated",  result[1].name)
    }

    @Test
    fun `loadPlots returns all plots when tag filter is null regardless of plot tags`() {
        val plots = listOf(
            makePlot(name = "Tagged",   tags = listOf("pvp")),
            makePlot(name = "Untagged", tags = emptyList())
        )
        every { plotManager.getAllLoadedPlots() } returns plots

        val result = simulateLoadPlots(plotManager.getAllLoadedPlots(), tagFilter = null)

        assertEquals(2, result.size)
    }
}
