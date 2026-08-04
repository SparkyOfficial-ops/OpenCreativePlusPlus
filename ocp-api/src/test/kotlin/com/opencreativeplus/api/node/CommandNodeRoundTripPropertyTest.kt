@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.api.node

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property-based tests for [CommandNode] serialization round-trip.
 *
 * Property: serialize(deserialize(serialize(node))) == serialize(node)
 * for any [CommandNode].
 *
 * Validates: Requirements 8.6
 */
class CommandNodeRoundTripPropertyTest : FreeSpec({

    val arbNodeType: Arb<NodeType> = Arb.element(NodeType.entries)

    val arbNodeId: Arb<String> = Arb.string(1..30)

    // Params values limited to String to stay MongoDB-compatible and avoid
    // type-erasure issues at the Map<String,Any> boundary.
    val arbParams: Arb<Map<String, Any>> = Arb.map(
        keyArb = Arb.string(1..15),
        valueArb = Arb.string(1..30),
        maxSize = 5
    ).map { it as Map<String, Any> }

    // -----------------------------------------------------------------------
    // Property 8a — single serialize→deserialize→serialize is idempotent
    // -----------------------------------------------------------------------

    "Property 8a: toMap() then fromMap() round-trips to an equal CommandNode" - {

        "fromMap(toMap(node)) == node for all NodeType values" {
            // Req 8.6
            checkAll(PropTestConfig(iterations = 20), arbNodeType, arbNodeId, arbParams) {
                    type, nodeId, params ->
                val node = CommandNode(type = type, nodeId = nodeId, params = params)
                val restored = CommandNode.fromMap(node.toMap())
                restored shouldBe node
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8b — double round-trip: serialize(deserialize(serialize(node))) == serialize(node)
    // -----------------------------------------------------------------------

    "Property 8b: double round-trip — serialize(deserialize(serialize(node))) == serialize(node)" - {

        "toMap(fromMap(toMap(node))) == toMap(node)" {
            // Req 8.6 — the canonical property from the spec
            checkAll(PropTestConfig(iterations = 20), arbNodeType, arbNodeId, arbParams) {
                    type, nodeId, params ->
                val node = CommandNode(type = type, nodeId = nodeId, params = params)
                val firstSerialized = node.toMap()
                val deserialized = CommandNode.fromMap(firstSerialized)
                val secondSerialized = deserialized.toMap()
                secondSerialized shouldBe firstSerialized
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8c — empty params is preserved
    // -----------------------------------------------------------------------

    "Property 8c: empty params map survives round-trip" - {

        "node with empty params round-trips correctly" {
            // Req 8.6
            checkAll(PropTestConfig(iterations = 20), arbNodeType, arbNodeId) { type, nodeId ->
                val node = CommandNode(type = type, nodeId = nodeId, params = emptyMap())
                CommandNode.fromMap(node.toMap()) shouldBe node
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8d — nodeId is preserved exactly (no truncation or mutation)
    // -----------------------------------------------------------------------

    "Property 8d: nodeId is preserved exactly through serialization" - {

        "nodeId after round-trip equals original nodeId" {
            // Req 8.6
            checkAll(PropTestConfig(iterations = 20), arbNodeType, arbNodeId) { type, nodeId ->
                val node = CommandNode(type = type, nodeId = nodeId)
                val restored = CommandNode.fromMap(node.toMap())
                restored.nodeId shouldBe node.nodeId
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8e — NodeType is preserved exactly
    // -----------------------------------------------------------------------

    "Property 8e: NodeType is preserved exactly through serialization" - {

        "type after round-trip equals original type" {
            // Req 8.6
            checkAll(PropTestConfig(iterations = 20), arbNodeType, arbNodeId) { type, nodeId ->
                val node = CommandNode(type = type, nodeId = nodeId)
                val restored = CommandNode.fromMap(node.toMap())
                restored.type shouldBe node.type
            }
        }
    }
})
