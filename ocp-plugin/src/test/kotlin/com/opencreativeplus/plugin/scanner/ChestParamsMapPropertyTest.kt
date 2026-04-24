// Feature: category-based-coding-ui, Property 13: Chest params map building
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.registry.NodeRegistry
import com.opencreativeplus.plugin.registry.ActionDescriptor
import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.registry.NodeCategory
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Chest
import org.bukkit.block.TileState
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

/**
 * Property 13: Chest params map building
 *
 * For any chest above a Category_Block marked with `ocp:param_chest`,
 * `BlockScanner.extractParameters` must build a `params` map where:
 *  - items with `ocp:item_var_type = "variable"` contribute `paramKey → varName`
 *  - items with `ocp:item_var_type = "location"` contribute `paramKey → "loc:<name>"`
 *  - plain items contribute `paramKey → material.name`
 * No chest item must be silently dropped.
 *
 * **Validates: Requirements 9.5, 9.6, 9.7, 9.8**
 */
class ChestParamsMapPropertyTest : FreeSpec({

    val KEY_PARAM_CHEST = NamespacedKey("ocp", "param_chest")
    val KEY_ITEM_VAR_TYPE = NamespacedKey("ocp", "item_var_type")
    val KEY_ITEM_VAR_NAME = NamespacedKey("ocp", "item_var_name")

    val world = mockk<World>(relaxed = true)
    val nodeRegistry = mockk<NodeRegistry>(relaxed = true)
    val categoryRegistry = CategoryRegistry()
    val scanner = BlockScanner(world, nodeRegistry, pluginNamespace = "opencreativeplus", categoryRegistry = categoryRegistry)

    // Arbitrary generators
    val arbName = Arb.string(1..30).filter { it.isNotBlank() && !it.contains('\u0000') }
    val arbParamKey = Arb.of(listOf("target", "message", "value", "count", "location", "speed", "mode", "radius", "delay", "power"))
    val arbMaterial = Arb.of(listOf(Material.STONE, Material.DIRT, Material.SAND, Material.GRAVEL, Material.OAK_LOG, Material.IRON_ORE))

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Build a mocked variable item (ocp:item_var_type = "variable", ocp:item_var_name = name). */
    fun mockVariableItem(name: String, material: Material = Material.PAPER): ItemStack {
        val item = mockk<ItemStack>(relaxed = true)
        val meta = mockk<ItemMeta>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { item.type } returns material
        every { item.itemMeta } returns meta
        every { meta.persistentDataContainer } returns pdc
        every { pdc.get(KEY_ITEM_VAR_TYPE, PersistentDataType.STRING) } returns "variable"
        every { pdc.get(KEY_ITEM_VAR_NAME, PersistentDataType.STRING) } returns name
        return item
    }

    /** Build a mocked location item (ocp:item_var_type = "location", ocp:item_var_name = name). */
    fun mockLocationItem(name: String, material: Material = Material.COMPASS): ItemStack {
        val item = mockk<ItemStack>(relaxed = true)
        val meta = mockk<ItemMeta>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { item.type } returns material
        every { item.itemMeta } returns meta
        every { meta.persistentDataContainer } returns pdc
        every { pdc.get(KEY_ITEM_VAR_TYPE, PersistentDataType.STRING) } returns "location"
        every { pdc.get(KEY_ITEM_VAR_NAME, PersistentDataType.STRING) } returns name
        return item
    }

    /** Build a mocked plain item (no PDC var type). */
    fun mockPlainItem(material: Material): ItemStack {
        val item = mockk<ItemStack>(relaxed = true)
        val meta = mockk<ItemMeta>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { item.type } returns material
        every { item.itemMeta } returns meta
        every { meta.persistentDataContainer } returns pdc
        every { pdc.get(KEY_ITEM_VAR_TYPE, PersistentDataType.STRING) } returns null
        every { pdc.get(KEY_ITEM_VAR_NAME, PersistentDataType.STRING) } returns null
        return item
    }

    /**
     * Build a mocked Category_Block with a param chest above it.
     * [chestContents] is a list of (slot, item) pairs — slots not in the list are null.
     * [paramKeys] is the expectedParams list for the ActionDescriptor.
     */
    fun makeBlockWithParamChest(
        chestContents: List<ItemStack?>,
        paramKeys: List<String>,
        actionId: String = "test_action"
    ): Block {
        // Register the descriptor (ignore if already registered)
        if (categoryRegistry.getDescriptorById(actionId) == null) {
            categoryRegistry.register(
                ActionDescriptor(
                    id = actionId,
                    displayName = "Test Action",
                    icon = Material.STONE,
                    category = NodeCategory.PLAYER_ACTION,
                    expectedParams = paramKeys
                )
            )
        }

        val categoryBlock = mockk<Block>(relaxed = true)

        // Block above is a Chest
        val chestBlock = mockk<Block>(relaxed = true)
        val chestState = mockk<Chest>(relaxed = true)
        val chestPdc = mockk<PersistentDataContainer>(relaxed = true)
        val inventory = mockk<Inventory>(relaxed = true)

        every { categoryBlock.getRelative(BlockFace.UP) } returns chestBlock
        every { chestBlock.state } returns chestState
        every { (chestState as TileState).persistentDataContainer } returns chestPdc
        every { chestPdc.get(KEY_PARAM_CHEST, PersistentDataType.STRING) } returns "true"
        every { chestState.inventory } returns inventory

        // Build the contents array: size = max(chestContents.size, 27)
        val size = maxOf(chestContents.size, 1)
        val contentsArray = arrayOfNulls<ItemStack>(size)
        chestContents.forEachIndexed { idx, item -> contentsArray[idx] = item }
        every { inventory.contents } returns contentsArray

        // Block itself: not a TileState (no PDC action_id needed for extractParameters)
        every { categoryBlock.state } returns mockk(relaxed = true)

        // No signs on horizontal faces
        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val air = mockk<Block>(relaxed = true)
            every { air.state } returns mockk(relaxed = true)
            every { categoryBlock.getRelative(face) } returns air
        }

        return categoryBlock
    }

    // -----------------------------------------------------------------------
    // Property 13a: variable items contribute paramKey → varName
    // -----------------------------------------------------------------------

    "Property 13a: variable items contribute paramKey → varName" - {
        "for any variable name, the params map must contain the varName as the value" {
            // Validates: Requirements 9.5, 9.7
            checkAll(PropTestConfig(iterations = 20), arbName, arbParamKey) { varName, paramKey ->
                val actionId = "var_test_${paramKey}_${varName.take(5).replace(" ", "_")}"
                val item = mockVariableItem(varName)
                val block = makeBlockWithParamChest(
                    chestContents = listOf(item),
                    paramKeys = listOf(paramKey),
                    actionId = actionId
                )
                val descriptor = categoryRegistry.getDescriptorById(actionId)!!
                val params = scanner.extractParameters(block, descriptor)

                params shouldContainKey paramKey
                params[paramKey] shouldBe varName
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 13b: location items contribute paramKey → "loc:<name>"
    // -----------------------------------------------------------------------

    "Property 13b: location items contribute paramKey → 'loc:<name>'" - {
        "for any location name, the params map must contain 'loc:<name>' as the value" {
            // Validates: Requirements 9.5, 9.8
            checkAll(PropTestConfig(iterations = 20), arbName, arbParamKey) { locName, paramKey ->
                val actionId = "loc_test_${paramKey}_${locName.take(5).replace(" ", "_")}"
                val item = mockLocationItem(locName)
                val block = makeBlockWithParamChest(
                    chestContents = listOf(item),
                    paramKeys = listOf(paramKey),
                    actionId = actionId
                )
                val descriptor = categoryRegistry.getDescriptorById(actionId)!!
                val params = scanner.extractParameters(block, descriptor)

                params shouldContainKey paramKey
                params[paramKey] shouldBe "loc:$locName"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 13c: plain items contribute paramKey → material.name
    // -----------------------------------------------------------------------

    "Property 13c: plain items contribute paramKey → material.name" - {
        "for any plain item, the params map must contain the material name as the value" {
            // Validates: Requirements 9.5, 9.6
            checkAll(PropTestConfig(iterations = 20), arbMaterial, arbParamKey) { material, paramKey ->
                val actionId = "plain_test_${paramKey}_${material.name}"
                val item = mockPlainItem(material)
                val block = makeBlockWithParamChest(
                    chestContents = listOf(item),
                    paramKeys = listOf(paramKey),
                    actionId = actionId
                )
                val descriptor = categoryRegistry.getDescriptorById(actionId)!!
                val params = scanner.extractParameters(block, descriptor)

                params shouldContainKey paramKey
                params[paramKey] shouldBe material.name
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 13d: items are mapped to expectedParams keys by slot index order
    // -----------------------------------------------------------------------

    "Property 13d: items are mapped to expectedParams keys by ascending slot index" - {
        "for N items in slots 0..N-1, each item maps to expectedParams[slotIndex]" {
            // Validates: Requirements 9.5, 9.6
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.list(arbParamKey, 2..5).filter { it.distinct().size == it.size },
                arbName
            ) { paramKeys, baseName ->
                val actionId = "order_test_${paramKeys.joinToString("_").take(20)}"
                // Build items: alternating variable and plain
                val items = paramKeys.mapIndexed { idx, _ ->
                    if (idx % 2 == 0) mockVariableItem("${baseName}_$idx")
                    else mockPlainItem(Material.STONE)
                }
                val block = makeBlockWithParamChest(
                    chestContents = items,
                    paramKeys = paramKeys,
                    actionId = actionId
                )
                val descriptor = categoryRegistry.getDescriptorById(actionId)!!
                val params = scanner.extractParameters(block, descriptor)

                // Each paramKey must be present
                paramKeys.forEachIndexed { idx, key ->
                    params shouldContainKey key
                    val expectedValue = if (idx % 2 == 0) "${baseName}_$idx" else Material.STONE.name
                    params[key] shouldBe expectedValue
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 13e: no chest item is silently dropped
    // -----------------------------------------------------------------------

    "Property 13e: no chest item is silently dropped — all items in slots 0..N-1 appear in params" - {
        "for any N items and N expectedParams, all N entries must appear in the result map" {
            // Validates: Requirements 9.5, 9.6
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1..8),
                arbName
            ) { n, baseName ->
                val paramKeys = (0 until n).map { "param_$it" }
                val actionId = "nodrop_test_n${n}_${baseName.take(5).replace(" ", "_")}"
                val items = (0 until n).map { idx ->
                    when (idx % 3) {
                        0 -> mockVariableItem("${baseName}_$idx")
                        1 -> mockLocationItem("${baseName}_loc_$idx")
                        else -> mockPlainItem(Material.DIRT)
                    }
                }
                val block = makeBlockWithParamChest(
                    chestContents = items,
                    paramKeys = paramKeys,
                    actionId = actionId
                )
                val descriptor = categoryRegistry.getDescriptorById(actionId)!!
                val params = scanner.extractParameters(block, descriptor)

                // All N param keys must be present — nothing dropped
                params.size shouldBe n
                paramKeys.forEach { key -> params shouldContainKey key }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 13f: mixed item types in a single chest are all resolved correctly
    // -----------------------------------------------------------------------

    "Property 13f: mixed variable, location, and plain items are all resolved correctly" - {
        "a chest with one of each type must produce correct values for all three" {
            // Validates: Requirements 9.5, 9.6, 9.7, 9.8
            checkAll(PropTestConfig(iterations = 20), arbName) { name ->
                val paramKeys = listOf("p_var", "p_loc", "p_plain")
                val actionId = "mixed_test_${name.take(8).replace(" ", "_")}"
                val items = listOf(
                    mockVariableItem(name),
                    mockLocationItem(name),
                    mockPlainItem(Material.SAND)
                )
                val block = makeBlockWithParamChest(
                    chestContents = items,
                    paramKeys = paramKeys,
                    actionId = actionId
                )
                val descriptor = categoryRegistry.getDescriptorById(actionId)!!
                val params = scanner.extractParameters(block, descriptor)

                params["p_var"] shouldBe name
                params["p_loc"] shouldBe "loc:$name"
                params["p_plain"] shouldBe Material.SAND.name
            }
        }
    }
})
