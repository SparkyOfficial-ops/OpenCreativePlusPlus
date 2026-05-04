package com.opencreativeplus.plugin.inventory

import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.api.registry.NodeRegistry
import com.opencreativeplus.core.database.MongoConnectionManager
import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.registry.NodeCategory
import kotlinx.coroutines.flow.firstOrNull
import org.bson.Document
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.logging.Logger

/**
 * Manages per-player per-plot inventory states across the three modes.
 *
 * Each player has three separate inventory states per plot (BUILD, DEV, PLAY).
 * Inventories are serialized to Base64 and stored in MongoDB.
 *
 * Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 17.4, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 7.2
 */
class InventoryManager(
    private val database: MongoDatabase,
    private val connectionManager: MongoConnectionManager,
    private val nodeRegistry: NodeRegistry,
    collectionOverride: com.mongodb.kotlin.client.coroutine.MongoCollection<Document>? = null,
    private val categoryRegistry: CategoryRegistry? = null,
    private val logger: Logger? = null
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
    // DEV mode provisioning (Requirements 2.1–2.6, 7.2)
    // -------------------------------------------------------------------------

    /**
     * Provision the DEV mode inventory with category blocks, glass, signs, and chests.
     *
     * Slots 0–5: one ItemStack (qty 64) per NodeCategory, display name = russianLabel.
     * Slots 6–8: BLUE_STAINED_GLASS (64), WHITE_STAINED_GLASS (64), GRAY_STAINED_GLASS (64).
     * Slots 9–10: OAK_SIGN (64), CHEST (1).
     *
     * If total item types exceed 36, logs a WARNING and fills only the first 36 slots.
     *
     * Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6
     */
    fun provisionDevInventory(player: Player) {
        player.inventory.clear()

        val items = mutableListOf<ItemStack>()

        // Slots 0–5: six NodeCategory blocks with Russian display names (Req 2.2, 2.5)
        if (categoryRegistry != null) {
            NodeCategory.entries.forEach { category ->
                val stack = ItemStack(category.material, 64)
                val meta: ItemMeta? = stack.itemMeta
                if (meta != null) {
                    meta.setDisplayName(category.russianLabel)
                    stack.itemMeta = meta
                }
                items.add(stack)
            }
        } else {
            // Fallback: legacy material-based provisioning when no CategoryRegistry provided
            if (nodeRegistry is com.opencreativeplus.plugin.registry.NodeRegistryImpl) {
                nodeRegistry.getRegisteredActionMaterials().forEach { material ->
                    items.add(ItemStack(material, 64))
                }
            }
        }

        // Slots 6–8: glass blocks (Req 2.3)
        items.add(ItemStack(Material.BLUE_STAINED_GLASS, 64))
        items.add(ItemStack(Material.WHITE_STAINED_GLASS, 64))
        items.add(ItemStack(Material.GRAY_STAINED_GLASS, 64))

        // Slots 9–10: sign and chest (Req 2.4)
        items.add(ItemStack(Material.OAK_SIGN, 64))
        items.add(ItemStack(Material.CHEST, 1))

        // Warn if exceeding 36 slots (Req 2.6)
        if (items.size > 36) {
            logger?.warning(
                "provisionDevInventory: total item types (${items.size}) exceeds 36 — " +
                "provisioning only the first 36 slots."
            )
        }

        // Fill inventory slots (Req 2.6: max 36)
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
        return try {
            val bytes = Base64.getDecoder().decode(base64)
            BukkitObjectInputStream(ByteArrayInputStream(bytes)).use { ois ->
                val size = ois.readInt()
                Array(size) { ois.readObject() as? ItemStack }
            }
        } catch (e: Exception) {
            logger?.warning("deserializeContents: failed to deserialize inventory: ${e.message}")
            arrayOfNulls(36)
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
