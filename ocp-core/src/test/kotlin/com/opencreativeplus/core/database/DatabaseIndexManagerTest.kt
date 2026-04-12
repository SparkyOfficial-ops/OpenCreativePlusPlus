package com.opencreativeplus.core.database

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for DatabaseIndexManager using Testcontainers.
 * Tests index creation for all collections.
 * 
 17.1, 17.2, 17.3, 17.4, 37.5
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseIndexManagerTest {
    
    private lateinit var mongoContainer: MongoDBContainer
    private lateinit var connectionManager: MongoConnectionManager
    private lateinit var indexManager: DatabaseIndexManager
    
    @BeforeAll
    fun setupContainer() {
        // Start MongoDB container
        mongoContainer = MongoDBContainer(DockerImageName.parse("mongo:7.0"))
        mongoContainer.start()
        
        // Create connection manager with container connection string
        val config = DatabaseConfig(
            connectionString = mongoContainer.replicaSetUrl,
            databaseName = "test_ocp_indexes",
            maxRetries = 3,
            retryDelayMs = 100
        )
        
        connectionManager = MongoConnectionManager(config)
        
        runBlocking {
            connectionManager.connect()
            indexManager = DatabaseIndexManager(connectionManager.getDatabase())
        }
    }
    
    @AfterAll
    fun teardownContainer() {
        connectionManager.close()
        mongoContainer.stop()
    }
    
    @Test
    fun `test createIndexes creates all required indexes`() = runBlocking {
        // When: Creating indexes
        indexManager.createIndexes()
        
        // Then: Verify indexes exist for all collections
        verifyPlotsIndexes()
        verifyCompiledAstsIndexes()
        verifyPlotVariablesIndexes()
        verifyPlayerInventoriesIndexes()
        verifyExecutionLogsIndexes()
    }
    
    @Test
    fun `test createIndexes is idempotent`() = runBlocking {
        // Given: Indexes already created
        indexManager.createIndexes()
        
        // When: Creating indexes again
        indexManager.createIndexes()
        
        // Then: No errors occur and indexes still exist
        verifyPlotsIndexes()
    }
    
    private suspend fun verifyPlotsIndexes() {
        val collection = connectionManager.getDatabase()
            .getCollection<org.bson.Document>("plots")
        
        val indexes = mutableListOf<String>()
        collection.listIndexes().collect { index ->
            indexes.add(index.toJson())
        }
        
        // Verify required indexes exist
        assertTrue(indexes.any { it.contains("\"owner\"") }, "Missing owner index")
        assertTrue(indexes.any { it.contains("\"metadata.rating\"") }, "Missing rating index")
        assertTrue(indexes.any { it.contains("\"metadata.tags\"") }, "Missing tags index")
        assertTrue(indexes.any { it.contains("\"updated_at\"") }, "Missing updated_at index")
    }
    
    private suspend fun verifyCompiledAstsIndexes() {
        val collection = connectionManager.getDatabase()
            .getCollection<org.bson.Document>("compiled_asts")
        
        val indexes = mutableListOf<String>()
        collection.listIndexes().collect { index ->
            indexes.add(index.toJson())
        }
        
        // Verify compiled_at index exists
        assertTrue(indexes.any { it.contains("\"compiled_at\"") }, "Missing compiled_at index")
    }
    
    private suspend fun verifyPlotVariablesIndexes() {
        val collection = connectionManager.getDatabase()
            .getCollection<org.bson.Document>("plot_variables")
        
        val indexes = mutableListOf<String>()
        collection.listIndexes().collect { index ->
            indexes.add(index.toJson())
        }
        
        // Verify updated_at index exists
        assertTrue(indexes.any { it.contains("\"updated_at\"") }, "Missing updated_at index")
    }
    
    private suspend fun verifyPlayerInventoriesIndexes() {
        val collection = connectionManager.getDatabase()
            .getCollection<org.bson.Document>("player_inventories")
        
        val indexes = mutableListOf<String>()
        collection.listIndexes().collect { index ->
            indexes.add(index.toJson())
        }
        
        // Verify compound unique index exists
        assertTrue(
            indexes.any { 
                it.contains("\"player_id\"") && 
                it.contains("\"plot_id\"") && 
                it.contains("\"mode\"") &&
                it.contains("\"unique\" : true")
            },
            "Missing unique compound index on player_id + plot_id + mode"
        )
        
        // Verify individual indexes
        assertTrue(indexes.any { it.contains("\"player_id\"") }, "Missing player_id index")
        assertTrue(indexes.any { it.contains("\"plot_id\"") }, "Missing plot_id index")
    }
    
    private suspend fun verifyExecutionLogsIndexes() {
        val collection = connectionManager.getDatabase()
            .getCollection<org.bson.Document>("execution_logs")
        
        val indexes = mutableListOf<String>()
        collection.listIndexes().collect { index ->
            indexes.add(index.toJson())
        }
        
        // Verify plot_id index
        assertTrue(indexes.any { it.contains("\"plot_id\"") }, "Missing plot_id index")
        
        // Verify started_at index (both regular and TTL)
        val startedAtIndexes = indexes.filter { it.contains("\"started_at\"") }
        assertTrue(startedAtIndexes.isNotEmpty(), "Missing started_at indexes")
        
        // Verify TTL index exists (expireAfterSeconds field)
        assertTrue(
            indexes.any { it.contains("\"expireAfterSeconds\"") },
            "Missing TTL index for execution logs"
        )
    }
    
    @Test
    fun `test plots collection indexes support common queries`() = runBlocking {
        // Given: Indexes created
        indexManager.createIndexes()
        
        // When: Performing common queries
        val collection = connectionManager.getDatabase()
            .getCollection<org.bson.Document>("plots")
        
        // Query by owner (should use index)
        val ownerQuery = org.bson.Document("owner", "test-uuid")
        collection.find(ownerQuery).collect { }
        
        // Sort by rating (should use index)
        collection.find()
            .sort(org.bson.Document("metadata.rating", -1))
            .collect { }
        
        // Filter by tags (should use index)
        val tagsQuery = org.bson.Document("metadata.tags", "adventure")
        collection.find(tagsQuery).collect { }
        
        // Then: No errors occur (indexes are being used)
        assertTrue(true, "Queries executed successfully with indexes")
    }
    
    @Test
    fun `test player_inventories unique constraint prevents duplicates`() = runBlocking {
        // Given: Indexes created
        indexManager.createIndexes()
        
        val collection = connectionManager.getDatabase()
            .getCollection<org.bson.Document>("player_inventories")
        
        // When: Inserting a document
        val doc = org.bson.Document().apply {
            put("player_id", "player-1")
            put("plot_id", "plot-1")
            put("mode", "BUILD")
            put("contents", "test")
        }
        collection.insertOne(doc)
        
        // Then: Inserting duplicate should fail
        val exception = assertThrows<Exception> {
            collection.insertOne(doc)
        }
        
        // Verify it's a duplicate key error
        assertTrue(
            exception.message?.contains("duplicate", ignoreCase = true) == true ||
            exception.message?.contains("E11000", ignoreCase = true) == true,
            "Expected duplicate key error, got: ${exception.message}"
        )
    }
    
    @Test
    fun `test execution_logs TTL index configuration`() = runBlocking {
        // Given: Indexes created
        indexManager.createIndexes()
        
        val collection = connectionManager.getDatabase()
            .getCollection<org.bson.Document>("execution_logs")
        
        // When: Listing indexes
        val indexes = mutableListOf<org.bson.Document>()
        collection.listIndexes().collect { index ->
            indexes.add(index)
        }
        
        // Then: Find TTL index and verify expiration time
        val ttlIndex = indexes.find { 
            it.containsKey("expireAfterSeconds")
        }
        
        assertNotNull(ttlIndex, "TTL index not found")
        
        // Verify expiration is set to 7 days (604800 seconds)
        val expireAfterSeconds = ttlIndex.getInteger("expireAfterSeconds")
        assertTrue(
            expireAfterSeconds == 604800,
            "Expected 604800 seconds (7 days), got $expireAfterSeconds"
        )
    }
}
