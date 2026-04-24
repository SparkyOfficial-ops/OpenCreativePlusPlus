// Feature: category-based-coding-ui, Property 3: Provisioned inventory category slots
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.inventory

import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.opencreativeplus.core.database.MongoConnectionManager
import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.registry.NodeCategory
import com.opencreativeplus.plugin.registry.NodeRegistryImpl
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.PropTestConfig
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import io.mockk.*
import org.bson.Document
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import java.util.UUID

/**
 * Property 3: Provisioned inventory category slots
 *
 * For any call to provisionDevInventory, slots 0–5 of the resulting inventory must contain
 * exactly the six NodeCategory materials in the canonical order, each with quantity 64,
 * and each with a non-blank display name equal to the category's russianLabel.
 *
 * **Validates: Requirements 2.2, 2.5**
 *
 * Feature: category-based-coding-ui, Property 3: Provisioned inventory category slots
 */
class ProvisionedInventoryPropertyTest : FreeSpec({

    fun buildSetup(): Triple<InventoryManager, Player, MutableMap<Int, ItemStack>> {
        val database = mockk<MongoDatabase>(relaxed = true)
        val collection = mockk<MongoCollection<Document>>(relaxed = true)
        val connectionManager = mockk<MongoConnectionManager>(relaxed = true)
        val nodeRegistry = NodeRegistryImpl()
        val categoryRegistry = CategoryRegistry()
        val manager = InventoryManager(database, connectionManager, nodeRegistry, collection, categoryRegistry)

        val setItems = mutableMapOf<Int, ItemStack>()
        val inventory = mockk<PlayerInventory>(relaxed = true)
        every { inventory.contents } returns arrayOfNulls(36)
        every { inventory.armorContents } returns arrayOfNulls(4)
        every { inventory.itemInOffHand } returns ItemStack(Material.AIR)
        every { inventory.setItem(any<Int>(), any()) } answers {
            setItems[firstArg<Int>()] = secondArg()
        }
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.inventory } returns inventory

        return Triple(manager, player, setItems)
    }

    "Property 3: slots 0-5 contain NodeCategory materials with quantity 64 and russianLabel" - {

        // Validates: Requirements 2.2, 2.5
        "for any call to provisionDevInventory, slots 0-5 have correct materials and quantities" {
            // Feature: category-based-coding-ui, Property 3: Provisioned inventory category slots
            checkAll(PropTestConfig(iterations = 20), Arb.element(NodeCategory.entries)) { category ->
                val (manager, player, setItems) = buildSetup()

                manager.provisionDevInventory(player)

                val index = NodeCategory.entries.indexOf(category)
                val item = setItems[index]
                item shouldNotBe null
                item!!.type shouldBe category.material
                item.amount shouldBe 64
            }
        }

        // Validates: Requirements 2.5
        "for any call to provisionDevInventory, category block display names equal russianLabel" {
            // Feature: category-based-coding-ui, Property 3: Provisioned inventory category slots
            checkAll(PropTestConfig(iterations = 20), Arb.element(NodeCategory.entries)) { category ->
                val (manager, player, setItems) = buildSetup()

                manager.provisionDevInventory(player)

                val index = NodeCategory.entries.indexOf(category)
                val item = setItems[index]
                item shouldNotBe null
                @Suppress("DEPRECATION")
                val displayName = item!!.itemMeta?.displayName
                displayName shouldNotBe null
                displayName!!.isBlank() shouldBe false
                displayName shouldBe category.russianLabel
            }
        }

        // Validates: Requirements 2.1
        "provisionDevInventory always clears inventory before provisioning" {
            // Feature: category-based-coding-ui, Property 3: Provisioned inventory category slots
            checkAll(PropTestConfig(iterations = 20), Arb.element(NodeCategory.entries)) { _ ->
                val (manager, player, _) = buildSetup()

                manager.provisionDevInventory(player)

                verify { player.inventory.clear() }
            }
        }

        // Validates: Requirements 2.6
        "provisioned slots never exceed index 35" {
            // Feature: category-based-coding-ui, Property 3: Provisioned inventory category slots
            checkAll(PropTestConfig(iterations = 20), Arb.element(NodeCategory.entries)) { _ ->
                val (manager, player, setItems) = buildSetup()

                manager.provisionDevInventory(player)

                setItems.keys.all { it < 36 } shouldBe true
            }
        }
    }
})
