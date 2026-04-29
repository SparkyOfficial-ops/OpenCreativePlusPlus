@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-visual-programming-platform, Property 6: Round-trip AST с поршневыми скобками

package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.plugin.serialization.CodeLineSerializer
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
 * Property 6: Round-trip AST с поршневыми скобками
 *
 * For any valid piston-scoped program, scan → serialize → deserialize SHALL produce
 * a structurally equivalent CodeLine: same nodes and same `children` at every nesting level.
 *
 * Feature: ocp-visual-programming-platform, Property 6: Round-trip AST с поршневыми скобками
 * Validates: Requirements 2.8
 */
class ASTRoundTripPistonPropertyTest : FreeSpec({

    val serializer = CodeLineSerializer()

    // -----------------------------------------------------------------------
    // Arbitraries
    // -----------------------------------------------------------------------

    val arbMaterial = Arb.element(
        Material.COBBLESTONE, Material.OAK_PLANKS, Material.OBSIDIAN,
        Material.IRON_BLOCK, Material.BRICK, Material.STICKY_PISTON, Material.PISTON
    )

    val arbCoord = Arb.int(-64..64)
    val arbY     = Arb.int(0..64)

    fun loc(x: Int, y: Int, z: Int): Location = Location(null, x.toDouble(), y.toDouble(), z.toDouble())

    val arbNode = arbitrary {
        ScannedNode(
            blockType  = arbMaterial.bind(),
            location   = loc(arbCoord.bind(), arbY.bind(), arbCoord.bind()),
            parameters = mapOf(Arb.string(1..6).bind() to Arb.string(1..8).bind()),
            nodeId     = Arb.string(1..8).bind()
        )
    }

    val arbNodes = Arb.list(arbNode, 0..3)

    /** Flat CodeLine — no children (represents a node sequence with no piston scope). */
    val arbFlat = arbitrary {
        CodeLine(
            startLocation = loc(arbCoord.bind(), arbY.bind(), arbCoord.bind()),
            nodes         = arbNodes.bind(),
            children      = emptyList()
        )
    }

    /** CodeLine with depth-1 piston children (one level of piston scope). */
    val arbDepth1 = arbitrary {
        val childCount = Arb.int(1..3).bind()
        val children = (0 until childCount).map {
            CodeLine(
                startLocation = loc(arbCoord.bind(), arbY.bind(), arbCoord.bind()),
                nodes         = arbNodes.bind(),
                children      = emptyList()
            )
        }
        CodeLine(
            startLocation = loc(arbCoord.bind(), arbY.bind(), arbCoord.bind()),
            nodes         = arbNodes.bind(),
            children      = children
        )
    }

    /** CodeLine with depth-2 nested piston children (grandchildren). */
    val arbDepth2 = arbitrary {
        val outerChildCount = Arb.int(1..2).bind()
        val outerChildren = (0 until outerChildCount).map {
            val innerChildCount = Arb.int(1..2).bind()
            val innerChildren = (0 until innerChildCount).map {
                CodeLine(
                    startLocation = loc(arbCoord.bind(), arbY.bind(), arbCoord.bind()),
                    nodes         = arbNodes.bind(),
                    children      = emptyList()
                )
            }
            CodeLine(
                startLocation = loc(arbCoord.bind(), arbY.bind(), arbCoord.bind()),
                nodes         = arbNodes.bind(),
                children      = innerChildren
            )
        }
        CodeLine(
            startLocation = loc(arbCoord.bind(), arbY.bind(), arbCoord.bind()),
            nodes         = arbNodes.bind(),
            children      = outerChildren
        )
    }

    // -----------------------------------------------------------------------
    // Structural equality helpers (world ref is null after deserialization)
    // -----------------------------------------------------------------------

    fun locEq(a: Location, b: Location): Boolean =
        a.blockX == b.blockX && a.blockY == b.blockY && a.blockZ == b.blockZ

    fun nodeEq(a: ScannedNode, b: ScannedNode): Boolean =
        a.blockType == b.blockType &&
        a.nodeId    == b.nodeId    &&
        locEq(a.location, b.location) &&
        a.parameters.keys == b.parameters.keys &&
        a.parameters.keys.all { a.parameters[it].toString() == b.parameters[it].toString() }

    fun clEq(a: CodeLine, b: CodeLine): Boolean {
        if (!locEq(a.startLocation, b.startLocation)) return false
        if (a.nodes.size != b.nodes.size) return false
        if (!a.nodes.zip(b.nodes).all { (na, nb) -> nodeEq(na, nb) }) return false
        if (a.children.size != b.children.size) return false
        return a.children.zip(b.children).all { (ca, cb) -> clEq(ca, cb) }
    }

    // -----------------------------------------------------------------------
    // Property 6a: Flat CodeLine round-trip
    // -----------------------------------------------------------------------

    "Property 6a: flat CodeLine (no piston children) round-trips correctly" - {
        "nodes are preserved after serialize → deserialize" {
            // Feature: ocp-visual-programming-platform, Property 6: Round-trip AST с поршневыми скобками
            // Validates: Requirements 2.8
            checkAll(PropTestConfig(iterations = 20), arbFlat) { cl ->
                val doc      = serializer.serializeCodeLine(cl)
                val restored = serializer.deserializeCodeLine(doc)

                restored shouldNotBe null
                clEq(cl, restored!!) shouldBe true
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 6b: Depth-1 piston children round-trip
    // -----------------------------------------------------------------------

    "Property 6b: CodeLine with depth-1 piston children round-trips correctly" - {
        "children count and their nodes are preserved after serialize → deserialize" {
            // Feature: ocp-visual-programming-platform, Property 6: Round-trip AST с поршневыми скобками
            // Validates: Requirements 2.8
            checkAll(PropTestConfig(iterations = 20), arbDepth1) { cl ->
                val doc      = serializer.serializeCodeLine(cl)
                val restored = serializer.deserializeCodeLine(doc)

                restored shouldNotBe null
                restored!!.children.size shouldBe cl.children.size
                clEq(cl, restored) shouldBe true
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 6c: Depth-2 nested piston children round-trip
    // -----------------------------------------------------------------------

    "Property 6c: CodeLine with depth-2 nested piston children round-trips correctly" - {
        "grandchildren are preserved after serialize → deserialize" {
            // Feature: ocp-visual-programming-platform, Property 6: Round-trip AST с поршневыми скобками
            // Validates: Requirements 2.8
            checkAll(PropTestConfig(iterations = 20), arbDepth2) { cl ->
                val doc      = serializer.serializeCodeLine(cl)
                val restored = serializer.deserializeCodeLine(doc)

                restored shouldNotBe null
                clEq(cl, restored!!) shouldBe true
                // Verify grandchildren are present
                restored.children.forEach { child ->
                    child.children.size shouldBe cl.children
                        .first { clEq(it, child) }.children.size
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 6d: Idempotency — serialize(deserialize(serialize(cl))) == serialize(cl)
    // -----------------------------------------------------------------------

    "Property 6d: round-trip is idempotent for piston-scoped CodeLines" - {
        "serialize(deserialize(serialize(cl))).toJson() == serialize(cl).toJson()" {
            // Feature: ocp-visual-programming-platform, Property 6: Round-trip AST с поршневыми скобками
            // Validates: Requirements 2.8
            checkAll(PropTestConfig(iterations = 20), arbDepth1) { cl ->
                val doc1     = serializer.serializeCodeLine(cl)
                val restored = serializer.deserializeCodeLine(doc1)
                restored shouldNotBe null
                val doc2 = serializer.serializeCodeLine(restored!!)

                doc1.toJson() shouldBe doc2.toJson()
            }
        }
    }
})
