package com.opencreativeplus.core.database

import com.mongodb.client.model.IndexOptions
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import org.bson.Document
import java.util.concurrent.TimeUnit

/**
 * Manages database indexes for all collections.
 * Creates indexes for owner, rating, tags, timestamps, and TTL for execution logs.
 * 
 * Requirements: 17.1, 17.2, 17.3, 17.4, 37.5
 */
class DatabaseIndexManager(private val database: MongoDatabase) {
    
    /**
     * Create all required indexes for OCP collections.
     * Should be called during plugin initialization.
     */
    suspend fun createIndexes() {
        createPlotsIndexes()
        createCompiledAstsIndexes()
        createPlotVariablesIndexes()
        createPlayerInventoriesIndexes()
        createExecutionLogsIndexes()
    }
    
    private suspend fun createPlotsIndexes() {
        val collection = database.getCollection<Document>("plots")
        
        // Index for querying plots by owner
        collection.createIndex(Document("owner", 1))
        
        // Index for sorting by rating (descending)
        collection.createIndex(Document("metadata.rating", -1))
        
        // Index for filtering by tags
        collection.createIndex(Document("metadata.tags", 1))
        
        // Index for sorting by update time
        collection.createIndex(Document("updated_at", -1))
    }
    
    private suspend fun createCompiledAstsIndexes() {
        val collection = database.getCollection<Document>("compiled_asts")
        
        // Index for querying by plot_id (already covered by _id)
        // Index for sorting by compilation time
        collection.createIndex(Document("compiled_at", -1))
    }
    
    private suspend fun createPlotVariablesIndexes() {
        val collection = database.getCollection<Document>("plot_variables")
        
        // Index for sorting by update time
        collection.createIndex(Document("updated_at", -1))
    }
    
    private suspend fun createPlayerInventoriesIndexes() {
        val collection = database.getCollection<Document>("player_inventories")
        
        // Unique compound index for player_id + plot_id + mode
        collection.createIndex(
            Document().apply {
                put("player_id", 1)
                put("plot_id", 1)
                put("mode", 1)
            },
            IndexOptions().unique(true)
        )
        
        // Index for querying by player
        collection.createIndex(Document("player_id", 1))
        
        // Index for querying by plot
        collection.createIndex(Document("plot_id", 1))
    }
    
    private suspend fun createExecutionLogsIndexes() {
        val collection = database.getCollection<Document>("execution_logs")
        
        // Index for querying logs by plot
        collection.createIndex(Document("plot_id", 1))
        
        // Index for sorting by start time
        collection.createIndex(Document("started_at", -1))
        
        // TTL index to auto-delete logs after 7 days
        collection.createIndex(
            Document("started_at", 1),
            IndexOptions().expireAfter(7, TimeUnit.DAYS)
        )
    }
}
