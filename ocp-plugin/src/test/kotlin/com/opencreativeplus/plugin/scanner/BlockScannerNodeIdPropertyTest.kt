// Feature: category-based-coding-ui, Property 8: PDC priority over block.type in BlockScanner
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.registry.NodeRegistry
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.TileState
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

/**
 * Property 8: PDC priority over block.type in BlockScanner
 *
 * For any block that has `ocp:action_id` in its PDC, `BlockScanner.buildScannedNode`
 * must set `ScannedNode.nodeId` to the PDC value, regardless of `block.type`.
 * For any block that does not have `ocp:action_id` in its PDC, `nodeId` must be
 * resolved via the existing Material-based registry lookup.
 *
 * **Validates: Requirements 4.2, 4.3, 7.1**
 */
class BlockScannerNodeIdPropertyTest : FreeSpec({

    val world = mockk<World>(relaxed = true)

    val arbActionId = Arb.string(1..30).filter { it.isNotBlank() }
    val arbMaterial = Arb.of(
        Material.COBBLESTONE, Material.STONE_BRICKS, Material.GOLD_BLOCK,
        Material.IRON_BLOCK, Material.OAK_PLANKS, Material.DIAMOND_BLOCK
    )

    fun dummyActionFactory(): (Map<String, Any>) -> IAction {
        val action = mockk<IAction>(relaxed = true)
        return { action }
    }

    fun makeBlockWithPDCActionId(material: Material, actionId: String): Block {
        val block = mockk<Block>(relaxed = true)
        val location = Location(world, 0.0, 1.0, 0.0)
        every { block.type } returns material
        every { block.location } returns location
        val tileState = mockk<TileState>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { block.state } returns tileState
        every { tileState.persistentDataContainer } returns pdc
        val actionIdKey = NamespacedKey("ocp", "action_id")
        every { pdc.get(actionIdKey, PersistentDataType.STRING) } returns actionId
        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val air = mockk<Block>(relaxed = true)
            every { air.state } returns mockk(relaxed = true)
            every { block.getRelative(face) } returns air
        }
        val airAbove = mockk<Block>(relaxed = true)
        every { airAbove.type } returns Material.AIR
        every { airAbove.state } returns mockk(relaxed = true)
        every { block.getRelative(BlockFace.UP) } returns airAbove
        return block
    }

    fun makeBlockWithoutPDCActionId(material: Material): Block {
        val block = mockk<Block>(relaxed = true)
        val location = Location(world, 0.0, 1.0, 0.0)
        every { block.type } returns material
        every { block.location } returns location
        every { block.state } returns mockk(relaxed = true)
        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val air = mockk<Block>(relaxed = true)
            every { air.state } returns mockk(relaxed = true)
            every { block.getRelative(face) } returns air
        }
        val airAbove = mockk<Block>(relaxed = true)
        every { airAbove.type } returns Material.AIR
        every { airAbove.state } returns mockk(relaxed = true)
        every { block.getRelative(BlockFace.UP) } returns airAbove
        return block
    }

    "Property 8a: when ocp:action_id is in PDC, buildScannedNode sets nodeId to the PDC value" - {
        "for any registered action_id and any block material, nodeId equals the PDC value" {
            // Validates: Requirements 4.1, 4.2
            checkAll(PropTestConfig(iterations = 100), arbActionId, arbMaterial) { actionId, material ->
                val nodeRegistry = mockk<NodeRegistry>(relaxed = true)
                every { nodeRegistry.getActionFactoryById(actionId) } returns dummyActionFactory()
                val scanner = BlockScanner(world, nodeRegistry)
                val block = makeBlockWithPDCActionId(material, actionId)
                val node = scanner.buildScannedNode(block)
                node shouldNotBe null
                node?.nodeId shouldBe actionId
            }
        }
    }

    "Property 8b: block.type does not affect nodeId when ocp:action_id is in PDC" - {
        "for any two different materials with the same action_id, nodeId is the same" {
            // Validates: Requirements 4.2
            checkAll(PropTestConfig(iterations = 100), arbActionId) { actionId ->
                val nodeRegistry = mockk<NodeRegistry>(relaxed = true)
                every { nodeRegistry.getActionFactoryById(actionId) } returns dummyActionFactory()
                val scanner = BlockScanner(world, nodeRegistry)
                val node1 = scanner.buildScannedNode(makeBlockWithPDCActionId(Material.COBBLESTONE, actionId))
                val node2 = scanner.buildScannedNode(makeBlockWithPDCActionId(Material.GOLD_BLOCK, actionId))
                node1 shouldNotBe null
                node2 shouldNotBe null
                node1?.nodeId shouldBe actionId
                node2?.nodeId shouldBe actionId
            }
        }
    }

    "Property 8c: when action_id is present from sign, buildScannedNode returns a ScannedNode with that nodeId" - {
        "for any action_id from a sign, the block is included with that nodeId" {
            // Validates: Requirements 4.2, 4.5
            // The scanner trusts the sign's action_id — factory validation happens at compile time.
            checkAll(PropTestConfig(iterations = 100), arbActionId, arbMaterial) { actionId, material ->
                val nodeRegistry = mockk<NodeRegistry>(relaxed = true)
                val scanner = BlockScanner(world, nodeRegistry)
                val node = scanner.buildScannedNode(makeBlockWithPDCActionId(material, actionId))
                node shouldNotBe null
                node!!.nodeId shouldBe actionId
            }
        }
    }

    "Property 8d: when ocp:action_id is absent, nodeId is resolved via Material-based registry" - {
        "for any material with a registered nodeId, nodeId equals the material's registered nodeId" {
            // Validates: Requirements 4.3, 7.1
            checkAll(PropTestConfig(iterations = 100), arbActionId, arbMaterial) { materialNodeId, material ->
                val nodeRegistry = mockk<NodeRegistry>(relaxed = true)
                every { nodeRegistry.getActionNodeId(material) } returns materialNodeId
                every { nodeRegistry.getConditionNodeId(material) } returns null
                every { nodeRegistry.getValueNodeId(material) } returns null
                // Explicitly stub event factory methods to return null for DIAMOND_BLOCK branch
                every { nodeRegistry.getEventFactoryById(any()) } returns null
                every { nodeRegistry.getEventFactory(any()) } returns null
                val scanner = BlockScanner(world, nodeRegistry)
                val node = scanner.buildScannedNode(makeBlockWithoutPDCActionId(material))
                node shouldNotBe null
                node?.nodeId shouldBe materialNodeId
            }
        }
    }

    "Property 8e: nodeId is null when no PDC action_id and material is not registered" - {
        "for any unregistered material without PDC, nodeId is null" {
            // Validates: Requirements 4.3, 7.1
            checkAll(PropTestConfig(iterations = 100), arbMaterial) { material ->
                val nodeRegistry = mockk<NodeRegistry>(relaxed = true)
                every { nodeRegistry.getActionNodeId(any()) } returns null
                every { nodeRegistry.getConditionNodeId(any()) } returns null
                every { nodeRegistry.getValueNodeId(any()) } returns null
                // Explicitly stub event factory methods to return null for DIAMOND_BLOCK branch
                every { nodeRegistry.getEventFactoryById(any()) } returns null
                every { nodeRegistry.getEventFactory(any()) } returns null
                val scanner = BlockScanner(world, nodeRegistry)
                val node = scanner.buildScannedNode(makeBlockWithoutPDCActionId(material))
                // When material is not registered and no PDC action_id, buildScannedNode returns null
                node shouldBe null
            }
        }
    }
})
