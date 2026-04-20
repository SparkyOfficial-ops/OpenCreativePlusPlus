package com.opencreativeplus.plugin.inventory

import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.api.registry.NodeRegistry
import com.opencreativeplus.core.database.MongoConnectionManager
import kotlinx.coroutines.flow.firstOrNull
import org.bson.Document
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

/**
 * Manages per-player per-plot inventory states across the three modes.
 *
 * Each player has three separate inventory states per plot (BUILD, DEV, PLAY).
 * Inventories are serialized to Base64 and stored in MongoDB.
 *
 14.1, 14.2, 14.3, 14.4, 14.5, 17.4, 36.1, 36.2, 36.3, 36.4
 */
class InventoryManager(
    private val database: MongoDatabase,
    private val connectionManager: MongoConnectionManager,
    private val nodeRegistry: NodeRegistry,
    collectionOverride: com.mongodb.kotlin.client.coroutine.MongoCollection<Document>? = null
) {
    private val collection = collectionOverride ?: database.getCollection<Document>("player_inventories")

    // -------------------------------------------------------------------------
    // Save / Load
    // -------------------------------------------------------------------------

    /**
     * Save the player's current inventory for [mode] on [plotId].
     * Reads inventory on the calling (main) thread, then persists to DB.
     14.1, 14.5, 17.4
     */
    suspend fun saveInventory(player: Player, plotId: UUID, mode: PlotMode) {
        val contents = player.inventory.contents
        val armor = player.inventory.armorContents
        val offhand = player.inventory.itemInOffHand
        saveInventorySnapshot(player, plotId, mode, contents, armor, offhand)
    }

    /**
     * Save a pre-captured inventory snapshot to DB (can be called from any thread).
     */
    suspend fun saveInventorySnapshot(
        player: Player, plotId: UUID, mode: PlotMode,
        contents: Array<ItemStack?>, armor: Array<ItemStack?>, offhand: ItemStack
    ) {
        val doc = Document().apply {
            put("_id", inventoryKey(player.uniqueId, plotId, mode))
            put("player_id", player.uniqueId.toString())
            put("plot_id", plotId.toString())
            put("mode", mode.name)
            put("contents", serializeContents(contents))
            put("armor", serializeContents(armor))
            put("offhand", serializeItem(offhand))
            put("saved_at", System.currentTimeMillis())
        }
        connectionManager.withRetry {
            collection.replaceOne(
                Document("_id", inventoryKey(player.uniqueId, plotId, mode)),
                doc,
                com.mongodb.client.model.ReplaceOptions().upsert(true)
            )
        }
    }

    /**
     * Fetch the raw inventory document from DB (can be called from any thread).
     */
    suspend fun fetchInventoryDoc(player: Player, plotId: UUID, mode: PlotMode): Document? =
        connectionManager.withRetry {
            collection.find(Document("_id", inventoryKey(player.uniqueId, plotId, mode))).firstOrNull()
        }

    /**
     * Apply a fetched inventory document to the player. Must be called on the main thread.
     */
    fun applyInventoryDoc(player: Player, doc: Document?) {
        player.inventory.clear()
        if (doc != null) {
            player.inventory.contents = deserializeContents(doc["contents"] as? String)
            player.inventory.armorContents = deserializeContents(doc["armor"] as? String)
            player.inventory.setItemInOffHand(deserializeItem(doc["offhand"] as? String))
        }
    }

    /**
     * Load and apply the saved inventory for [mode] on [plotId] to [player].
     * Fetches from DB first, then applies to player inventory on the calling thread.
     * If no saved state exists, clears the inventory.
     14.2, 14.3, 14.4
     */
    suspend fun loadInventory(player: Player, plotId: UUID, mode: PlotMode) {
        val doc = fetchInventoryDoc(player, plotId, mode)
        applyInventoryDoc(player, doc)
    }

    // -------------------------------------------------------------------------
    // DEV mode provisioning (s 36.1–36.4)
    // -------------------------------------------------------------------------

    /**
     * Provision the DEV mode inventory: all registered action node blocks,
     * glass blocks, signs, and chests in infinite quantities.
     36.1, 36.2, 36.3, 36.4
     */
    fun provisionDevInventory(player: Player) {
        player.inventory.clear()

        val items = mutableListOf<ItemStack>()

        // All registered action node materials (req 36.1)
        if (nodeRegistry is com.opencreativeplus.plugin.registry.NodeRegistryImpl) {
            nodeRegistry.getRegisteredActionMaterials().forEach { material ->
                items.add(ItemStack(material, 64))
            }
        }

        // Glass blocks for grid extension (req 36.2)
        items.add(ItemStack(Material.BLUE_STAINED_GLASS, 64))
        items.add(ItemStack(Material.WHITE_STAINED_GLASS, 64))
        items.add(ItemStack(Material.GRAY_STAINED_GLASS, 64))

        // Signs and chests for parameters (req 36.3)
        items.add(ItemStack(Material.OAK_SIGN, 64))
        items.add(ItemStack(Material.CHEST, 64))

        // Fill inventory slots
        items.forEachIndexed { index, item ->
            if (index < 36) player.inventory.setItem(index, item)
        }
    }

    // -------------------------------------------------------------------------
    // Serialization helpers
    // -------------------------------------------------------------------------

    private fun serializeContents(contents: Array<ItemStack?>): String {
        val baos = ByteArrayOutputStream()
        BukkitObjectOutputStream(baos).use { oos ->
            oos.writeInt(contents.size)
            contents.forEach { oos.writeObject(it) }
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }

    private fun deserializeContents(base64: String?): Array<ItemStack?> {
        if (base64.isNullOrEmpty()) return arrayOfNulls(36)
        val bytes = Base64.getDecoder().decode(base64)
        BukkitObjectInputStream(ByteArrayInputStream(bytes)).use { ois ->
            val size = ois.readInt()
            return Array(size) { ois.readObject() as? ItemStack }
        }
    }

    private fun serializeItem(item: ItemStack?): String {
        if (item == null || item.type == Material.AIR) return ""
        return serializeContents(arrayOf(item))
    }

    private fun deserializeItem(base64: String?): ItemStack? {
        if (base64.isNullOrEmpty()) return null
        return deserializeContents(base64).firstOrNull()
    }

    private fun inventoryKey(playerId: UUID, plotId: UUID, mode: PlotMode) =
        "${playerId}:${plotId}:${mode.name}"
}
