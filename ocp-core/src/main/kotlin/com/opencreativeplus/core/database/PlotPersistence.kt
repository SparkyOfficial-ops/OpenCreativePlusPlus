package com.opencreativeplus.core.database

import com.mongodb.client.model.ReplaceOptions
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotSettings
import kotlinx.coroutines.flow.firstOrNull
import org.bson.Document
import java.util.UUID

/**
 * Plot persistence layer for MongoDB operations.
 * Handles plot serialization/deserialization and CRUD operations.
 * 
 17.1, 39.1, 39.2, 39.3, 39.4, 39.5
 */
class PlotPersistence(
    private val database: MongoDatabase,
    private val connectionManager: MongoConnectionManager
) {
    
    private val collection = database.getCollection<Document>("plots")
    
    /**
     * Create a new plot in the database.
     * 
     * @param plot The plot to create
     * @throws Exception if creation fails after retries
     */
    suspend fun createPlot(plot: Plot) {
        connectionManager.withRetry {
            val document = serializePlot(plot)
            collection.insertOne(document)
        }
    }
    
    /**
     * Load a plot from the database by ID.
     * 
     * @param plotId The UUID of the plot to load
     * @return The loaded plot, or null if not found
     * @throws Exception if load fails after retries
     */
    suspend fun loadPlot(plotId: UUID): Plot? {
        return connectionManager.withRetry {
            val document = collection.find(Document("_id", plotId.toString()))
                .firstOrNull()
            
            document?.let { deserializePlot(it) }
        }
    }
    
    /**
     * Update an existing plot in the database.
     * Uses upsert to create if not exists.
     * 
     * @param plot The plot to update
     * @throws Exception if update fails after retries
     */
    suspend fun updatePlot(plot: Plot) {
        connectionManager.withRetry {
            val document = serializePlot(plot)
            collection.replaceOne(
                Document("_id", plot.id.toString()),
                document,
                ReplaceOptions().upsert(true)
            )
        }
    }
    
    /**
     * Delete a plot from the database.
     * 
     * @param plotId The UUID of the plot to delete
     * @throws Exception if deletion fails after retries
     */
    suspend fun deletePlot(plotId: UUID) {
        connectionManager.withRetry {
            collection.deleteOne(Document("_id", plotId.toString()))
        }
    }
    
    /**
     * Update plot metadata (name, description, tags, rating).
     * 
     * @param plotId The UUID of the plot
     * @param name New plot name (max 32 characters)
     * @param description New plot description (max 256 characters)
     * @throws IllegalArgumentException if name or description exceeds limits
     * @throws Exception if update fails after retries
     */
    suspend fun updatePlotMetadata(
        plotId: UUID,
        name: String? = null,
        description: String? = null,
        tags: List<String>? = null
    ) {
        // Validate character limits (Requirements 39.5)
        name?.let {
            require(it.length <= 32) { "Plot name must not exceed 32 characters" }
        }
        description?.let {
            require(it.length <= 256) { "Plot description must not exceed 256 characters" }
        }
        
        connectionManager.withRetry {
            val updates = Document()
            
            name?.let { updates["name"] = it }
            description?.let { updates["description"] = it }
            tags?.let { updates["metadata.tags"] = it }
            updates["updated_at"] = System.currentTimeMillis()
            
            if (updates.isNotEmpty()) {
                collection.updateOne(
                    Document("_id", plotId.toString()),
                    Document("\$set", updates)
                )
            }
        }
    }
    
    /**
     * Update plot rating.
     * 
     * @param plotId The UUID of the plot
     * @param playerId The UUID of the player rating the plot
     * @throws Exception if update fails after retries
     */
    suspend fun addRating(plotId: UUID, playerId: UUID) {
        connectionManager.withRetry {
            collection.updateOne(
                Document("_id", plotId.toString()),
                Document().apply {
                    put("\$inc", Document("metadata.rating", 1))
                    put("\$addToSet", Document("metadata.rated_by", playerId.toString()))
                    put("\$set", Document("updated_at", System.currentTimeMillis()))
                }
            )
        }
    }
    
    /**
     * Update current player count for a plot.
     * 
     * @param plotId The UUID of the plot
     * @param count The new player count
     * @throws Exception if update fails after retries
     */
    suspend fun updatePlayerCount(plotId: UUID, count: Int) {
        connectionManager.withRetry {
            collection.updateOne(
                Document("_id", plotId.toString()),
                Document("\$set", Document("metadata.current_players", count))
            )
        }
    }
    
    /**
     * Serialize a Plot object to a MongoDB Document.
     */
    private fun serializePlot(plot: Plot): Document {
        return Document().apply {
            put("_id", plot.id.toString())
            put("owner", plot.owner.toString())
            put("name", plot.name)
            put("description", plot.description)
            put("main_world_name", plot.mainWorldName)
            put("dev_world_name", plot.devWorldName)
            put("created_at", plot.createdAt)
            put("updated_at", plot.updatedAt)
            
            // Serialize settings
            put("settings", Document().apply {
                put("biome", plot.settings.biome)
                put("time_of_day", plot.settings.timeOfDay)
                put("pvp_enabled", plot.settings.pvpEnabled)
                put("mob_spawning_enabled", plot.settings.mobSpawningEnabled)
                put("world_border_size", plot.settings.worldBorderSize)
            })
            
            // Serialize metadata
            put("metadata", Document().apply {
                put("tags", plot.metadata.tags)
                put("rating", plot.metadata.rating)
                put("rated_by", plot.metadata.ratedBy.map { it.toString() })
                put("current_players", plot.metadata.currentPlayers)
            })
            
            // Serialize trusted players
            put("trusted_players", plot.trustedPlayers.map { it.toString() })
        }
    }
    
    /**
     * Deserialize a MongoDB Document to a Plot object.
     */
    private fun deserializePlot(document: Document): Plot {
        val settingsDoc = document.get("settings", Document::class.java)
        val metadataDoc = document.get("metadata", Document::class.java)
        
        return Plot(
            id = UUID.fromString(document.getString("_id")),
            owner = UUID.fromString(document.getString("owner")),
            name = document.getString("name"),
            description = document.getString("description"),
            mainWorldName = document.getString("main_world_name"),
            devWorldName = document.getString("dev_world_name"),
            createdAt = document.getLong("created_at"),
            updatedAt = document.getLong("updated_at"),
            settings = PlotSettings(
                biome = settingsDoc.getString("biome"),
                timeOfDay = settingsDoc.getLong("time_of_day"),
                pvpEnabled = settingsDoc.getBoolean("pvp_enabled"),
                mobSpawningEnabled = settingsDoc.getBoolean("mob_spawning_enabled"),
                worldBorderSize = settingsDoc.getInteger("world_border_size")
            ),
            metadata = PlotMetadata(
                tags = metadataDoc.getList("tags", String::class.java),
                rating = metadataDoc.getInteger("rating"),
                ratedBy = metadataDoc.getList("rated_by", String::class.java)
                    .map { UUID.fromString(it) }
                    .toSet(),
                currentPlayers = metadataDoc.getInteger("current_players")
            ),
            trustedPlayers = document.getList("trusted_players", String::class.java)
                .map { UUID.fromString(it) }
                .toSet()
        )
    }
}
