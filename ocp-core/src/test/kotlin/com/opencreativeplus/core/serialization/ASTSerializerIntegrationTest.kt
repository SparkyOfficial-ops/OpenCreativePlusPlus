package com.opencreativeplus.core.serialization

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.INode
import com.opencreativeplus.api.registry.NodeRegistry
import io.mockk.every
import io.mockk.mockk
import org.bson.Document
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Integration test for [ASTSerializer]: full save → restart → load cycle.
 *
 * Simulates a server restart by:
 * 1. Serializing a node to a Document (save phase)
 * 2. Discarding the original node instance (simulating restart)
 * 3. Creating a fresh [ASTSerializer] with a fresh registry (simulating post-restart state)
 * 4. Deserializing the Document back to a node (load phase)
 *
 * Validates: Requirements 2.2, 3.2 (Bug 2 fix — ASTSerializer integration)
 *
 * Note: Uses in-memory Document storage (no real MongoDB required).
 * For full MongoDB integration, see the @Disabled variant below.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ASTSerializerIntegrationTest {

    // -----------------------------------------------------------------------
    // Test node
    // -----------------------------------------------------------------------

    private class SendMessageAction(private val params: Map<String, Any>) : IAction {
        override val nodeId = "send_message"
        override val displayName = "Send Message"
        override fun getParams(): Map<String, Any> = params
        override suspend fun execute(context: ExecutionContext) {}
    }

    // -----------------------------------------------------------------------
    // Helper: build a registry with send_message registered on PAPER
    // -----------------------------------------------------------------------

    private fun buildRegistry(): NodeRegistry {
        val registry = mockk<NodeRegistry>()
        every { registry.getActionFactory(Material.PAPER) } returns { params -> SendMessageAction(params) }
        every { registry.getConditionFactory(Material.PAPER) } returns null
        every { registry.getValueFactory(Material.PAPER) } returns null
        every { registry.getMaterialForNode(match { it.nodeId == "send_message" }) } returns Material.PAPER
        return registry
    }

    // -----------------------------------------------------------------------
    // Integration test: save → restart → load
    // -----------------------------------------------------------------------

    @Test
    fun `full cycle - serialize node, simulate restart, deserialize returns correct node`() {
        // --- PHASE 1: Save (pre-restart) ---
        val preRestartRegistry = buildRegistry()
        val preRestartSerializer = ASTSerializer(preRestartRegistry)

        val originalNode = SendMessageAction(mapOf("message" to "Welcome!", "color" to "GREEN"))
        val savedDocument: Document = preRestartSerializer.serialize(originalNode)
            ?: fail("serialize() returned null — node not registered")

        // Verify the document was saved correctly
        assertEquals("PAPER", savedDocument.getString("nodeId"))
        val savedParams = savedDocument.get("params", Document::class.java)
        assertNotNull(savedParams)
        assertEquals("Welcome!", savedParams!!.getString("message"))
        assertEquals("GREEN", savedParams.getString("color"))

        // --- PHASE 2: Restart simulation ---
        // The original node instance is gone. A fresh registry and serializer are created.
        val postRestartRegistry = buildRegistry()
        val postRestartSerializer = ASTSerializer(postRestartRegistry)

        // --- PHASE 3: Load (post-restart) ---
        val restoredNode: INode? = postRestartSerializer.deserialize(savedDocument)

        // Then: node is restored correctly
        assertNotNull(restoredNode, "deserialize() returned null after restart — Bug 2 not fixed")
        assertEquals("send_message", restoredNode!!.nodeId)

        // Params are preserved
        val restoredParams = restoredNode.getParams()
        assertEquals("Welcome!", restoredParams["message"])
        assertEquals("GREEN", restoredParams["color"])
    }

    @Test
    fun `full cycle - multiple nodes saved and restored independently`() {
        // Registry with two node types
        val registry = mockk<NodeRegistry>()
        every { registry.getActionFactory(Material.PAPER) } returns { params -> SendMessageAction(params) }
        every { registry.getConditionFactory(Material.PAPER) } returns null
        every { registry.getValueFactory(Material.PAPER) } returns null
        every { registry.getMaterialForNode(match { it.nodeId == "send_message" }) } returns Material.PAPER

        val serializer = ASTSerializer(registry)

        // Save two nodes
        val node1 = SendMessageAction(mapOf("message" to "Hello"))
        val node2 = SendMessageAction(mapOf("message" to "Goodbye"))

        val doc1 = serializer.serialize(node1)!!
        val doc2 = serializer.serialize(node2)!!

        // Simulate restart — fresh serializer with same registry
        val freshSerializer = ASTSerializer(registry)

        val restored1 = freshSerializer.deserialize(doc1)
        val restored2 = freshSerializer.deserialize(doc2)

        assertNotNull(restored1)
        assertNotNull(restored2)
        assertEquals("Hello", restored1!!.getParams()["message"])
        assertEquals("Goodbye", restored2!!.getParams()["message"])
    }

    @Test
    fun `full cycle - document with missing nodeId returns null after restart`() {
        val registry = buildRegistry()
        val serializer = ASTSerializer(registry)

        // A corrupted document (no nodeId)
        val corruptedDoc = Document("params", Document("message", "test"))

        val result = serializer.deserialize(corruptedDoc)

        assertNull(result, "deserialize() should return null for document without nodeId")
    }

    @Test
    fun `full cycle - document with unregistered nodeId returns null after restart`() {
        val registry = mockk<NodeRegistry>()
        every { registry.getActionFactory(any()) } returns null
        every { registry.getConditionFactory(any()) } returns null
        every { registry.getValueFactory(any()) } returns null

        val serializer = ASTSerializer(registry)

        // A document referencing a node type not in the post-restart registry
        val doc = Document("nodeId", "DIRT").append("params", Document())

        val result = serializer.deserialize(doc)

        assertNull(result, "deserialize() should return null when factory not found in registry")
    }

    @Test
    fun `full cycle - round-trip idempotency after restart`() {
        // serialize(deserialize(serialize(ast))) == serialize(ast)
        val registry = buildRegistry()
        val serializer = ASTSerializer(registry)

        val original = SendMessageAction(mapOf("message" to "Test", "prefix" to "[OCP]"))

        val doc1 = serializer.serialize(original)!!

        // Simulate restart
        val freshSerializer = ASTSerializer(buildRegistry())
        val restored = freshSerializer.deserialize(doc1)!!
        val doc2 = freshSerializer.serialize(restored)!!

        assertEquals(doc1.getString("nodeId"), doc2.getString("nodeId"))
        assertEquals(
            doc1.get("params", Document::class.java),
            doc2.get("params", Document::class.java)
        )
    }
}
