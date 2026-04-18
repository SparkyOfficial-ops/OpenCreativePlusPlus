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
import io.kotest.property.arbitrary.string
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

/**
 * Property 23: PDC priority over sign data
 * Validates: s 20.3
 *
 * For any parameter key that appears in both sign data and PDC,
 * extractParameters must return the PDC value, not the sign value.
 */
class PDCPriorityPropertyTest : FreeSpec({

    val world = mockk<World>(relaxed = true)
    val nodeRegistry = mockk<NodeRegistry>(relaxed = true)
    val scanner = BlockScanner(world, nodeRegistry, pluginNamespace = "opencreativeplus")

    // Arbitrary for valid parameter key names (non-empty, NamespacedKey allows [a-z0-9/._-])
    // Use a fixed set of valid keys to avoid OOM from excessive filter rejection
    val arbKey = Arb.of(listOf("speed", "count", "mode", "target", "value", "name", "type", "radius", "delay", "power"))

    // Arbitrary for string values (non-empty, no '=' to avoid sign parse ambiguity, no '$' to avoid VariableReference)
    val arbValue = Arb.string(1..20)
        .filter { it.isNotBlank() && !it.contains('=') && !it.contains('\n') && !it.startsWith('$') }

    // Arbitrary for a pair of distinct values (signValue != pdcValue)
    val arbDistinctValues = Arb.pair(arbValue, arbValue).filter { (a, b) -> a != b }

    /**
     * Build a node block that has:
     * - A sign on NORTH face with line "key=signValue"
     * - Its own TileState PDC with key "key" -> pdcValue
     * - Air on all other faces and above
     */
    fun makeBlockWithSignAndPDC(key: String, signValue: String, pdcValue: String): Block {
        val nodeBlock = mockk<Block>(relaxed = true)

        // Sign on NORTH
        val signBlock = mockk<Block>(relaxed = true)
        val signState = mockk<Sign>(relaxed = true)
        every { signBlock.state } returns signState
        every { signState.lines } returns arrayOf("$key=$signValue", "", "", "")
        every { nodeBlock.getRelative(BlockFace.NORTH) } returns signBlock

        // Air on other horizontal faces
        for (face in listOf(BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val air = mockk<Block>(relaxed = true)
            every { air.state } returns mockk(relaxed = true)
            every { nodeBlock.getRelative(face) } returns air
        }

        // Air above (no chest)
        val airAbove = mockk<Block>(relaxed = true)
        every { airAbove.state } returns mockk(relaxed = true)
        every { nodeBlock.getRelative(BlockFace.UP) } returns airAbove

        // PDC on the block itself
        val tileState = mockk<TileState>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { nodeBlock.state } returns tileState
        every { tileState.persistentDataContainer } returns pdc

        val nsKey = NamespacedKey("ocp", key)
        every { pdc.keys } returns setOf(nsKey)
        every { pdc.get(nsKey, PersistentDataType.STRING) } returns pdcValue
        every { pdc.get(nsKey, PersistentDataType.INTEGER) } returns null
        every { pdc.get(nsKey, PersistentDataType.DOUBLE) } returns null

        return nodeBlock
    }

    /**
     * Build a node block that has ONLY sign data (no PDC / non-TileState).
     */
    fun makeBlockWithSignOnly(key: String, signValue: String): Block {
        val nodeBlock = mockk<Block>(relaxed = true)

        val signBlock = mockk<Block>(relaxed = true)
        val signState = mockk<Sign>(relaxed = true)
        every { signBlock.state } returns signState
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

        // Not a TileState → readPDCParams returns emptyMap
        every { nodeBlock.state } returns mockk(relaxed = true)

        return nodeBlock
    }

    /**
     * Build a node block that has ONLY PDC data (no signs).
     */
    fun makeBlockWithPDCOnly(key: String, pdcValue: String): Block {
        val nodeBlock = mockk<Block>(relaxed = true)

        // All horizontal faces are air (no signs)
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
        every { pdc.get(nsKey, PersistentDataType.STRING) } returns pdcValue
        every { pdc.get(nsKey, PersistentDataType.INTEGER) } returns null
        every { pdc.get(nsKey, PersistentDataType.DOUBLE) } returns null

        return nodeBlock
    }

    // -----------------------------------------------------------------------
    // Property 23a: PDC value wins over sign value for the same key
    // -----------------------------------------------------------------------

    "Property 23a: PDC value overrides sign value for the same parameter key" - {
        "for any key and distinct sign/PDC values, extractParameters returns the PDC value" {
            checkAll(PropTestConfig(iterations = 100), arbKey, arbDistinctValues) { key, (signValue, pdcValue) ->
                val block = makeBlockWithSignAndPDC(key, signValue, pdcValue)
                val params = scanner.extractParameters(block)

                params[key] shouldBe pdcValue
                params[key] shouldNotBe signValue
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 23b: sign value is preserved when PDC has no overlapping key
    // -----------------------------------------------------------------------

    "Property 23b: sign value is preserved when PDC does not contain the same key" - {
        "for any key and value, sign-only block returns the sign value" {
            checkAll(PropTestConfig(iterations = 100), arbKey, arbValue) { key, signValue ->
                val block = makeBlockWithSignOnly(key, signValue)
                val params = scanner.extractParameters(block)

                // parseValue may convert numeric strings; compare via toString for robustness
                params[key]?.toString() shouldBe signValue
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 23c: PDC-only value is present when no sign data exists
    // -----------------------------------------------------------------------

    "Property 23c: PDC-only value is returned when no sign data exists" - {
        "for any key and value, PDC-only block returns the PDC value" {
            checkAll(PropTestConfig(iterations = 100), arbKey, arbValue) { key, pdcValue ->
                val block = makeBlockWithPDCOnly(key, pdcValue)
                val params = scanner.extractParameters(block)

                params[key] shouldBe pdcValue
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 23d: PDC value is idempotent — same key queried twice returns same value
    // -----------------------------------------------------------------------

    "Property 23d: extractParameters is deterministic — same block returns same result on repeated calls" - {
        "for any key with both sign and PDC data, two calls return identical maps" {
            checkAll(PropTestConfig(iterations = 100), arbKey, arbDistinctValues) { key, (signValue, pdcValue) ->
                val block = makeBlockWithSignAndPDC(key, signValue, pdcValue)
                val first = scanner.extractParameters(block)
                val second = scanner.extractParameters(block)

                first[key] shouldBe second[key]
            }
        }
    }
})
