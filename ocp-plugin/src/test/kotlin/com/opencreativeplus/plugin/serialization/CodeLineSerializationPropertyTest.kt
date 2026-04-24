// Feature: ocp-manifest-roadmap, Property 8: Piston System round-trip
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.serialization

import com.opencreativeplus.plugin.scanner.CodeLine
import com.opencreativeplus.plugin.scanner.ScannedNode
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import org.bukkit.Location
import org.bukkit.Material

/**
 * Property 8: Piston System round-trip (CodeLine serialization)
 *
 * For any CodeLine with arbitrary nesting, serialize → deserialize SHALL produce
 * a structurally equivalent object.
 *
 * Feature: ocp-manifest-roadmap, Property 8: Piston System round-trip
 * Validates: Requirements 8.2, 8.5
 */
class CodeLineSerializationPropertyTest : FreeSpec({

    val serializer = CodeLineSerializer()

    // -----------------------------------------------------------------------
    // Arbitraries
    // -----------------------------------------------------------------------

    val arbMaterial = Arb.element(
        listOf(
            Material.COBBLESTONE, Material.OAK_PLANKS, Material.OBSIDIAN,
            Material.IRON_BLOCK, Material.DIAMOND_BLOCK, Material.BRICK,
            Material.PAPER, Material.STONE
        )
    )

    val arbCoord = Arb.int(-512..512)
    val arbY = Arb.int(0..255)

    fun makeLocation(x: Int, y: Int, z: Int): Location = Location(null, x.toDouble(), y.toDouble(), z.toDouble())

    val arbScannedNode = arbitrary { rs ->
        val x = arbCoord.bind()
        val y = arbY.bind()
        val z = arbCoord.bind()
        val material = arbMaterial.bind()
        val nodeId = Arb.string(1..10).bind()
        val paramKey = Arb.string(1..8).bind()
        val paramVal = Arb.string(1..10).bind()
        ScannedNode(
            blockType = material,
            location = makeLocation(x, y, z),
            parameters = mapOf(paramKey to paramVal),
            nodeId = nodeId
        )
    }

    val arbNodeList = Arb.list(arbScannedNode, 0..4)

    // Flat CodeLine (no children)
    val arbFlatCodeLine = arbitrary { rs ->
        val x = arbCoord.bind()
        val y = arbY.bind()
        val z = arbCoord.bind()
        val nodes = arbNodeList.bind()
        CodeLine(startLocation = makeLocation(x, y, z), nodes = nodes, children = emptyList())
    }

    // CodeLine with depth-1 children
    val arbNestedCodeLine = arbitrary { rs ->
        val x = arbCoord.bind()
        val y = arbY.bind()
        val z = arbCoord.bind()
        val nodes = arbNodeList.bind()
        val childCount = Arb.int(0..3).bind()
        val children = (0 until childCount).map {
            val cx = arbCoord.bind()
            val cy = arbY.bind()
            val cz = arbCoord.bind()
            val childNodes = arbNodeList.bind()
            CodeLine(startLocation = makeLocation(cx, cy, cz), nodes = childNodes, children = emptyList())
        }
        CodeLine(startLocation = makeLocation(x, y, z), nodes = nodes, children = children)
    }

    // -----------------------------------------------------------------------
    // Helpers for structural equality (ignoring world reference)
    // -----------------------------------------------------------------------

    fun locEquals(a: Location, b: Location): Boolean =
        a.blockX == b.blockX && a.blockY == b.blockY && a.blockZ == b.blockZ

    fun nodeEquals(a: ScannedNode, b: ScannedNode): Boolean =
        a.blockType == b.blockType &&
        a.nodeId == b.nodeId &&
        locEquals(a.location, b.location) &&
        a.parameters.keys == b.parameters.keys &&
        a.parameters.keys.all { a.parameters[it].toString() == b.parameters[it].toString() }

    fun codeLineEquals(a: CodeLine, b: CodeLine): Boolean {
        if (!locEquals(a.startLocation, b.startLocation)) return false
        if (a.nodes.size != b.nodes.size) return false
        if (!a.nodes.zip(b.nodes).all { (na, nb) -> nodeEquals(na, nb) }) return false
        if (a.children.size != b.children.size) return false
        return a.children.zip(b.children).all { (ca, cb) -> codeLineEquals(ca, cb) }
    }

    // -----------------------------------------------------------------------
    // Property 8a: Flat CodeLine round-trip
    // -----------------------------------------------------------------------

    "Property 8a: flat CodeLine serialize → deserialize produces equivalent object" - {
        "for any CodeLine without children, round-trip preserves all fields" {
            // Feature: ocp-manifest-roadmap, Property 8: Piston System round-trip
            checkAll(PropTestConfig(iterations = 100), arbFlatCodeLine) { codeLine ->
                val doc = serializer.serializeCodeLine(codeLine)
                val restored = serializer.deserializeCodeLine(doc)

                restored shouldNotBe null
                codeLineEquals(codeLine, restored!!) shouldBe true
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8b: Nested CodeLine round-trip
    // -----------------------------------------------------------------------

    "Property 8b: nested CodeLine serialize → deserialize preserves full structure" - {
        "for any CodeLine with depth-1 children, round-trip preserves nodes and children" {
            // Feature: ocp-manifest-roadmap, Property 8: Piston System round-trip
            checkAll(PropTestConfig(iterations = 100), arbNestedCodeLine) { codeLine ->
                val doc = serializer.serializeCodeLine(codeLine)
                val restored = serializer.deserializeCodeLine(doc)

                restored shouldNotBe null
                codeLineEquals(codeLine, restored!!) shouldBe true
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8c: Idempotency — serialize(deserialize(serialize(cl))) == serialize(cl)
    // -----------------------------------------------------------------------

    "Property 8c: round-trip is idempotent — serialize(deserialize(serialize(cl))) == serialize(cl)" - {
        "double round-trip produces the same document as single round-trip" {
            // Feature: ocp-manifest-roadmap, Property 8: Piston System round-trip
            checkAll(PropTestConfig(iterations = 100), arbNestedCodeLine) { codeLine ->
                val doc1 = serializer.serializeCodeLine(codeLine)
                val restored = serializer.deserializeCodeLine(doc1)
                restored shouldNotBe null
                val doc2 = serializer.serializeCodeLine(restored!!)

                // Both documents should be structurally equal
                doc1.toJson() shouldBe doc2.toJson()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8d: Empty CodeLine round-trip
    // -----------------------------------------------------------------------

    "Property 8d: empty CodeLine (no nodes, no children) round-trips correctly" - {
        "an empty CodeLine serializes and deserializes without data loss" {
            // Feature: ocp-manifest-roadmap, Property 8: Piston System round-trip
            checkAll(PropTestConfig(iterations = 100), arbCoord, arbY, arbCoord) { x, y, z ->
                val empty = CodeLine(startLocation = makeLocation(x, y, z))
                val doc = serializer.serializeCodeLine(empty)
                val restored = serializer.deserializeCodeLine(doc)

                restored shouldNotBe null
                restored!!.nodes shouldBe emptyList()
                restored.children shouldBe emptyList()
                locEquals(empty.startLocation, restored.startLocation) shouldBe true
            }
        }
    }
})
