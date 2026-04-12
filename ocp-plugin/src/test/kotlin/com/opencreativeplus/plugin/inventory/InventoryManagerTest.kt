package com.opencreativeplus.plugin.inventory

import com.mongodb.client.model.ReplaceOptions
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.core.database.MongoConnectionManager
import com.opencreativeplus.plugin.registry.NodeRegistryImpl
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.bson.Document
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/
 * Unit tests for InventoryManager covering:
 * - Inventory save and restore (serialization/deserialization)
 * - Mode-specific provisioning (BUILD, DEV, PLAY)
 * - Three-state separation (each mode stored/retrieved independently)
 *
14.1, 14.5
 */
class InventoryManagerTest {

    private lateinit var database: MongoDatabase
    private lateinit var collection: MongoCollection<Document>
    private lateinit var connectionManager: MongoConnectionManager
    private lateinit var inventoryManager: InventoryManager

    @BeforeEach
    fun setup() {
        database = mockk(relaxed = true)
        collection = mockk(relaxed = true)
        // Use relaxed mock — withRetry will return null by default (no real implementation called)
        connectionManager = mockk(relaxed = true)

        every { database.getCollection<Document>("player_inventories") } returns collection

        // Make withRetry execute the block for save operations (returns UpdateResult mock)
        // and return null for load operations (no document found by default)
        coEvery { connectionManager.withRetry<Any?>(any<suspend () -> Any?>()) } coAnswers {
            val block = firstArg<suspend () -> Any?>()
            block()
        }
        coEvery { connectionManager.withRetry<Any?>(any<Int>(), any<suspend () -> Any?>()) } coAnswers {
            val block = secondArg<suspend () -> Any?>()
            block()
        }

        val registry = NodeRegistryImpl()
        inventoryManager = InventoryManager(database, connectionManager, registry)
    }
    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun mockPlayer(id: UUID = UUID.randomUUID()): Player {
        val inventory = mockk<PlayerInventory>(relaxed = true)
        every { inventory.contents } returns arrayOfNulls(36)
        every { inventory.armorContents } returns arrayOfNulls(4)
        every { inventory.itemInOffHand } returns ItemStack(Material.AIR)

        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns id
        every { player.inventory } returns inventory
        return player
    }

    private fun emptyFindFlow() = mockk<com.mongodb.kotlin.client.coroutine.FindFlow<Document>>(relaxed = true)

    // -------------------------------------------------------------------------
    // Save inventory —  14.5
    // -------------------------------------------------------------------------

    @Test
    fun `saveInventory calls replaceOne with correct document fields`() = runTest {
        val playerId = UUID.randomUUID()
        val plotId = UUID.randomUUID()
        val player = mockPlayer(id = playerId)

        coEvery {
            collection.replaceOne(any(), any(), any<ReplaceOptions>())
        } returns mockk(relaxed = true)

        inventoryManager.saveInventory(player, plotId, PlotMode.BUILD)

        coVerify {
            collection.replaceOne(
                match<Document> { it.getString("_id") == "$playerId:$plotId:BUILD" },
                match<Document> { doc ->
                    doc.getString("player_id") == playerId.toString() &&
                    doc.getString("plot_id") == plotId.toString() &&
                    doc.getString("mode") == "BUILD" &&
                    doc.containsKey("contents") &&
                    doc.containsKey("armor") &&
                    doc.containsKey("offhand") &&
                    doc.containsKey("saved_at")
                },
                any<ReplaceOptions>()
            )
        }
    }

    @Test
    fun `saveInventory uses mode name in document key for DEV mode`() = runTest {
        val playerId = UUID.randomUUID()
        val plotId = UUID.randomUUID()
        val player = mockPlayer(id = playerId)

        coEvery { collection.replaceOne(any(), any(), any<ReplaceOptions>()) } returns mockk(relaxed = true)

        inventoryManager.saveInventory(player, plotId, PlotMode.DEV)

        coVerify {
            collection.replaceOne(
                match<Document> { it.getString("_id") == "$playerId:$plotId:DEV" },
                any(),
                any<ReplaceOptions>()
            )
        }
    }

    @Test
    fun `saveInventory stores contents as non-empty Base64 string`() = runTest {
        val plotId = UUID.randomUUID()
        val player = mockPlayer()

        val capturedDoc = slot<Document>()
        coEvery { collection.replaceOne(any(), capture(capturedDoc), any<ReplaceOptions>()) } returns mockk(relaxed = true)

        inventoryManager.saveInventory(player, plotId, PlotMode.PLAY)

        val contentsField = capturedDoc.captured.getString("contents")
        assertNotNull(contentsField, "contents field should be present in saved document")
        assertTrue(contentsField.isNotEmpty(), "contents field should be a non-empty Base64 string")
    }

    // -------------------------------------------------------------------------
    // Load inventory — s 14.2, 14.3, 14.4
    // -------------------------------------------------------------------------

    @Test
    fun `loadInventory clears player inventory when no saved state exists`() = runTest {
        val plotId = UUID.randomUUID()
        val player = mockPlayer()
        every { collection.find(any<Document>()) } returns emptyFindFlow()

        inventoryManager.loadInventory(player, plotId, PlotMode.BUILD)

        verify { player.inventory.clear() }
    }

