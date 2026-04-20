package com.opencreativeplus.core.serialization

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.node.INode
import com.opencreativeplus.api.node.IValue
import com.opencreativeplus.api.registry.NodeRegistry
import io.mockk.every
import io.mockk.mockk
import org.bson.Document
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ASTSerializer].
 *
 * 2.4 Fix check: deserialize returns non-null AST for a valid document.
 * 2.5 Preservation: serialize(deserialize(serialize(ast))) == serialize(ast).
 *
 * Validates: Requirements 2.2, 3.2 (Properties 3 and 4)
 */
class ASTSerializerTest {

    // -----------------------------------------------------------------------
    // Test node implementations
    // -----------------------------------------------------------------------

    /** Simple action node that stores its params and exposes them via getParams(). */
    private class TestAction(private val params: Map<String, Any>) : IAction {
        override val nodeId = "test_action"
        override val displayName = "Test Action"
        override fun getParams(): Map<String, Any> = params
        override suspend fun execute(context: ExecutionContext) {}
    }

    /** Simple condition node. */
    private class TestCondition(private val params: Map<String, Any>) : ICondition {
        override val nodeId = "test_condition"
        override val displayName = "Test Condition"
        override fun getParams(): Map<String, Any> = params
        override suspend fun evaluate(context: ExecutionContext): Boolean = true
    }

    /** Simple value node. */
    private class TestValue(private val params: Map<String, Any>) : IValue<Int> {
        override val nodeId = "test_value"
        override val displayName = "Test Value"
        override fun getParams(): Map<String, Any> = params
        override suspend fun compute(context: ExecutionContext): Int =
            params["value"] as? Int ?: 0
    }

    // -----------------------------------------------------------------------
    // Registry setup
    // -----------------------------------------------------------------------

    private lateinit var registry: NodeRegistry
    private lateinit var serializer: ASTSerializer

    @BeforeEach
    fun setup() {
        registry = mockk()

        // Action: PAPER → test_action
        every { registry.getActionFactory(Material.PAPER) } returns { params -> TestAction(params) }
        every { registry.getConditionFactory(Material.PAPER) } returns null
        every { registry.getValueFactory(Material.PAPER) } returns null
        every { registry.getMaterialForNode(match { it.nodeId == "test_action" }) } returns Material.PAPER

        // Condition: COMPARATOR → test_condition
        every { registry.getActionFactory(Material.COMPARATOR) } returns null
        every { registry.getConditionFactory(Material.COMPARATOR) } returns { params -> TestCondition(params) }
        every { registry.getValueFactory(Material.COMPARATOR) } returns null
        every { registry.getMaterialForNode(match { it.nodeId == "test_condition" }) } returns Material.COMPARATOR

        // Value: GOLD_BLOCK → test_value
        every { registry.getActionFactory(Material.GOLD_BLOCK) } returns null
        every { registry.getConditionFactory(Material.GOLD_BLOCK) } returns null
        every { registry.getValueFactory(Material.GOLD_BLOCK) } returns { params -> TestValue(params) }
        every { registry.getMaterialForNode(match { it.nodeId == "test_value" }) } returns Material.GOLD_BLOCK

        // Unknown material
        every { registry.getActionFactory(Material.DIRT) } returns null
        every { registry.getConditionFactory(Material.DIRT) } returns null
        every { registry.getValueFactory(Material.DIRT) } returns null

        serializer = ASTSerializer(registry)
    }

    // -----------------------------------------------------------------------
    // 2.4 Fix check: deserialize returns non-null for valid document
    // -----------------------------------------------------------------------

    @Test
    fun `deserialize returns non-null action node for valid document`() {
        // Given: a document with a registered action nodeId
        val doc = Document("nodeId", "PAPER")
            .append("params", Document("message", "Hello"))

        // When
        val result = serializer.deserialize(doc)

        // Then: non-null node is returned
        assertNotNull(result)
        assertEquals("test_action", result!!.nodeId)
    }

    @Test
    fun `deserialize returns non-null condition node for valid document`() {
        val doc = Document("nodeId", "COMPARATOR")
            .append("params", Document("left", 1).append("right", 2))

        val result = serializer.deserialize(doc)

        assertNotNull(result)
        assertEquals("test_condition", result!!.nodeId)
    }

