@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.model.ItemVariableRef
import com.opencreativeplus.api.model.ItemVariableType
import com.opencreativeplus.api.registry.NodeRegistry
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Barrel
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

/**
 * Property 9: Item Variable PDC detection round-trip
 * Validates: s 4.2, 4.3
 *
 * For any ItemVariableType and variable name, placing an Item_Variable item in the chest
 * above a block must result in BlockScanner.extractParameters returning an ItemVariableRef
 * with the correct name and type.
 */
class ItemVariablePropertyTest : FreeSpec({

    val world = mockk<World>(relaxed = true)
    val nodeRegistry = mockk<NodeRegistry>(relaxed = true)
    val scanner = BlockScanner(world, nodeRegistry, pluginNamespace = "opencreativeplus")

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    fun mockItemVariableStack(varType: String, varName: String?): ItemStack {
        val item = mockk<ItemStack>(relaxed = true)
        val meta = mockk<ItemMeta>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { item.itemMeta } returns meta
        every { meta.persistentDataContainer } returns pdc
        val typeKey = NamespacedKey("opencreativeplus", "variable_type")
        val nameKey = NamespacedKey("opencreativeplus", "variable_name")
        every { pdc.get(typeKey, PersistentDataType.STRING) } returns varType
        every { pdc.get(nameKey, PersistentDataType.STRING) } returns varName
        return item
    }

    fun makeNodeBlock(chestItems: Array<ItemStack?>): Block {
        val nodeBlock = mockk<Block>(relaxed = true)
        // No PDC (not a TileState)
        every { nodeBlock.state } returns mockk(relaxed = true)
        // No signs on horizontal faces
        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val air = mockk<Block>(relaxed = true)
            every { air.state } returns mockk(relaxed = true)
            every { nodeBlock.getRelative(face) } returns air
        }
        // Barrel above (production code checks for Barrel, not Chest)
        val chestBlock = mockk<Block>(relaxed = true)
        val chestState = mockk<Barrel>(relaxed = true)
        val inventory = mockk<Inventory>(relaxed = true)
        every { chestBlock.state } returns chestState
        every { chestState.inventory } returns inventory
        every { inventory.contents } returns chestItems
        every { nodeBlock.getRelative(BlockFace.UP) } returns chestBlock
        return nodeBlock
    }

    fun makeNodeBlockNoChest(): Block {
        val nodeBlock = mockk<Block>(relaxed = true)
        every { nodeBlock.state } returns mockk(relaxed = true)
        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val air = mockk<Block>(relaxed = true)
            every { air.state } returns mockk(relaxed = true)
            every { nodeBlock.getRelative(face) } returns air
        }
        val airAbove = mockk<Block>(relaxed = true)
        every { airAbove.state } returns mockk(relaxed = true)
        every { nodeBlock.getRelative(BlockFace.UP) } returns airAbove
        return nodeBlock
    }

    // -----------------------------------------------------------------------
    // Arbitraries
    // -----------------------------------------------------------------------

    val arbType = Arb.element(ItemVariableType.values().toList())
    val arbVarName = Arb.string(1..30).filter { it.isNotBlank() }
    val arbInvalidType = Arb.string(1..20).filter {
        runCatching { ItemVariableType.valueOf(it.uppercase()) }.isFailure
    }

    // -----------------------------------------------------------------------
    // Property 9a: single item round-trip
    // -----------------------------------------------------------------------

    "Property 9a: single Item_Variable item is detected with correct name and type" - {
        "for any ItemVariableType and variable name, extractParameters returns correct ItemVariableRef" {
            // s 4.2, 4.3
            checkAll(PropTestConfig(iterations = 100), arbType, arbVarName) { type, name ->
                val item = mockItemVariableStack(type.name, name)
                val nodeBlock = makeNodeBlock(arrayOf(item))
                val params = scanner.extractParameters(nodeBlock)
                val key = "item_var_${type.name.lowercase()}"
                params shouldContainKey key
                val ref = params[key]
                ref.shouldBeInstanceOf<ItemVariableRef>()
                ref.name shouldBe name
                ref.type shouldBe type
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 9b: multiple distinct types all detected
    // -----------------------------------------------------------------------

    "Property 9b: multiple Item_Variable items of distinct types are all detected" - {
        "for any subset of distinct ItemVariableTypes, all are present in extractParameters result" {
            // s 4.2, 4.3
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(arbType, 1..5),
                Arb.list(arbVarName, 5..5)
            ) { types, names ->
                // Deduplicate by type to avoid key collisions
                val distinctEntries = types.zip(names).distinctBy { it.first }
                val items = distinctEntries.map { (type, name) ->
                    mockItemVariableStack(type.name, name)
                }.toTypedArray<ItemStack?>()

                val nodeBlock = makeNodeBlock(items)
                val params = scanner.extractParameters(nodeBlock)

                distinctEntries.forEach { (type, name) ->
                    val key = "item_var_${type.name.lowercase()}"
                    params shouldContainKey key
                    val ref = params[key]
                    ref.shouldBeInstanceOf<ItemVariableRef>()
                    ref.name shouldBe name
                    ref.type shouldBe type
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 9c: item with null variable name is ignored
    // -----------------------------------------------------------------------

    "Property 9c: Item_Variable item with null variable name is ignored" - {
        "extractParameters must not contain a key for an item missing variable_name" {
            // s 4.2
            checkAll(PropTestConfig(iterations = 100), arbType) { type ->
                val item = mockItemVariableStack(type.name, null)
                val nodeBlock = makeNodeBlock(arrayOf(item))
                val params = scanner.extractParameters(nodeBlock)
                val key = "item_var_${type.name.lowercase()}"
                params shouldNotContainKey key
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 9d: item with unknown variable type is ignored
    // -----------------------------------------------------------------------

    "Property 9d: Item_Variable item with unknown variable type is ignored" - {
        "extractParameters must not contain any item_var_ key for an unrecognised type string" {
            // s 4.3
            checkAll(PropTestConfig(iterations = 100), arbInvalidType, arbVarName) { invalidType, name ->
                val item = mockItemVariableStack(invalidType, name)
                val nodeBlock = makeNodeBlock(arrayOf(item))
                val params = scanner.extractParameters(nodeBlock)
                val key = "item_var_${invalidType.lowercase()}"
                params shouldNotContainKey key
            }
        }
    }
})
