package com.opencreativeplus.plugin.node

import com.opencreativeplus.plugin.node.value.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Arithmetic value tests
// ---------------------------------------------------------------------------

class AddValueTest {

    @Test
    fun `nodeId is add and displayName is Add`() {
        val v = AddValue(1, 2)
        assertEquals("add", v.nodeId)
        assertEquals("Add", v.displayName)
    }

    @Test
    fun `adds two numeric literals`() = runTest {
        val ctx = FakeExecutionContext()
        assertEquals(7.0, AddValue(3, 4).compute(ctx))
    }

    @Test
    fun `adds double values`() = runTest {
        val ctx = FakeExecutionContext()
        assertEquals(5.5, AddValue(2.5, 3.0).compute(ctx))
    }

    @Test
    fun `resolves variable reference from local scope`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.localScope.set("x", 10)
        assertEquals(15.0, AddValue("\$x", 5).compute(ctx))
    }

    @Test
    fun `resolves variable reference from plot scope`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.plotScope.set("y", 20)
        assertEquals(25.0, AddValue("\$y", 5).compute(ctx))
    }

    @Test
    fun `resolves variable reference from saved scope`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.savedScope.set("z", 30)
        assertEquals(35.0, AddValue("\$z", 5).compute(ctx))
    }

    @Test
    fun `null operand treated as zero`() = runTest {
        val ctx = FakeExecutionContext()
        assertEquals(5.0, AddValue(null, 5).compute(ctx))
    }

    @Test
    fun `string numeric operand is parsed`() = runTest {
        val ctx = FakeExecutionContext()
        assertEquals(9.0, AddValue("4", "5").compute(ctx))
    }

    @Test
    fun `non-numeric string operand treated as zero`() = runTest {
        val ctx = FakeExecutionContext()
        assertEquals(3.0, AddValue("abc", 3).compute(ctx))
    }
}

class SubtractValueTest {

    @Test
    fun `nodeId is subtract`() {
        assertEquals("subtract", SubtractValue(0, 0).nodeId)
    }

    @Test
    fun `subtracts two numeric literals`() = runTest {
        val ctx = FakeExecutionContext()
        assertEquals(3.0, SubtractValue(10, 7).compute(ctx))
    }

    @Test
    fun `result can be negative`() = runTest {
        val ctx = FakeExecutionContext()
        assertEquals(-2.0, SubtractValue(3, 5).compute(ctx))
    }

    @Test
    fun `resolves variable reference`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.localScope.set("a", 8)
        assertEquals(3.0, SubtractValue("\$a", 5).compute(ctx))
    }
}

class MultiplyValueTest {

    @Test
    fun `nodeId is multiply`() {
        assertEquals("multiply", MultiplyValue(0, 0).nodeId)
    }

    @Test
    fun `multiplies two numeric literals`() = runTest {
        val ctx = FakeExecutionContext()
        assertEquals(12.0, MultiplyValue(3, 4).compute(ctx))
    }

    @Test
    fun `multiply by zero returns zero`() = runTest {
        val ctx = FakeExecutionContext()
        assertEquals(0.0, MultiplyValue(99, 0).compute(ctx))
    }

    @Test
    fun `resolves variable reference`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.localScope.set("factor", 6)
        assertEquals(18.0, MultiplyValue("\$factor", 3).compute(ctx))
    }
}

class DivideValueTest {

    @Test
    fun `nodeId is divide`() {
        assertEquals("divide", DivideValue(0, 1).nodeId)
    }

    @Test
    fun `divides two numeric literals`() = runTest {
        val ctx = FakeExecutionContext()
        assertEquals(4.0, DivideValue(12, 3).compute(ctx))
    }

    @Test
    fun `division by zero returns zero instead of throwing`() = runTest {
        val ctx = FakeExecutionContext()
        assertEquals(0.0, DivideValue(10, 0).compute(ctx))
    }

    @Test
    fun `division by zero via variable returns zero`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.localScope.set("divisor", 0)
        assertEquals(0.0, DivideValue(10, "\$divisor").compute(ctx))
    }

    @Test
    fun `resolves variable reference`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.localScope.set("n", 20)
        assertEquals(4.0, DivideValue("\$n", 5).compute(ctx))
    }

    @Test
    fun `fractional result is preserved`() = runTest {
        val ctx = FakeExecutionContext()
        assertEquals(2.5, DivideValue(5, 2).compute(ctx))
    }
}

// ---------------------------------------------------------------------------
// Comparison value tests
// ---------------------------------------------------------------------------

class EqualsValueTest {

    @Test
    fun `nodeId is equals_value`() {
        assertEquals("equals_value", EqualsValue(0, 0).nodeId)
    }

    @Test
    fun `equal integers return true`() = runTest {
        val ctx = FakeExecutionContext()
        assertTrue(EqualsValue(5, 5).compute(ctx))
    }

    @Test
    fun `different integers return false`() = runTest {
        val ctx = FakeExecutionContext()
        assertFalse(EqualsValue(5, 6).compute(ctx))
    }

    @Test
    fun `equal strings return true`() = runTest {
        val ctx = FakeExecutionContext()
        assertTrue(EqualsValue("hello", "hello").compute(ctx))
    }