    @Test
    fun `loadInventory queries collection with correct key for PLAY mode`() = runTest {
        val playerId = UUID.randomUUID()
        val plotId = UUID.randomUUID()
        val player = mockPlayer(id = playerId)

        val findFilter = slot<Document>()
        every { collection.find(capture(findFilter)) } returns emptyFindFlow()

        inventoryManager.loadInventory(player, plotId, PlotMode.PLAY)

        assertEquals("$playerId:$plotId:PLAY", findFilter.captured.getString("_id"),
            "loadInventory must query with the correct mode-specific key")
    }

    @Test
    fun `loadInventory applies saved contents to player inventory when document exists`() = runTest {
        val plotId = UUID.randomUUID()
        val player = mockPlayer()

        // Step 1: capture the document produced by saveInventory
        val capturedDoc = slot<Document>()
        coEvery { collection.replaceOne(any(), capture(capturedDoc), any<ReplaceOptions>()) } returns mockk(relaxed = true)
        inventoryManager.saveInventory(player, plotId, PlotMode.BUILD)
        val savedDoc = capturedDoc.captured

        // Step 2: make withRetry return the saved document for the load call
        coEvery { connectionManager.withRetry(block = any<suspend () -> Any?>()) } returns savedDoc

        inventoryManager.loadInventory(player, plotId, PlotMode.BUILD)

        verify { player.inventory.clear() }
        verify { player.inventory.contents = any() }
        verify { player.inventory.armorContents = any() }
        verify { player.inventory.setItemInOffHand(any()) }
    }

    // -------------------------------------------------------------------------
    // Three-state separation —  14.1
    // -------------------------------------------------------------------------

    @Test
    fun `saveInventory uses distinct keys for BUILD, DEV, and PLAY modes`() = runTest {
        val playerId = UUID.randomUUID()
        val plotId = UUID.randomUUID()
        val player = mockPlayer(id = playerId)

        val capturedKeys = mutableListOf<String>()
        coEvery { collection.replaceOne(any(), any(), any<ReplaceOptions>()) } coAnswers {
            capturedKeys.add(firstArg<Document>().getString("_id"))
            mockk(relaxed = true)
        }

        inventoryManager.saveInventory(player, plotId, PlotMode.BUILD)
        inventoryManager.saveInventory(player, plotId, PlotMode.DEV)
        inventoryManager.saveInventory(player, plotId, PlotMode.PLAY)

        assertEquals(3, capturedKeys.size, "Should have saved 3 separate inventory states")
        assertEquals(
            setOf(
                "$playerId:$plotId:BUILD",
                "$playerId:$plotId:DEV",
                "$playerId:$plotId:PLAY"
            ),
            capturedKeys.toSet(),
            "Each mode must use a distinct inventory key ( 14.1)"
        )
    }

    @Test
    fun `loadInventory queries distinct keys for each mode`() = runTest {
        val playerId = UUID.randomUUID()
        val plotId = UUID.randomUUID()
        val player = mockPlayer(id = playerId)

        val queriedKeys = mutableListOf<String>()
        every { collection.find(any<Document>()) } answers {
            queriedKeys.add(firstArg<Document>().getString("_id"))
            emptyFindFlow()
        }

        inventoryManager.loadInventory(player, plotId, PlotMode.BUILD)
        inventoryManager.loadInventory(player, plotId, PlotMode.DEV)
        inventoryManager.loadInventory(player, plotId, PlotMode.PLAY)

        assertEquals(3, queriedKeys.size, "Should have queried 3 separate inventory states")
        assertEquals(
            setOf(
                "$playerId:$plotId:BUILD",
                "$playerId:$plotId:DEV",
                "$playerId:$plotId:PLAY"
            ),
            queriedKeys.toSet(),
            "Each mode must query a distinct inventory key ( 14.1)"
        )
    }

    @Test
    fun `different players on same plot have independent inventory keys`() = runTest {
        val playerIdA = UUID.randomUUID()
        val playerIdB = UUID.randomUUID()
        val plotId = UUID.randomUUID()
        val playerA = mockPlayer(id = playerIdA)
        val playerB = mockPlayer(id = playerIdB)

        val capturedKeys = mutableListOf<String>()
        coEvery { collection.replaceOne(any(), any(), any<ReplaceOptions>()) } coAnswers {
            capturedKeys.add(firstArg<Document>().getString("_id"))
            mockk(relaxed = true)
        }

        inventoryManager.saveInventory(playerA, plotId, PlotMode.BUILD)
        inventoryManager.saveInventory(playerB, plotId, PlotMode.BUILD)

        assertEquals(2, capturedKeys.size)
        assertTrue(capturedKeys[0] != capturedKeys[1], "Different players must have different inventory keys")
        assertTrue(capturedKeys[0].startsWith(playerIdA.toString()))
        assertTrue(capturedKeys[1].startsWith(playerIdB.toString()))
    }

