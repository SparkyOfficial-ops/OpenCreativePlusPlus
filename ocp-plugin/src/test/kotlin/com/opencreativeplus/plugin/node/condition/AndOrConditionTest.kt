package com.opencreativeplus.plugin.node.condition

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.node.ICondition
import kotlinx.coroutines.test.runTest
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Fake helpers (local to this file)
// ---------------------------------------------------------------------------

private class FakeScope : VariableScope {
    private val map = mutableMapOf<String, Any>()
    override fun get(name: String) = map[name]
    override fun set(name: String, value: Any) { map[name] = value }
    override fun has(name: String) = map.containsKey(name)
    override fun clear() = map.clear()
}

private class FakeCtx : ExecutionContext {
    override val plotId: UUID = UUID.randomUUID()
    override val player: Player? = null
    override val eventData: Map<String, Any> = emptyMap()
    override val localScope: VariableScope = FakeScope()
    override val plotScope: VariableScope = FakeScope()
    override val savedScope: VariableScope = FakeScope()
    override val operationCount: AtomicInteger = AtomicInteger(0)
    override val callStackSize: AtomicInteger = AtomicInteger(0)
    override suspend fun <T> syncContext(block: () -> T): T = block()
}

/**
 * A condition that returns a fixed [result] and counts how many times it was evaluated.
 * Used to verify short-circuit behaviour.
 */
private class TrackingCondition(private val result: Boolean) : ICondition {
    override val nodeId = "tracking"
    override val displayName = "Tracking"
    var evaluationCount: Int = 0
        private set

    override suspend fun evaluate(context: ExecutionContext): Boolean {
        evaluationCount++
        return result
    }
}

// ---------------------------------------------------------------------------
// AndConditionNode unit tests  (Requirements 6.2, 6.3, 6.4)
// ---------------------------------------------------------------------------

class AndConditionTest {

    // --- empty list ---

    @Test
    fun `empty list returns true (vacuous truth)`() = runTest {
        val node = AndConditionNode(emptyList())
        assertTrue(node.evaluate(FakeCtx()))
    }

    // --- all-true / all-false ---

    @Test
    fun `all true conditions returns true`() = runTest {
        val node = AndConditionNode(listOf(
            TrackingCondition(true),
            TrackingCondition(true),
            TrackingCondition(true)
        ))
        assertTrue(node.evaluate(FakeCtx()))
    }

    @Test
    fun `all false conditions returns false`() = runTest {
        val node = AndConditionNode(listOf(
            TrackingCondition(false),
            TrackingCondition(false)
        ))
        assertFalse(node.evaluate(FakeCtx()))
    }

    @Test
    fun `mixed true then false returns false`() = runTest {
        val node = AndConditionNode(listOf(
            TrackingCondition(true),
            TrackingCondition(false),
            TrackingCondition(true)
        ))
        assertFalse(node.evaluate(FakeCtx()))
    }

    // --- short-circuit: first false stops evaluation ---

    @Test
    fun `first false short-circuits remaining conditions`() = runTest {
        val first  = TrackingCondition(false)
        val second = TrackingCondition(true)
        val third  = TrackingCondition(true)

        AndConditionNode(listOf(first, second, third)).evaluate(FakeCtx())

        assertEquals(1, first.evaluationCount,  "first condition must be evaluated")
        assertEquals(0, second.evaluationCount, "second condition must NOT be evaluated after first false")
        assertEquals(0, third.evaluationCount,  "third condition must NOT be evaluated after first false")
    }

    @Test
    fun `false in the middle short-circuits remaining conditions`() = runTest {
        val first  = TrackingCondition(true)
        val second = TrackingCondition(false)
        val third  = TrackingCondition(true)

        AndConditionNode(listOf(first, second, third)).evaluate(FakeCtx())

        assertEquals(1, first.evaluationCount,  "first condition must be evaluated")
        assertEquals(1, second.evaluationCount, "second condition must be evaluated")
        assertEquals(0, third.evaluationCount,  "third condition must NOT be evaluated after second false")
    }

    @Test
    fun `all true conditions evaluates every condition exactly once`() = runTest {
        val conditions = List(4) { TrackingCondition(true) }
        AndConditionNode(conditions).evaluate(FakeCtx())
        conditions.forEach { assertEquals(1, it.evaluationCount) }
    }
}

// ---------------------------------------------------------------------------
// OrConditionNode unit tests  (Requirements 7.2, 7.3, 7.4)
// ---------------------------------------------------------------------------

class OrConditionTest {

    // --- empty list ---

    @Test
    fun `empty list returns false`() = runTest {
        val node = OrConditionNode(emptyList())
        assertFalse(node.evaluate(FakeCtx()))
    }

    // --- all-true / all-false ---

    @Test
    fun `all false conditions returns false`() = runTest {
        val node = OrConditionNode(listOf(
            TrackingCondition(false),
            TrackingCondition(false),
            TrackingCondition(false)
        ))
        assertFalse(node.evaluate(FakeCtx()))
    }

    @Test
    fun `all true conditions returns true`() = runTest {
        val node = OrConditionNode(listOf(
            TrackingCondition(true),
            TrackingCondition(true)
        ))
        assertTrue(node.evaluate(FakeCtx()))
    }

    @Test
    fun `mixed false then true returns true`() = runTest {
        val node = OrConditionNode(listOf(
            TrackingCondition(false),
            TrackingCondition(true),
            TrackingCondition(false)
        ))
        assertTrue(node.evaluate(FakeCtx()))
    }

    // --- short-circuit: first true stops evaluation ---

    @Test
    fun `first true short-circuits remaining conditions`() = runTest {
        val first  = TrackingCondition(true)
        val second = TrackingCondition(false)
        val third  = TrackingCondition(false)

        OrConditionNode(listOf(first, second, third)).evaluate(FakeCtx())

        assertEquals(1, first.evaluationCount,  "first condition must be evaluated")
        assertEquals(0, second.evaluationCount, "second condition must NOT be evaluated after first true")
        assertEquals(0, third.evaluationCount,  "third condition must NOT be evaluated after first true")
    }

    @Test
    fun `true in the middle short-circuits remaining conditions`() = runTest {
        val first  = TrackingCondition(false)
        val second = TrackingCondition(true)
        val third  = TrackingCondition(false)

        OrConditionNode(listOf(first, second, third)).evaluate(FakeCtx())

        assertEquals(1, first.evaluationCount,  "first condition must be evaluated")
        assertEquals(1, second.evaluationCount, "second condition must be evaluated")
        assertEquals(0, third.evaluationCount,  "third condition must NOT be evaluated after second true")
    }

    @Test
    fun `all false conditions evaluates every condition exactly once`() = runTest {
        val conditions = List(4) { TrackingCondition(false) }
        OrConditionNode(conditions).evaluate(FakeCtx())
        conditions.forEach { assertEquals(1, it.evaluationCount) }
    }
}
