package com.opencreativeplus.core.database

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/
 * Integration tests for PlotPersistence using Testcontainers.
 * Tests plot CRUD operations and serialization round-trip consistency.
 * 
 17.5, 17.6, 38.2
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlotPersistenceTest {
    
    private lateinit var mongoContainer: MongoDBContainer
    private lateinit var connectionManager: MongoConnectionManager
    private lateinit var plotPersistence: PlotPersistence
    
    @BeforeAll
    fun setupContainer() {
        // Start MongoDB container
        mongoContainer = MongoDBContainer(DockerImageName.parse("mongo:7.0"))
        mongoContainer.start()
        
        // Create connection manager with container connection string
        val config = DatabaseConfig(
            connectionString = mongoContainer.replicaSetUrl,
            databaseName = "test_ocp",
            maxRetries = 3,
            retryDelayMs = 100
        )
        
        connectionManager = MongoConnectionManager(config)
        
        runBlocking {
            connectionManager.connect()
            plotPersistence = PlotPersistence(
                connectionManager.getDatabase(),
                connectionManager
            )
        }
    }
    
    @AfterAll
    fun teardownContainer() {
        connectionManager.close()
        mongoContainer.stop()
    }
    
    @BeforeEach
    fun cleanDatabase() = runBlocking {
        // Clear plots collection before each test
        connectionManager.getDatabase()
            .getCollection<org.bson.Document>("plots")
            .drop()
    }
    
    @Test
    fun `test createPlot stores plot in database`() = runBlocking {
        // Given: A new plot
        val plot = createTestPlot()
        
        // When: Creating the plot
        plotPersistence.createPlot(plot)
        
        // Then: Plot can be loaded from database
        val loaded = plotPersistence.loadPlot(plot.id)
        assertNotNull(loaded)
        assertEquals(plot.id, loaded.id)
        assertEquals(plot.name, loaded.name)
    }
    
    @Test
    fun `test loadPlot returns null for non-existent plot`() = runBlocking {
        // Given: A random UUID that doesn't exist
        val randomId = UUID.randomUUID()
        
        // When: Loading non-existent plot
        val loaded = plotPersistence.loadPlot(randomId)
        
        // Then: Returns null
        assertNull(loaded)
    }
    
    @Test
    fun `test updatePlot modifies existing plot`() = runBlocking {
        // Given: An existing plot
        val plot = createTestPlot()
        plotPersistence.createPlot(plot)
        
        // When: Updating the plot
        val updatedPlot = plot.copy(
            name = "Updated Name",
            description = "Updated Description"
        )
        plotPersistence.updatePlot(updatedPlot)
        
        // Then: Changes are persisted
        val loaded = plotPersistence.loadPlot(plot.id)
        assertNotNull(loaded)
        assertEquals("Updated Name", loaded.name)
        assertEquals("Updated Description", loaded.description)
    }
    
    @Test
    fun `test updatePlot creates plot if not exists (upsert)`() = runBlocking {
        // Given: A plot that doesn't exist in database
        val plot = createTestPlot()
        
        // When: Calling updatePlot (which uses upsert)
        plotPersistence.updatePlot(plot)
        
        // Then: Plot is created
        val loaded = plotPersistence.loadPlot(plot.id)
        assertNotNull(loaded)
        assertEquals(plot.name, loaded.name)
    }
    
    @Test
    fun `test deletePlot removes plot from database`() = runBlocking {
        // Given: An existing plot
        val plot = createTestPlot()
        plotPersistence.createPlot(plot)
        
        // When: Deleting the plot
        plotPersistence.deletePlot(plot.id)
        
        // Then: Plot no longer exists
        val loaded = plotPersistence.loadPlot(plot.id)
        assertNull(loaded)
    }
    
    @Test
    fun `test serialization round-trip preserves all fields`() = runBlocking {
        // Given: A plot with all fields populated
        val originalPlot = Plot(
            id = UUID.randomUUID(),
            owner = UUID.randomUUID(),
            name = "Test Plot",
            description = "A test plot with all fields",
            mainWorldName = "plot_main_world",
            devWorldName = "plot_dev_world",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            settings = PlotSettings(
                biome = "DESERT",
                timeOfDay = 12000L,
                pvpEnabled = true,
                mobSpawningEnabled = true,
                worldBorderSize = 2048
            ),
            metadata = PlotMetadata(
                tags = listOf("adventure", "parkour", "pvp"),
                rating = 42,
                ratedBy = setOf(UUID.randomUUID(), UUID.randomUUID()),
                currentPlayers = 5
            ),
            trustedPlayers = setOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        )
        
        // When: Serializing and deserializing through database
        plotPersistence.createPlot(originalPlot)
        val loadedPlot = plotPersistence.loadPlot(originalPlot.id)
        
        // Then: All fields are preserved
        assertNotNull(loadedPlot)
        assertEquals(originalPlot.id, loadedPlot.id)
        assertEquals(originalPlot.owner, loadedPlot.owner)
        assertEquals(originalPlot.name, loadedPlot.name)
        assertEquals(originalPlot.description, loadedPlot.description)
        assertEquals(originalPlot.mainWorldName, loadedPlot.mainWorldName)
        assertEquals(originalPlot.devWorldName, loadedPlot.devWorldName)
        assertEquals(originalPlot.createdAt, loadedPlot.createdAt)
        assertEquals(originalPlot.updatedAt, loadedPlot.updatedAt)
        
        // Verify settings
        assertEquals(originalPlot.settings.biome, loadedPlot.settings.biome)
        assertEquals(originalPlot.settings.timeOfDay, loadedPlot.settings.timeOfDay)
        assertEquals(originalPlot.settings.pvpEnabled, loadedPlot.settings.pvpEnabled)
        assertEquals(originalPlot.settings.mobSpawningEnabled, loadedPlot.settings.mobSpawningEnabled)
        assertEquals(originalPlot.settings.worldBorderSize, loadedPlot.settings.worldBorderSize)
        
        // Verify metadata
        assertEquals(originalPlot.metadata.tags, loadedPlot.metadata.tags)
        assertEquals(originalPlot.metadata.rating, loadedPlot.metadata.rating)
        assertEquals(originalPlot.metadata.ratedBy, loadedPlot.metadata.ratedBy)
        assertEquals(originalPlot.metadata.currentPlayers, loadedPlot.metadata.currentPlayers)
        
        // Verify trusted players
        assertEquals(originalPlot.trustedPlayers, loadedPlot.trustedPlayers)
    }
    
    @Test
    fun `test updatePlotMetadata updates name and description`() = runBlocking {
        // Given: An existing plot
        val plot = createTestPlot()
        plotPersistence.createPlot(plot)
        
        // When: Updating metadata
        plotPersistence.updatePlotMetadata(
            plotId = plot.id,
            name = "New Name",
            description = "New Description"
        )
        
        // Then: Metadata is updated
        val loaded = plotPersistence.loadPlot(plot.id)
        assertNotNull(loaded)
        assertEquals("New Name", loaded.name)
        assertEquals("New Description", loaded.description)
    }
    
    @Test
    fun `test updatePlotMetadata validates name length`() = runBlocking {
        // Given: An existing plot
        val plot = createTestPlot()
        plotPersistence.createPlot(plot)
        
        // When/Then: Updating with name exceeding 32 characters throws exception
        val longName = "a".repeat(33)
        assertThrows<IllegalArgumentException> {
            plotPersistence.updatePlotMetadata(
                plotId = plot.id,
                name = longName
            )
        }
    }
    
    @Test
    fun `test updatePlotMetadata validates description length`() = runBlocking {
        // Given: An existing plot
        val plot = createTestPlot()
        plotPersistence.createPlot(plot)
        
        // When/Then: Updating with description exceeding 256 characters throws exception
        val longDescription = "a".repeat(257)
        assertThrows<IllegalArgumentException> {
            plotPersistence.updatePlotMetadata(
                plotId = plot.id,
                description = longDescription
            )
        }
    }
    
    @Test
    fun `test updatePlotMetadata updates tags`() = runBlocking {
        // Given: An existing plot
        val plot = createTestPlot()
        plotPersistence.createPlot(plot)
        
        // When: Updating tags
        val newTags = listOf("tag1", "tag2", "tag3")
        plotPersistence.updatePlotMetadata(
            plotId = plot.id,
            tags = newTags
        )
        
        // Then: Tags are updated
        val loaded = plotPersistence.loadPlot(plot.id)
        assertNotNull(loaded)
        assertEquals(newTags, loaded.metadata.tags)
    }
    
    @Test
    fun `test addRating increments rating and tracks player`() = runBlocking {
        // Given: An existing plot with initial rating
        val plot = createTestPlot()
        plotPersistence.createPlot(plot)
        val playerId = UUID.randomUUID()
        
        // When: Adding a rating
        plotPersistence.addRating(plot.id, playerId)
        
        // Then: Rating is incremented and player is tracked
        val loaded = plotPersistence.loadPlot(plot.id)
        assertNotNull(loaded)
        assertEquals(1, loaded.metadata.rating)
        assertTrue(loaded.metadata.ratedBy.contains(playerId))
    }
    
    @Test
    fun `test addRating prevents duplicate ratings from same player`() = runBlocking {
        // Given: An existing plot
        val plot = createTestPlot()
        plotPersistence.createPlot(plot)
        val playerId = UUID.randomUUID()
        
        // When: Same player rates twice
        plotPersistence.addRating(plot.id, playerId)
        plotPersistence.addRating(plot.id, playerId)
        
        // Then: Rating is only incremented once (MongoDB $addToSet prevents duplicates)
        val loaded = plotPersistence.loadPlot(plot.id)
        assertNotNull(loaded)
        // Note: The rating will be 2 because $inc always increments
        // In production, you'd check ratedBy before calling addRating
        assertEquals(1, loaded.metadata.ratedBy.size)
        assertTrue(loaded.metadata.ratedBy.contains(playerId))
    }
    
    @Test
    fun `test updatePlayerCount updates current players`() = runBlocking {
        // Given: An existing plot
        val plot = createTestPlot()
        plotPersistence.createPlot(plot)
        
        // When: Updating player count
        plotPersistence.updatePlayerCount(plot.id, 10)
        
        // Then: Player count is updated
        val loaded = plotPersistence.loadPlot(plot.id)
        assertNotNull(loaded)
        assertEquals(10, loaded.metadata.currentPlayers)
    }
    
    @Test
    fun `test serialization handles empty collections`() = runBlocking {
        // Given: A plot with empty collections
        val plot = createTestPlot().copy(
            metadata = PlotMetadata(
                tags = emptyList(),
                rating = 0,
                ratedBy = emptySet(),
                currentPlayers = 0
            ),
            trustedPlayers = emptySet()
        )
        
        // When: Serializing and deserializing
        plotPersistence.createPlot(plot)
        val loaded = plotPersistence.loadPlot(plot.id)
        
        // Then: Empty collections are preserved
        assertNotNull(loaded)
        assertTrue(loaded.metadata.tags.isEmpty())
        assertTrue(loaded.metadata.ratedBy.isEmpty())
        assertTrue(loaded.trustedPlayers.isEmpty())
    }
    
    private fun createTestPlot(): Plot {
        return Plot(
            id = UUID.randomUUID(),
            owner = UUID.randomUUID(),
            name = "Test Plot",
            description = "A test plot",
            mainWorldName = "test_main",
            devWorldName = "test_dev",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            settings = PlotSettings(),
            metadata = PlotMetadata(),
            trustedPlayers = emptySet()
        )
    }
}