    @Test
    fun `deserialize returns non-null value node for valid document`() {
        val doc = Document("nodeId", "GOLD_BLOCK")
            .append("params", Document("value", 42))

        val result = serializer.deserialize(doc)

        assertNotNull(result)
        assertEquals("test_value", result!!.nodeId)
    }

    @Test
    fun `deserialize returns null when nodeId field is missing`() {
        val doc = Document("params", Document("x", 1))

        val result = serializer.deserialize(doc)

        assertNull(result)
    }

    @Test
    fun `deserialize returns null for unknown Material name`() {
        val doc = Document("nodeId", "NOT_A_REAL_MATERIAL")
            .append("params", Document())

        val result = serializer.deserialize(doc)

        assertNull(result)
    }

    @Test
    fun `deserialize returns null when no factory registered for material`() {
        val doc = Document("nodeId", "DIRT")
            .append("params", Document())

        val result = serializer.deserialize(doc)

        assertNull(result)
    }

    @Test
    fun `deserialize handles missing params field gracefully`() {
        // Document with nodeId but no params sub-document
        val doc = Document("nodeId", "PAPER")

        val result = serializer.deserialize(doc)

        assertNotNull(result)
        assertEquals("test_action", result!!.nodeId)
    }

    // -----------------------------------------------------------------------
    // 2.5 Preservation: serialize(deserialize(serialize(ast))) == serialize(ast)
    // -----------------------------------------------------------------------

    @Test
    fun `serialize produces document with correct nodeId and params for action`() {
        val node = TestAction(mapOf("message" to "Hello"))

        val doc = serializer.serialize(node)

        assertNotNull(doc)
        assertEquals("PAPER", doc!!.getString("nodeId"))
        val params = doc.get("params", Document::class.java)
        assertNotNull(params)
        assertEquals("Hello", params!!.getString("message"))
    }

    @Test
    fun `serialize returns null for unregistered node`() {
        every { registry.getMaterialForNode(any()) } returns null

        val node = TestAction(emptyMap())
        val result = serializer.serialize(node)

        assertNull(result)
    }

    @Test
    fun `serialize then deserialize then serialize produces equal document (round-trip)`() {
        // Given: an action node with params
        val original = TestAction(mapOf("message" to "Hello", "color" to "RED"))

        // When: serialize → deserialize → serialize
        val doc1 = serializer.serialize(original)
        assertNotNull(doc1)

        val deserialized = serializer.deserialize(doc1!!)
        assertNotNull(deserialized)

        val doc2 = serializer.serialize(deserialized!!)
        assertNotNull(doc2)

        // Then: serialize(deserialize(serialize(ast))) == serialize(ast)
        assertEquals(doc1.getString("nodeId"), doc2!!.getString("nodeId"))
        val params1 = doc1.get("params", Document::class.java)
        val params2 = doc2.get("params", Document::class.java)
        assertEquals(params1, params2)
    }

    @Test
    fun `round-trip preserves condition node params`() {
        val original = TestCondition(mapOf("left" to 10, "right" to 20))

        val doc1 = serializer.serialize(original)!!
        val deserialized = serializer.deserialize(doc1)!!
        val doc2 = serializer.serialize(deserialized)!!

        assertEquals(doc1.getString("nodeId"), doc2.getString("nodeId"))
        assertEquals(
            doc1.get("params", Document::class.java),
            doc2.get("params", Document::class.java)
        )
    }

    @Test
    fun `round-trip preserves value node params`() {
        val original = TestValue(mapOf("value" to 99))

        val doc1 = serializer.serialize(original)!!
        val deserialized = serializer.deserialize(doc1)!!
        val doc2 = serializer.serialize(deserialized)!!

        assertEquals(doc1.getString("nodeId"), doc2.getString("nodeId"))
        assertEquals(
            doc1.get("params", Document::class.java),
            doc2.get("params", Document::class.java)
        )
    }

    @Test
    fun `round-trip with empty params produces equal documents`() {
        val original = TestAction(emptyMap())

        val doc1 = serializer.serialize(original)!!
        val deserialized = serializer.deserialize(doc1)!!
        val doc2 = serializer.serialize(deserialized)!!

        assertEquals(doc1.getString("nodeId"), doc2.getString("nodeId"))
    }
}
