package com.opencreativeplus.plugin.node

import com.opencreativeplus.plugin.node.action.IfAction
import com.opencreativeplus.plugin.node.condition.EqualsCondition
import com.opencreativeplus.plugin.node.condition.GreaterThanCondition
import com.opencreativeplus.plugin.node.condition.LessThanCondition
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// EqualsCondition tests ( 30.1, 30.2)
// ---------------------------------------------------------------------------

class EqualsConditionTest {

    @Test
    fun `nodeId is equals and displayName is Equals`() {
        val cond = EqualsCondition(1, 1)
        assertEquals("equals", cond.nodeId)
        assertEquals("Equals", cond.displayName)
    }

    @Test
    fun `equal integer literals evaluate to true`() = runTest {
        val ctx = FakeExecutionContext()
        assertTrue(EqualsCondition(42, 42).evaluate(ctx))
    }

    @Test
    fun `different integer literals evaluate to false`() = runTest {
        val ctx = FakeExecutionContext()
        assertFalse(EqualsCondition(1, 2).evaluate(ctx))
    }

    @Test
    fun `equal string literals evaluate to true`() = runTest {
        val ctx = FakeExecutionContext()
        assertTrue(EqualsCondition("hello", "hello").evaluate(ctx))
    }

    @Test
    fun `different string literals evaluate to false`() = runTest {
        val ctx = FakeExecutionContext()
        assertFalse(EqualsCondition("hello", "world").evaluate(ctx))
    }

    @Test
    fun `null equals null evaluates to true`() = runTest {
        val ctx = FakeExecutionContext()
        assertTrue(EqualsCondition(null, null).evaluate(ctx))
    }

    @Test
    fun `null does not equal non-null`() = runTest {
        val ctx = FakeExecutionContext()
        assertFalse(EqualsCondition(null, 5).evaluate(ctx))
    }

    //  30.2 - evaluate using current ExecutionContext (variable resolution)

    @Test
    fun `resolves variable reference from local scope`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.localScope.set("score", 100)
        assertTrue(EqualsCondition("\$score", 100).evaluate(ctx))
    }

    @Test
    fun `resolves variable reference from plot scope`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.plotScope.set("level", "gold")
        assertTrue(EqualsCondition("\$level", "gold").evaluate(ctx))
    }

    @Test
    fun `resolves variable reference from saved scope`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.savedScope.set("rank", 5)
        assertTrue(EqualsCondition("\$rank", 5).evaluate(ctx))
    }

    @Test
    fun `local scope takes precedence over plot scope`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.localScope.set("x", 10)
        ctx.plotScope.set("x", 99)
        assertTrue(EqualsCondition("\$x", 10).evaluate(ctx))
    }

    @Test
    fun `unresolved variable reference evaluates to false when compared to non-null`() = runTest {
        val ctx = FakeExecutionContext()
        // $missing is not set → resolves to null, which != 5
        assertFalse(EqualsCondition("\$missing", 5).evaluate(ctx))
    }
}

// ---------------------------------------------------------------------------
// GreaterThanCondition tests ( 30.1, 30.2)
// ---------------------------------------------------------------------------

class GreaterThanConditionTest {

    @Test
    fun `nodeId is greater_than and displayName is Greater Than`() {
        val cond = GreaterThanCondition(1, 0)
        assertEquals("greater_than", cond.nodeId)
        assertEquals("Greater Than", cond.displayName)
    }

    @Test
    fun `larger left operand evaluates to true`() = runTest {
        val ctx = FakeExecutionContext()
        assertTrue(GreaterThanCondition(10, 5).evaluate(ctx))
    }

    @Test
    fun `smaller left operand evaluates to false`() = runTest {
        val ctx = FakeExecutionContext()
        assertFalse(GreaterThanCondition(3, 7).evaluate(ctx))
    }

    @Test
    fun `equal operands evaluate to false`() = runTest {
        val ctx = FakeExecutionContext()
        assertFalse(GreaterThanCondition(5, 5).evaluate(ctx))
    }

