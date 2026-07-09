package com.opencreativeplus.plugin.inventory

import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.api.registry.NodeRegistry
import com.opencreativeplus.core.database.MongoConnectionManager
import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.registry.NodeCategory
import kotlinx.coroutines.flow.firstOrNull
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
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
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Tracks which players are currently in DEV mode (plotId stored per player UUID).
     * Used by [isDevInventoryComplete] to decide when to reprovision.
     */
    private val playersInDev = ConcurrentHashMap<UUID, UUID>() // playerId -> plotId

    /**
     * Records that [player] has entered DEV mode on [plotId].
     */
    fun markPlayerInDev(player: Player, plotId: UUID) {
        playersInDev[player.uniqueId] = plotId
    }

    /**
     * Records that [player] has left DEV mode.
     */
    fun unmarkPlayerInDev(player: Player) {
        playersInDev.remove(player.uniqueId)
    }

    /**
     * Returns true if [player] is currently in DEV mode.
     */
    fun isPlayerInDev(player: Player): Boolean = playersInDev.containsKey(player.uniqueId)

    /**
     * Returns the expected number of slots a provisioned DEV inventory occupies.
     * Used to detect when items have gone missing.
     */
    private fun expectedDevSlotCount(): Int {
        val categoryCount = categoryRegistry?.let { NodeCategory.entries.size } ?: 0
        return categoryCount + 3 + 2  // categories + 3 glass + sign + barrel
    }

    /**
     * Returns true if the player's DEV inventory is missing any items that should be there.
     * Checks that the first [expectedDevSlotCount] slots are all non-empty.
     */
    fun isDevInventoryIncomplete(player: Player): Boolean {
        val expected = expectedDevSlotCount()
        for (i in 0 until minOf(expected, 36)) {
            val item = player.inventory.getItem(i)
            if (item == null || item.type == Material.AIR) return true
        }
        return false
    }

    // -------------------------------------------------------------------------
    // DEV mode provisioning (Requirements 2.1–2.6, 7.2)
    // -------------------------------------------------------------------------

    /**
     * Provision the DEV mode inventory with category blocks, glass, signs, and barrels.
     *
     * Each item:
     * - Quantity = 1 (not stackable — easier to notice when missing)
     * - Display name = non-italic bold white text via Adventure API
     * - Lore = category description (italic disabled)
     *
     * Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6
     */
    fun provisionDevInventory(player: Player) {
        player.inventory.clear()

        val items = mutableListOf<ItemStack>()

        if (categoryRegistry != null) {
            NodeCategory.entries.forEach { category ->
                items.add(makeCategoryItem(category))
            }
        } else {
            if (nodeRegistry is com.opencreativeplus.plugin.registry.NodeRegistryImpl) {
                nodeRegistry.getRegisteredActionMaterials().forEach { material ->
                    items.add(ItemStack(material, 1))
                }
            }
        }

        // Glass strips — qty 64 is intentional (placed in world, not held)
        items.add(makeNamedItem(Material.BLUE_STAINED_GLASS,  64, "Синяя полоса",  "Начало новой строки кода"))
        items.add(makeNamedItem(Material.WHITE_STAINED_GLASS, 64, "Белое стекло",  "Продолжение строки кода"))
        items.add(makeNamedItem(Material.GRAY_STAINED_GLASS,  64, "Серое стекло",  "Разделитель / заполнитель"))

        // Tools
        items.add(makeNamedItem(Material.OAK_SIGN,  1, "Табличка",  "Параметры действий"))
        items.add(makeNamedItem(Material.BARREL,    1, "Бочка",     "Хранилище параметров"))

        if (items.size > 36) {
            logger?.warning("provisionDevInventory: ${items.size} items — showing first 36 slots only.")
        }

        items.forEachIndexed { index, item ->
            if (index < 36) player.inventory.setItem(index, item)
        }
    }

    /**
     * Creates a category block item with non-italic display name and lore.
     */
    private fun makeCategoryItem(category: NodeCategory): ItemStack {
        val stack = ItemStack(category.material, 1)
        val meta = stack.itemMeta ?: return stack

        // Adventure API: bold white, italic explicitly disabled
        meta.displayName(
            Component.text(category.russianLabel)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true)
        )
        meta.lore(listOf(
            Component.text("§7ПКМ — открыть список действий")
                .decoration(TextDecoration.ITALIC, false)
        ))

        stack.itemMeta = meta
        return stack
    }

    /**
     * Creates a named item with non-italic bold white display name and grey lore.
     */
    private fun makeNamedItem(material: Material, amount: Int, name: String, loreLine: String): ItemStack {
        val stack = ItemStack(material, amount)
        val meta = stack.itemMeta ?: return stack

        // Strip legacy § codes and build Adventure component directly
        val cleanName = name.replace(Regex("§[0-9a-fk-orA-FK-OR]"), "")
        val cleanLore = loreLine.replace(Regex("§[0-9a-fk-orA-FK-OR]"), "")

        meta.displayName(
            Component.text(cleanName)
                .color(net.kyori.adventure.text.format.NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true)
        )
        meta.lore(listOf(
            Component.text(cleanLore)
                .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ))

        stack.itemMeta = meta
        return stack
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
