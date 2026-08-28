@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.registry.NodeRegistry
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.block.TileState
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

class PDCPriorityPropertyTest : FreeSpec({

    val world = mockk<World>(relaxed = true)
    val nodeRegistry = mockk<NodeRegistry>(relaxed = true)
    val scanner = BlockScanner(world, nodeRegistry, pluginNamespace = "opencreativeplus")

    // Fixed sets — avoids OOM from Arb.string.filter shrinking
    val arbKey = Arb.of(listOf("speed", "count", "mode", "target", "value", "name", "type", "radius", "delay", "power"))
    val arbValue = Arb.of(listOf("hello", "world", "foo", "bar", "baz", "test", "abc", "xyz", "val1", "val2"))
    val arbDistinctValues = Arb.pair(arbValue, arbValue).filter { (a, b) -> a != b }

    fun makeBlockWithSignAndPDC(key: String, signValue: String, pdcValue: String): Block {
        val nodeBlock = mockk<Block>(relaxed = true)
        val signBlock = mockk<Block>(relaxed = true)
        val signState = mockk<Sign>(relaxed = true)
        every { signBlock.state } returns signState
        @Suppress("DEPRECATION")
        every { signState.lines } returns arrayOf("$key=$signValue", "", "", "")
        every { nodeBlock.getRelative(BlockFace.NORTH) } returns signBlock
        for (face in listOf(BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val air = mockk<Block>(relaxed = true)
            every { air.state } returns mockk(relaxed = true)
            every { nodeBlock.getRelative(face) } returns air
        }
        val airAbove = mockk<Block>(relaxed = true)
        every { airAbove.state } returns mockk(relaxed = true)
        every { nodeBlock.getRelative(BlockFace.UP) } returns airAbove
        val tileState = mockk<TileState>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { nodeBlock.state } returns tileState
        every { tileState.persistentDataContainer } returns pdc
        val nsKey = NamespacedKey("ocp", key)
        every { pdc.keys } returns setOf(nsKey)
        // Default: all STRING gets return null (prevents _ocp_type lookup returning Object)
        every { pdc.get(any(), any<PersistentDataType<String, String>>()) } returns null
        every { pdc.get(nsKey, PersistentDataType.STRING) } returns pdcValue
        every { pdc.get(nsKey, PersistentDataType.INTEGER) } returns null
        every { pdc.get(nsKey, PersistentDataType.DOUBLE) } returns null
        every { pdc.get(nsKey, PersistentDataType.BYTE) } returns null
        return nodeBlock
    }

    fun makeBlockWithSignOnly(key: String, signValue: String): Block {
        val nodeBlock = mockk<Block>(relaxed = true)
        val signBlock = mockk<Block>(relaxed = true)
        val signState = mockk<Sign>(relaxed = true)
        every { signBlock.state } returns signState
        @Suppress("DEPRECATION")
        every { signState.lines } returns arrayOf("$key=$signValue", "", "", "")
        every { nodeBlock.getRelative(BlockFace.NORTH) } returns signBlock
        for (face in listOf(BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val air = mockk<Block>(relaxed = true)
            every { air.state } returns mockk(relaxed = true)
            every { nodeBlock.getRelative(face) } returns air
        }
        val airAbove = mockk<Block>(relaxed = true)
        every { airAbove.state } returns mockk(relaxed = true)
        every { nodeBlock.getRelative(BlockFace.UP) } returns airAbove
        every { nodeBlock.state } returns mockk(relaxed = true)
        return nodeBlock
    }

    fun makeBlockWithPDCOnly(key: String, pdcValue: String): Block {
        val nodeBlock = mockk<Block>(relaxed = true)
        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val air = mockk<Block>(relaxed = true)
            every { air.state } returns mockk(relaxed = true)
            every { nodeBlock.getRelative(face) } returns air
        }
        val airAbove = mockk<Block>(relaxed = true)
        every { airAbove.state } returns mockk(relaxed = true)
        every { nodeBlock.getRelative(BlockFace.UP) } returns airAbove
        val tileState = mockk<TileState>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { nodeBlock.state } returns tileState
        every { tileState.persistentDataContainer } returns pdc
        val nsKey = NamespacedKey("ocp", key)
        every { pdc.keys } returns setOf(nsKey)
        // Default: all STRING gets return null (prevents _ocp_type lookup returning Object)
        every { pdc.get(any(), any<PersistentDataType<String, String>>()) } returns null
        every { pdc.get(nsKey, PersistentDataType.STRING) } returns pdcValue
        every { pdc.get(nsKey, PersistentDataType.INTEGER) } returns null
        every { pdc.get(nsKey, PersistentDataType.DOUBLE) } returns null
        every { pdc.get(nsKey, PersistentDataType.BYTE) } returns null
        return nodeBlock
    }

    "Property 23a: PDC value overrides sign value for the same parameter key" - {
        "for any key and distinct sign/PDC values, extractParameters returns the PDC value" {
            checkAll(PropTestConfig(iterations = 20), arbKey, arbDistinctValues) { key, (signValue, pdcValue) ->
                    val block = makeBlockWithSignAndPDC(key, signValue, pdcValue)
                    val params = scanner.extractParameters(block)
                    params[key] shouldBe pdcValue
                    params[key] shouldNotBe signValue
            }
        }
    }

    "Property 23b: sign value is preserved when PDC does not contain the same key" - {
        "for any key and value, sign-only block returns the sign value" {
            checkAll(PropTestConfig(iterations = 20), arbKey, arbValue) { key, signValue ->
                    val block = makeBlockWithSignOnly(key, signValue)
                    val params = scanner.extractParameters(block)
                    params[key]?.toString() shouldBe signValue
            }
        }
    }

    "Property 23c: PDC-only value is returned when no sign data exists" - {
        "for any key and value, PDC-only block returns the PDC value" {
            checkAll(PropTestConfig(iterations = 20), arbKey, arbValue) { key, pdcValue ->
                    val block = makeBlockWithPDCOnly(key, pdcValue)
                    val params = scanner.extractParameters(block)
                    params[key] shouldBe pdcValue
            }
        }
    }

    "Property 23d: extractParameters is deterministic — same block returns same result on repeated calls" - {
        "for any key with both sign and PDC data, two calls return identical maps" {
            checkAll(PropTestConfig(iterations = 20), arbKey, arbDistinctValues) { key, (signValue, pdcValue) ->
                    val block = makeBlockWithSignAndPDC(key, signValue, pdcValue)
                    val first = scanner.extractParameters(block)
                    val second = scanner.extractParameters(block)
                    first[key] shouldBe second[key]
            }
        }
    }
})