    @Test
    fun `resolves variable reference from local scope`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.localScope.set("hp", 80)
        assertTrue(GreaterThanCondition("\$hp", 50).evaluate(ctx))
    }

    @Test
    fun `resolves variable reference from plot scope`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.plotScope.set("kills", 10)
        assertTrue(GreaterThanCondition("\$kills", 5).evaluate(ctx))
    }

    @Test
    fun `non-numeric operand returns false`() = runTest {
        val ctx = FakeExecutionContext()
        assertFalse(GreaterThanCondition("abc", 5).evaluate(ctx))
    }

    @Test
    fun `double values are compared correctly`() = runTest {
        val ctx = FakeExecutionContext()
        assertTrue(GreaterThanCondition(3.5, 3.4).evaluate(ctx))
    }
}

// ---------------------------------------------------------------------------
// LessThanCondition tests ( 30.1, 30.2)
// ---------------------------------------------------------------------------

class LessThanConditionTest {

    @Test
    fun `nodeId is less_than and displayName is Less Than`() {
        val cond = LessThanCondition(0, 1)
        assertEquals("less_than", cond.nodeId)
        assertEquals("Less Than", cond.displayName)
    }

    @Test
    fun `smaller left operand evaluates to true`() = runTest {
        val ctx = FakeExecutionContext()
        assertTrue(LessThanCondition(3, 7).evaluate(ctx))
    }

    @Test
    fun `larger left operand evaluates to false`() = runTest {
        val ctx = FakeExecutionContext()
        assertFalse(LessThanCondition(10, 5).evaluate(ctx))
    }

    @Test
    fun `equal operands evaluate to false`() = runTest {
        val ctx = FakeExecutionContext()
        assertFalse(LessThanCondition(5, 5).evaluate(ctx))
    }

    @Test
    fun `resolves variable reference from local scope`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.localScope.set("lives", 1)
        assertTrue(LessThanCondition("\$lives", 3).evaluate(ctx))
    }

    @Test
    fun `non-numeric operand returns false`() = runTest {
        val ctx = FakeExecutionContext()
        assertFalse(LessThanCondition("text", 5).evaluate(ctx))
    }
}

// ---------------------------------------------------------------------------
// IfAction branching tests ( 30.3)
// ---------------------------------------------------------------------------

class IfActionTest {

    @Test
    fun `nodeId is if and displayName is If`() {
        val cond = EqualsCondition(1, 1)
        val action = IfAction(cond, emptyList())
        assertEquals("if", action.nodeId)
        assertEquals("If", action.displayName)
    }

    @Test
    fun `executes then-branch when condition is true`() = runTest {
        val ctx = FakeExecutionContext()
        val thenAction = mockk<com.opencreativeplus.api.node.IAction>(relaxed = true)
        val elseAction = mockk<com.opencreativeplus.api.node.IAction>(relaxed = true)

        val ifAction = IfAction(
            condition = EqualsCondition(1, 1), // true
            thenActions = listOf(thenAction),
            elseActions = listOf(elseAction)
        )

        ifAction.execute(ctx)

        coVerify(exactly = 1) { thenAction.execute(ctx) }
        coVerify(exactly = 0) { elseAction.execute(ctx) }
    }

    @Test
    fun `executes else-branch when condition is false`() = runTest {
        val ctx = FakeExecutionContext()
        val thenAction = mockk<com.opencreativeplus.api.node.IAction>(relaxed = true)
        val elseAction = mockk<com.opencreativeplus.api.node.IAction>(relaxed = true)

        val ifAction = IfAction(
            condition = EqualsCondition(1, 2), // false
            thenActions = listOf(thenAction),
            elseActions = listOf(elseAction)
        )

        ifAction.execute(ctx)

        coVerify(exactly = 0) { thenAction.execute(ctx) }
        coVerify(exactly = 1) { elseAction.execute(ctx) }
    }