    @Test
    fun `same player on different plots have independent inventory keys`() = runTest {
        val playerId = UUID.randomUUID()
        val plotIdA = UUID.randomUUID()
        val plotIdB = UUID.randomUUID()
        val player = mockPlayer(id = playerId)

        val capturedKeys = mutableListOf<String>()
        coEvery { collection.replaceOne(any(), any(), any<ReplaceOptions>()) } coAnswers {
            capturedKeys.add(firstArg<Document>().getString("_id"))
            mockk(relaxed = true)
        }

        inventoryManager.saveInventory(player, plotIdA, PlotMode.BUILD)
        inventoryManager.saveInventory(player, plotIdB, PlotMode.BUILD)

        assertEquals(2, capturedKeys.size)
        assertTrue(capturedKeys[0] != capturedKeys[1], "Same player on different plots must have different inventory keys")
        assertTrue(capturedKeys[0].contains(plotIdA.toString()))
        assertTrue(capturedKeys[1].contains(plotIdB.toString()))
    }

    // -------------------------------------------------------------------------
    // DEV mode provisioning — s 36.1–36.4
    // -------------------------------------------------------------------------

    @Test
    fun `provisionDevInventory clears player inventory before provisioning`() {
        val player = mockPlayer()

        inventoryManager.provisionDevInventory(player)

        verify { player.inventory.clear() }
    }

    @Test
    fun `provisionDevInventory provides glass blocks for grid extension`() {
        val player = mockPlayer()
        val inventory = player.inventory

        val setItems = mutableMapOf<Int, ItemStack>()
        every { inventory.setItem(any<Int>(), any()) } answers {
            setItems[firstArg<Int>()] = secondArg()
        }

        inventoryManager.provisionDevInventory(player)

        val materials = setItems.values.map { it.type }.toSet()
        assertTrue(Material.BLUE_STAINED_GLASS in materials, "DEV inventory must include BLUE_STAINED_GLASS (Req 36.2)")
        assertTrue(Material.WHITE_STAINED_GLASS in materials, "DEV inventory must include WHITE_STAINED_GLASS (Req 36.2)")
        assertTrue(Material.GRAY_STAINED_GLASS in materials, "DEV inventory must include GRAY_STAINED_GLASS (Req 36.2)")
    }

    @Test
    fun `provisionDevInventory provides signs and chests for parameter configuration`() {
        val player = mockPlayer()
        val inventory = player.inventory

        val setItems = mutableMapOf<Int, ItemStack>()
        every { inventory.setItem(any<Int>(), any()) } answers {
            setItems[firstArg<Int>()] = secondArg()
        }

        inventoryManager.provisionDevInventory(player)

        val materials = setItems.values.map { it.type }.toSet()
        assertTrue(Material.OAK_SIGN in materials, "DEV inventory must include OAK_SIGN (Req 36.3)")
        assertTrue(Material.CHEST in materials, "DEV inventory must include CHEST (Req 36.3)")
    }

    @Test
    fun `provisionDevInventory provides registered action node blocks`() {
        val player = mockPlayer()
        val inventory = player.inventory

        val setItems = mutableMapOf<Int, ItemStack>()
        every { inventory.setItem(any<Int>(), any()) } answers {
            setItems[firstArg<Int>()] = secondArg()
        }

        val registry = NodeRegistryImpl()
        registry.registerAction(Material.PAPER) { _ ->
            object : IAction {
                override val nodeId = "send_message"
                override val displayName = "Send Message"
                override suspend fun execute(context: ExecutionContext) {}
            }
        }
        registry.registerAction(Material.CLOCK) { _ ->
            object : IAction {
                override val nodeId = "wait"
                override val displayName = "Wait"
                override suspend fun execute(context: ExecutionContext) {}
            }
        }

        val manager = InventoryManager(database, connectionManager, registry)
        manager.provisionDevInventory(player)

        val materials = setItems.values.map { it.type }.toSet()
        assertTrue(Material.PAPER in materials, "DEV inventory must include registered action PAPER (Req 36.1)")
        assertTrue(Material.CLOCK in materials, "DEV inventory must include registered action CLOCK (Req 36.1)")
    }

    @Test
    fun `provisionDevInventory provides items in stacks of 64`() {
        val player = mockPlayer()
        val inventory = player.inventory

        val setItems = mutableMapOf<Int, ItemStack>()
        every { inventory.setItem(any<Int>(), any()) } answers {
            setItems[firstArg<Int>()] = secondArg()
        }

        inventoryManager.provisionDevInventory(player)

        setItems.values.forEach { item ->
            assertEquals(64, item.amount, "All provisioned items should be in stacks of 64 (Req 36.4)")
        }
    }

    @Test
    fun `provisionDevInventory does not exceed 36 inventory slots`() {
        val player = mockPlayer()
        val inventory = player.inventory

        val setItems = mutableMapOf<Int, ItemStack>()
        every { inventory.setItem(any<Int>(), any()) } answers {
            setItems[firstArg<Int>()] = secondArg()
        }

        inventoryManager.provisionDevInventory(player)

        assertTrue(setItems.keys.all { it < 36 }, "Items must only be placed in slots 0–35")
    }
}
