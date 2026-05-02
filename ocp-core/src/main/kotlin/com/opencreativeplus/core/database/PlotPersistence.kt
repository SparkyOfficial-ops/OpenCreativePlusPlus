package com.opencreativeplus.core.database

import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Sorts
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotSettings
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.Document
import java.util.UUID

/**
 * Generic representation of a custom menu slot for MongoDB persistence.
 * Uses primitive types only so that ocp-core has no dependency on Bukkit/Paper.
 *
 * @property displayName Human-readable display name of the slot item.
 * @property clickScriptName Optional name of the script triggered on click.
 * @property itemType Material name (e.g. "STONE", "DIAMOND_SWORD").
 * @property itemData Raw serialized item bytes encoded as Base64, or null if unavailable.
 */
data class CustomMenuSlotData(
    val displayName: String,
    val clickScriptName: String?,
    val itemType: String,
    val itemData: String?
)

/**
 * Generic representation of a custom menu definition for MongoDB persistence.
 * Uses primitive types only so that ocp-core has no dependency on Bukkit/Paper.
 *
 * @property name Unique name of the menu within the plot.
 * @property slots Map of slot index to slot data.
 */
data class CustomMenuData(
    val name: String,
    val slots: Map<Int, CustomMenuSlotData>
)

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
        // Validate character limits (s 39.5)
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
     * Get all plots owned by a specific player.
     *
     * @param ownerId The UUID of the owner
     * @return List of plots owned by the player
     */
    suspend fun getPlotsByOwner(ownerId: UUID): List<Plot> {
        return connectionManager.withRetry {
            collection
                .find(Document("owner", ownerId.toString()))
                .toList()
                .map { deserializePlot(it) }
        }
    }

    /**
     * Count plots owned by a player.
     *
     * @param ownerId The UUID of the owner
     * @return Number of plots owned by the player
     */
    suspend fun countPlotsByOwner(ownerId: UUID): Int {
        return connectionManager.withRetry {
            collection
                .find(Document("owner", ownerId.toString()))
                .toList()
                .size
        }
    }

    /**
     * Delete all data for a plot (used when owner deletes it).
     * Removes the plot document and all associated custom menus.
     *
     * @param plotId The UUID of the plot to delete
     */
    suspend fun deletePlotAllData(plotId: UUID) {
        connectionManager.withRetry {
            collection.deleteOne(Document("_id", plotId.toString()))
            val menuCol = database.getCollection<Document>("custom_menus")
            menuCol.deleteMany(Document("plot_id", plotId.toString()))
        }
    }

    /**
     * Search plots by name (case-insensitive partial match).
     *
     * @param query The search string
     * @param page Zero-based page index
     * @param pageSize Maximum number of results to return
     * @return List of matching plots
     */
    suspend fun searchPlots(query: String, page: Int, pageSize: Int): List<Plot> {
        return connectionManager.withRetry {
            val filter = Filters.regex("name", query, "i")
            collection
                .find(filter)
                .sort(Sorts.descending("metadata.rating"))
                .skip(page * pageSize)
                .limit(pageSize)
                .toList()
                .map { deserializePlot(it) }
        }
    }

    /**
     * Load a paged list of plots sorted by rating descending.
     * Optionally filter by tag.
     *
     * @param page Zero-based page index
     * @param pageSize Maximum number of plots to return
     * @param tagFilter If non-null, only plots containing this tag are returned
     * @return List of plots for the requested page
     */
    suspend fun getPlotsPaged(page: Int, pageSize: Int, tagFilter: String? = null): List<Plot> {
        return connectionManager.withRetry {
            val filter = if (tagFilter != null)
                Filters.eq("metadata.tags", tagFilter)
            else
                Document()

            collection
                .find(filter)
                .sort(Sorts.descending("metadata.rating"))
                .skip(page * pageSize)
                .limit(pageSize)
                .toList()
                .map { deserializePlot(it) }
        }
    }

    // ── Custom Menu Persistence ───────────────────────────────────────────────

    /**
     * Save or update a custom menu definition for a plot in MongoDB.
     *
     * Uses composite key `(plotId, menuName)` as the document `_id` for upsert.
     *
     * @param plotId The UUID of the plot that owns this menu.
     * @param menuData The generic menu data to persist (Bukkit-free representation).
     * @throws Exception if the operation fails after retries.
     *
     * Requirements: 6.3
     */
    suspend fun saveCustomMenu(plotId: UUID, menuData: CustomMenuData) {
        connectionManager.withRetry {
            val col = database.getCollection<Document>("custom_menus")
            val doc = Document().apply {
                put("_id", "${plotId}:${menuData.name}")
                put("plot_id", plotId.toString())
                put("menu_name", menuData.name)
                put("slots", serializeSlots(menuData.slots))
                put("updated_at", System.currentTimeMillis())
            }
            col.replaceOne(
                Filters.eq("_id", "${plotId}:${menuData.name}"),
                doc,
                ReplaceOptions().upsert(true)
            )
        }
    }

    /**
     * Load all custom menu definitions for a plot from MongoDB.
     *
     * @param plotId The UUID of the plot whose menus to load.
     * @return List of generic menu data objects; empty if none found.
     * @throws Exception if the operation fails after retries.
     *
     * Requirements: 6.3
     */
    suspend fun loadCustomMenus(plotId: UUID): List<CustomMenuData> {
        return connectionManager.withRetry {
            val col = database.getCollection<Document>("custom_menus")
            col.find(Filters.eq("plot_id", plotId.toString()))
                .toList()
                .mapNotNull { deserializeCustomMenu(it) }
        }
    }

    /**
     * Serialize a map of slot index → [CustomMenuSlotData] to a BSON [Document].
     */
    private fun serializeSlots(slots: Map<Int, CustomMenuSlotData>): Document {
        return Document().apply {
            slots.forEach { (index, slotData) ->
                put(index.toString(), Document().apply {
                    put("display_name", slotData.displayName)
                    put("click_script_name", slotData.clickScriptName)
                    put("item_type", slotData.itemType)
                    put("item_data", slotData.itemData)
                })
            }
        }
    }

    /**
     * Deserialize a BSON [Document] from the `custom_menus` collection into a [CustomMenuData].
     * Returns null if the document is malformed.
     */
    private fun deserializeCustomMenu(doc: Document): CustomMenuData? {
        return try {
            val menuName = doc.getString("menu_name") ?: return null
            val slotsDoc = doc.get("slots", Document::class.java) ?: Document()
            val slots = mutableMapOf<Int, CustomMenuSlotData>()
            for (key in slotsDoc.keys) {
                val slotIndex = key.toIntOrNull() ?: continue
                val slotDoc = slotsDoc.get(key, Document::class.java) ?: continue
                slots[slotIndex] = CustomMenuSlotData(
                    displayName = slotDoc.getString("display_name") ?: "",
                    clickScriptName = slotDoc.getString("click_script_name"),
                    itemType = slotDoc.getString("item_type") ?: "STONE",
                    itemData = slotDoc.getString("item_data")
                )
            }
            CustomMenuData(name = menuName, slots = slots)
        } catch (e: Exception) {
            null
        }
    }

    // ── Plot Serialization ────────────────────────────────────────────────────

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
                put("is_public", plot.settings.isPublic)
                put("allow_interactions", plot.settings.allowInteractions)
                put("allow_explosions", plot.settings.allowExplosions)
                put("allow_fire", plot.settings.allowFire)
                put("allow_coding_access", plot.settings.allowCodingAccess)
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
                biome = settingsDoc?.getString("biome") ?: "PLAINS",
                timeOfDay = settingsDoc?.getLong("time_of_day") ?: 6000L,
                pvpEnabled = settingsDoc?.getBoolean("pvp_enabled") ?: false,
                mobSpawningEnabled = settingsDoc?.getBoolean("mob_spawning_enabled") ?: false,
                worldBorderSize = settingsDoc?.getInteger("world_border_size") ?: 1024,
                isPublic = settingsDoc?.getBoolean("is_public") ?: true,
                allowInteractions = settingsDoc?.getBoolean("allow_interactions") ?: true,
                allowExplosions = settingsDoc?.getBoolean("allow_explosions") ?: false,
                allowFire = settingsDoc?.getBoolean("allow_fire") ?: false,
                allowCodingAccess = settingsDoc?.getBoolean("allow_coding_access") ?: false
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