    @Test
    fun `executes all then-actions in order when condition is true`() = runTest {
        val ctx = FakeExecutionContext()
        val executionOrder = mutableListOf<Int>()

        val action1 = mockk<com.opencreativeplus.api.node.IAction>(relaxed = true)
        val action2 = mockk<com.opencreativeplus.api.node.IAction>(relaxed = true)
        val action3 = mockk<com.opencreativeplus.api.node.IAction>(relaxed = true)

        io.mockk.coEvery { action1.execute(ctx) } answers { executionOrder.add(1) }
        io.mockk.coEvery { action2.execute(ctx) } answers { executionOrder.add(2) }
        io.mockk.coEvery { action3.execute(ctx) } answers { executionOrder.add(3) }

        val ifAction = IfAction(
            condition = EqualsCondition("a", "a"), // true
            thenActions = listOf(action1, action2, action3)
        )

        ifAction.execute(ctx)

        assertEquals(listOf(1, 2, 3), executionOrder)
    }

    @Test
    fun `does nothing when condition is false and no else-branch`() = runTest {
        val ctx = FakeExecutionContext()
        val thenAction = mockk<com.opencreativeplus.api.node.IAction>(relaxed = true)

        val ifAction = IfAction(
            condition = EqualsCondition(1, 2), // false
            thenActions = listOf(thenAction)
            // no elseActions
        )

        ifAction.execute(ctx)

        coVerify(exactly = 0) { thenAction.execute(ctx) }
    }

    @Test
    fun `branches based on variable from execution context`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.localScope.set("mode", "admin")

        val thenAction = mockk<com.opencreativeplus.api.node.IAction>(relaxed = true)
        val elseAction = mockk<com.opencreativeplus.api.node.IAction>(relaxed = true)

        val ifAction = IfAction(
            condition = EqualsCondition("\$mode", "admin"), // true via context
            thenActions = listOf(thenAction),
            elseActions = listOf(elseAction)
        )

        ifAction.execute(ctx)

        coVerify(exactly = 1) { thenAction.execute(ctx) }
        coVerify(exactly = 0) { elseAction.execute(ctx) }
    }

    @Test
    fun `branches correctly with GreaterThan condition`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.localScope.set("score", 150)

        val thenAction = mockk<com.opencreativeplus.api.node.IAction>(relaxed = true)
        val elseAction = mockk<com.opencreativeplus.api.node.IAction>(relaxed = true)

        val ifAction = IfAction(
            condition = GreaterThanCondition("\$score", 100), // true
            thenActions = listOf(thenAction),
            elseActions = listOf(elseAction)
        )

        ifAction.execute(ctx)

        coVerify(exactly = 1) { thenAction.execute(ctx) }
        coVerify(exactly = 0) { elseAction.execute(ctx) }
    }

    @Test
    fun `branches correctly with LessThan condition`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.localScope.set("hp", 10)

        val thenAction = mockk<com.opencreativeplus.api.node.IAction>(relaxed = true)
        val elseAction = mockk<com.opencreativeplus.api.node.IAction>(relaxed = true)

        val ifAction = IfAction(
            condition = LessThanCondition("\$hp", 20), // true
            thenActions = listOf(thenAction),
            elseActions = listOf(elseAction)
        )

        ifAction.execute(ctx)

        coVerify(exactly = 1) { thenAction.execute(ctx) }
        coVerify(exactly = 0) { elseAction.execute(ctx) }
    }

    @Test
    fun `nested IfAction executes inner branch correctly`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.localScope.set("x", 10)
        ctx.localScope.set("y", 5)

        val innerAction = mockk<com.opencreativeplus.api.node.IAction>(relaxed = true)

        // if (x > 5) { if (y < 10) { innerAction } }
        val innerIf = IfAction(
            condition = LessThanCondition("\$y", 10), // true
            thenActions = listOf(innerAction)
        )
        val outerIf = IfAction(
            condition = GreaterThanCondition("\$x", 5), // true
            thenActions = listOf(innerIf)
        )

        outerIf.execute(ctx)

        coVerify(exactly = 1) { innerAction.execute(ctx) }
    }
}