    @Test
    fun `different strings return false`() = runTest {
        val ctx = FakeExecutionContext()
        assertFalse(EqualsValue("hello", "world").compute(ctx))
    }

    @Test
    fun `resolves variable reference for equality check`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.localScope.set("val", 42)
        assertTrue(EqualsValue("\$val", 42).compute(ctx))
    }

    @Test
    fun `null equals null returns true`() = runTest {
        val ctx = FakeExecutionContext()
        assertTrue(EqualsValue(null, null).compute(ctx))
    }

    @Test
    fun `null does not equal non-null`() = runTest {
        val ctx = FakeExecutionContext()
        assertFalse(EqualsValue(null, 5).compute(ctx))
    }
}

class GreaterThanValueTest {

    @Test
    fun `nodeId is greater_than_value`() {
        assertEquals("greater_than_value", GreaterThanValue(0, 0).nodeId)
    }

    @Test
    fun `larger left operand returns true`() = runTest {
        val ctx = FakeExecutionContext()
        assertTrue(GreaterThanValue(10, 5).compute(ctx))
    }

    @Test
    fun `smaller left operand returns false`() = runTest {
        val ctx = FakeExecutionContext()
        assertFalse(GreaterThanValue(3, 7).compute(ctx))
    }

    @Test
    fun `equal operands return false`() = runTest {
        val ctx = FakeExecutionContext()
        assertFalse(GreaterThanValue(5, 5).compute(ctx))
    }

    @Test
    fun `resolves variable reference`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.localScope.set("score", 100)
        assertTrue(GreaterThanValue("\$score", 50).compute(ctx))
    }
}

class LessThanValueTest {

    @Test
    fun `nodeId is less_than_value`() {
        assertEquals("less_than_value", LessThanValue(0, 0).nodeId)
    }

    @Test
    fun `smaller left operand returns true`() = runTest {
        val ctx = FakeExecutionContext()
        assertTrue(LessThanValue(3, 7).compute(ctx))
    }

    @Test
    fun `larger left operand returns false`() = runTest {
        val ctx = FakeExecutionContext()
        assertFalse(LessThanValue(10, 5).compute(ctx))
    }

    @Test
    fun `equal operands return false`() = runTest {
        val ctx = FakeExecutionContext()
        assertFalse(LessThanValue(5, 5).compute(ctx))
    }

    @Test
    fun `resolves variable reference`() = runTest {
        val ctx = FakeExecutionContext()
        ctx.localScope.set("hp", 10)
        assertTrue(LessThanValue("\$hp", 50).compute(ctx))
    }
}

// ---------------------------------------------------------------------------
// Nested value evaluation tests (Requirements 31.4, 31.5)
// ---------------------------------------------------------------------------

class NestedValueTest {

    @Test
    fun `add nested inside multiply`() = runTest {
        // (2 + 3) * 4 = 20
        val ctx = FakeExecutionContext()
        val inner = AddValue(2, 3)
        val outer = MultiplyValue(inner, 4)
        assertEquals(20.0, outer.compute(ctx))
    }

    @Test
    fun `subtract nested inside divide`() = runTest {
        // (10 - 4) / 2 = 3
        val ctx = FakeExecutionContext()
        val inner = SubtractValue(10, 4)
        val outer = DivideValue(inner, 2)
        assertEquals(3.0, outer.compute(ctx))
    }

    @Test
    fun `multiply nested inside add`() = runTest {
        // (3 * 4) + 5 = 17
        val ctx = FakeExecutionContext()
        val inner = MultiplyValue(3, 4)
        val outer = AddValue(inner, 5)
        assertEquals(17.0, outer.compute(ctx))
    }

    @Test
    fun `arithmetic nested inside comparison`() = runTest {
        // (2 + 3) > 4 → true
        val ctx = FakeExecutionContext()
        val sum = AddValue(2, 3)
        val cmp = GreaterThanValue(sum, 4)
        assertTrue(cmp.compute(ctx))
    }

    @Test
    fun `arithmetic nested inside equals`() = runTest {
        // (6 / 2) == 3 → true
        val ctx = FakeExecutionContext()
        val div = DivideValue(6, 2)
        val eq = EqualsValue(div, 3.0)
        assertTrue(eq.compute(ctx))
    }

    @Test
    fun `three levels of nesting`() = runTest {
        // ((1 + 2) * 3) - 4 = 5
        val ctx = FakeExecutionContext()
        val add = AddValue(1, 2)
        val mul = MultiplyValue(add, 3)
        val sub = SubtractValue(mul, 4)
        assertEquals(5.0, sub.compute(ctx))
    }

    @Test
    fun `nested value with variable reference`() = runTest {
        // x = 10; (x + 5) * 2 = 30
        val ctx = FakeExecutionContext()
        ctx.localScope.set("x", 10)
        val add = AddValue("\$x", 5)
        val mul = MultiplyValue(add, 2)
        assertEquals(30.0, mul.compute(ctx))
    }

    @Test
    fun `nested division by zero is safe`() = runTest {
        // (5 - 5) used as divisor → 0.0 result
        val ctx = FakeExecutionContext()
        val zero = SubtractValue(5, 5)
        val div = DivideValue(10, zero)
        assertEquals(0.0, div.compute(ctx))
    }
}
