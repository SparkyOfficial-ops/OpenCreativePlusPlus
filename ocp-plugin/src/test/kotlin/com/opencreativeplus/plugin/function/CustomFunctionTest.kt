package com.opencreativeplus.plugin.function

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.INode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
// ---------------------------------------------------------------------------
// Fake helpers (same pattern as BuiltInNodeExecutionTest)
// ---------------------------------------------------------------------------

private class FakeVariableScope : VariableScope {
    private val map = mutableMapOf<String, Any>()
    override fun get(name: String) = map[name]
    override fun set(name: String, value: Any) { map[name] = value }
    override fun has(name: String) = map.containsKey(name)
    override fun clear() = map.clear()
}

private class FakeExecutionContext(
    override val plotId: UUID = UUID.randomUUID()
) : ExecutionContext {
    override val player = null
    override val eventData: Map<String, Any> = emptyMap()
    override val localScope: VariableScope = FakeVariableScope()
    override val plotScope: VariableScope = FakeVariableScope()
    override val savedScope: VariableScope = FakeVariableScope()
    override val operationCount: AtomicInteger = AtomicInteger(0)
    override val callStackSize: AtomicInteger = AtomicInteger(0)
    override val targets: MutableList<org.bukkit.entity.Entity> = mutableListOf()
    override suspend fun <T> syncContext(block: () -> T): T = block()
}

/** Simple recording action that appends [label] to [log] when executed. */
private fun recordingAction(log: MutableList<String>, label: String): IAction =
    object : IAction {
        override val nodeId = "recording"
        override val displayName = "Recording"
        override suspend fun execute(context: ExecutionContext) { log.add(label) }
    }

/** Action that reads [varName] from local scope and stores it in [captured]. */
private fun capturingAction(varName: String, captured: MutableList<Any?>): IAction =
    object : IAction {
        override val nodeId = "capturing"
        override val displayName = "Capturing"
        override suspend fun execute(context: ExecutionContext) {
            captured.add(context.localScope.get(varName))
        }
    }

// ---------------------------------------------------------------------------
// FunctionRegistry tests
// ---------------------------------------------------------------------------

/**
 * Tests for [FunctionRegistry]: function definition storage and lookup.
 *
 15.1, 15.2
 */
class FunctionRegistryTest {

    private lateinit var registry: FunctionRegistry

    @BeforeEach
    fun setup() {
        registry = FunctionRegistry()
    }

    @Test
    fun `registered function can be retrieved by name`() {
        val plotId = UUID.randomUUID()
        val def = FunctionRegistry.FunctionDefinition(
            name = "greet",
            parameterNames = listOf("player"),
            actions = emptyList()
        )

        registry.register(plotId, def)

        assertEquals(def, registry.get(plotId, "greet"))
    }

    @Test
    fun `get returns null for unknown function name`() {
        val plotId = UUID.randomUUID()

        assertNull(registry.get(plotId, "nonexistent"))
    }

    @Test
    fun `get returns null for unknown plot`() {
        val plotId = UUID.randomUUID()
        val otherPlot = UUID.randomUUID()
        registry.register(plotId, FunctionRegistry.FunctionDefinition("fn", emptyList(), emptyList()))

        assertNull(registry.get(otherPlot, "fn"))
    }

    @Test
    fun `registering a function with the same name overwrites the previous definition`() {
        val plotId = UUID.randomUUID()
        val first = FunctionRegistry.FunctionDefinition("fn", listOf("a"), emptyList())
        val second = FunctionRegistry.FunctionDefinition("fn", listOf("b", "c"), emptyList())

        registry.register(plotId, first)
        registry.register(plotId, second)

        assertEquals(second, registry.get(plotId, "fn"))
    }

    @Test
    fun `functions are isolated between plots`() {
        val plot1 = UUID.randomUUID()
        val plot2 = UUID.randomUUID()
        registry.register(plot1, FunctionRegistry.FunctionDefinition("fn", emptyList(), emptyList()))

        assertNull(registry.get(plot2, "fn"), "Function registered for plot1 should not be visible on plot2")
    }

    @Test
    fun `clearPlot removes all functions for that plot`() {
        val plotId = UUID.randomUUID()
        registry.register(plotId, FunctionRegistry.FunctionDefinition("fn1", emptyList(), emptyList()))
        registry.register(plotId, FunctionRegistry.FunctionDefinition("fn2", emptyList(), emptyList()))

        registry.clearPlot(plotId)

        assertNull(registry.get(plotId, "fn1"))
        assertNull(registry.get(plotId, "fn2"))
    }

    @Test
    fun `clearPlot does not affect other plots`() {
        val plot1 = UUID.randomUUID()
        val plot2 = UUID.randomUUID()
        registry.register(plot1, FunctionRegistry.FunctionDefinition("fn", emptyList(), emptyList()))
        registry.register(plot2, FunctionRegistry.FunctionDefinition("fn", emptyList(), emptyList()))

        registry.clearPlot(plot1)

        assertNull(registry.get(plot1, "fn"))
        assertEquals("fn", registry.get(plot2, "fn")?.name)
    }
}

// ---------------------------------------------------------------------------
// FunctionCallAction – function definition and calling ( 15.3)
// ---------------------------------------------------------------------------

/**
 * Tests for [FunctionCallAction]: function execution when called.
 *
15.3
 */
class FunctionCallActionDefinitionTest {

    private lateinit var registry: FunctionRegistry
    private val plotId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        registry = FunctionRegistry()
    }

    @Test
    fun `calling a function executes all its actions in order`() = runTest {
        val log = mutableListOf<String>()
        registry.register(
            plotId, FunctionRegistry.FunctionDefinition(
                name = "myFn",
                parameterNames = emptyList(),
                actions = listOf(
                    recordingAction(log, "step1"),
                    recordingAction(log, "step2"),
                    recordingAction(log, "step3")
                )
            )
        )

        val ctx = FakeExecutionContext(plotId)
        FunctionCallAction("myFn", emptyMap(), registry).execute(ctx)

        assertEquals(listOf("step1", "step2", "step3"), log)
    }

    @Test
    fun `calling a function with no actions completes without error`() = runTest {
        registry.register(
            plotId, FunctionRegistry.FunctionDefinition("empty", emptyList(), emptyList())
        )

        val ctx = FakeExecutionContext(plotId)
        FunctionCallAction("empty", emptyMap(), registry).execute(ctx) // should not throw
    }

    @Test
    fun `calling an unknown function throws IllegalArgumentException`() = runTest {
        val ctx = FakeExecutionContext(plotId)

        assertFailsWith<IllegalArgumentException> {
            FunctionCallAction("doesNotExist", emptyMap(), registry).execute(ctx)
        }
    }

    @Test
    fun `nodeId and displayName are correct`() {
        val action = FunctionCallAction("fn", emptyMap(), registry)
        assertEquals("function_call", action.nodeId)
        assertEquals("Function Call", action.displayName)
    }
}

// ---------------------------------------------------------------------------
// FunctionCallAction – parameter passing ( 15.2, 15.3)
// ---------------------------------------------------------------------------

/**
 * Tests for [FunctionCallAction]: parameter passing into function scope.
 *
15.3
 */
class FunctionCallActionParameterTest {

    private lateinit var registry: FunctionRegistry
    private val plotId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        registry = FunctionRegistry()
    }

    @Test
    fun `parameters are set in local scope before function actions execute`() = runTest {
        val captured = mutableListOf<Any?>()
        registry.register(
            plotId, FunctionRegistry.FunctionDefinition(
                name = "greet",
                parameterNames = listOf("name"),
                actions = listOf(capturingAction("name", captured))
            )
        )

        val ctx = FakeExecutionContext(plotId)
        FunctionCallAction("greet", mapOf("name" to "Alice"), registry).execute(ctx)

        assertEquals(listOf<Any?>("Alice"), captured)
    }

    @Test
    fun `multiple parameters are all passed correctly`() = runTest {
        val capturedX = mutableListOf<Any?>()
        val capturedY = mutableListOf<Any?>()
        registry.register(
            plotId, FunctionRegistry.FunctionDefinition(
                name = "move",
                parameterNames = listOf("x", "y"),
                actions = listOf(
                    capturingAction("x", capturedX),
                    capturingAction("y", capturedY)
                )
            )
        )

        val ctx = FakeExecutionContext(plotId)
        FunctionCallAction("move", mapOf("x" to 10, "y" to 20), registry).execute(ctx)

        assertEquals(listOf<Any?>(10), capturedX)
        assertEquals(listOf<Any?>(20), capturedY)
    }

    @Test
    fun `extra arguments not in parameterNames are ignored`() = runTest {
        val captured = mutableListOf<Any?>()
        registry.register(
            plotId, FunctionRegistry.FunctionDefinition(
                name = "fn",
                parameterNames = listOf("a"),
                actions = listOf(capturingAction("a", captured))
            )
        )

        val ctx = FakeExecutionContext(plotId)
        // Pass "a" and an extra "b" that is not declared
        FunctionCallAction("fn", mapOf("a" to "hello", "b" to "ignored"), registry).execute(ctx)

        assertEquals(listOf<Any?>("hello"), captured)
    }

    @Test
    fun `missing argument results in null value in scope`() = runTest {
        val captured = mutableListOf<Any?>()
        registry.register(
            plotId, FunctionRegistry.FunctionDefinition(
                name = "fn",
                parameterNames = listOf("required"),
                actions = listOf(capturingAction("required", captured))
            )
        )

        val ctx = FakeExecutionContext(plotId)
        // Call without providing the "required" argument
        FunctionCallAction("fn", emptyMap(), registry).execute(ctx)

        // The parameter was declared but not provided, so it should not be set
        assertEquals(listOf<Any?>(null), captured)
    }

    @Test
    fun `parameters of different types are passed correctly`() = runTest {
        val capturedInt = mutableListOf<Any?>()
        val capturedDouble = mutableListOf<Any?>()
        val capturedBool = mutableListOf<Any?>()
        registry.register(
            plotId, FunctionRegistry.FunctionDefinition(
                name = "typed",
                parameterNames = listOf("i", "d", "b"),
                actions = listOf(
                    capturingAction("i", capturedInt),
                    capturingAction("d", capturedDouble),
                    capturingAction("b", capturedBool)
                )
            )
        )

        val ctx = FakeExecutionContext(plotId)
        FunctionCallAction("typed", mapOf("i" to 42, "d" to 3.14, "b" to true), registry).execute(ctx)

        assertEquals(42, capturedInt.first())
        assertEquals(3.14, capturedDouble.first())
        assertEquals(true, capturedBool.first())
    }
}

// ---------------------------------------------------------------------------
// FunctionCallAction – recursion detection ( 15.5)
// ---------------------------------------------------------------------------

/**
 * Tests for [FunctionCallAction]: recursion detection via call-depth limit.
 *
15.5
 */
class FunctionCallActionRecursionTest {

    private lateinit var registry: FunctionRegistry
    private val plotId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        registry = FunctionRegistry()
    }

    /**
     * Registers a self-recursive function: calling "recurse" will call "recurse" again.
     * The FunctionCallAction inside the definition calls the same function.
     */
    private fun registerSelfRecursiveFunction(name: String = "recurse") {
        // We need a placeholder first so the inner FunctionCallAction can reference it
        // Register with a self-referencing FunctionCallAction as its body
        val selfCall = FunctionCallAction(name, emptyMap(), registry)
        registry.register(
            plotId, FunctionRegistry.FunctionDefinition(
                name = name,
                parameterNames = emptyList(),
                actions = listOf(selfCall)
            )
        )
    }

    @Test
    fun `direct recursion exceeding MAX_DEPTH throws StackOverflowError`() = runTest {
        registerSelfRecursiveFunction("recurse")
        val ctx = FakeExecutionContext(plotId)

        assertFailsWith<StackOverflowError> {
            FunctionCallAction("recurse", emptyMap(), registry).execute(ctx)
        }
    }

    @Test
    fun `MAX_DEPTH constant is 100`() {
        assertEquals(100, FunctionCallAction.MAX_DEPTH)
    }

    @Test
    fun `StackOverflowError message mentions the function name and depth limit`() = runTest {
        registerSelfRecursiveFunction("infiniteLoop")
        val ctx = FakeExecutionContext(plotId)

        val ex = assertFailsWith<StackOverflowError> {
            FunctionCallAction("infiniteLoop", emptyMap(), registry).execute(ctx)
        }

        assertTrue(
            ex.message?.contains("infiniteLoop") == true,
            "Error message should contain the function name, got: ${ex.message}"
        )
        assertTrue(
            ex.message?.contains("100") == true,
            "Error message should mention the depth limit, got: ${ex.message}"
        )
    }

    @Test
    fun `non-recursive function call does not throw`() = runTest {
        val log = mutableListOf<String>()
        registry.register(
            plotId, FunctionRegistry.FunctionDefinition(
                name = "safe",
                parameterNames = emptyList(),
                actions = listOf(recordingAction(log, "executed"))
            )
        )

        val ctx = FakeExecutionContext(plotId)
        FunctionCallAction("safe", emptyMap(), registry).execute(ctx)

        assertEquals(listOf("executed"), log)
    }

    @Test
    fun `mutual recursion between two functions is also detected`() = runTest {
        // "a" calls "b", "b" calls "a" → mutual infinite recursion
        val callB = FunctionCallAction("b", emptyMap(), registry)
        val callA = FunctionCallAction("a", emptyMap(), registry)

        registry.register(
            plotId, FunctionRegistry.FunctionDefinition("a", emptyList(), listOf(callB))
        )
        registry.register(
            plotId, FunctionRegistry.FunctionDefinition("b", emptyList(), listOf(callA))
        )

        val ctx = FakeExecutionContext(plotId)

        assertFailsWith<StackOverflowError> {
            FunctionCallAction("a", emptyMap(), registry).execute(ctx)
        }
    }

    @Test
    fun `call depth resets after a successful non-recursive call`() = runTest {
        // Calling a safe function twice should not accumulate depth
        val log = mutableListOf<String>()
        registry.register(
            plotId, FunctionRegistry.FunctionDefinition(
                name = "safe",
                parameterNames = emptyList(),
                actions = listOf(recordingAction(log, "ok"))
            )
        )

        val ctx = FakeExecutionContext(plotId)
        FunctionCallAction("safe", emptyMap(), registry).execute(ctx)
        FunctionCallAction("safe", emptyMap(), registry).execute(ctx)

        assertEquals(listOf("ok", "ok"), log)
    }
}

// ---------------------------------------------------------------------------
// FunctionCallAction – Bug 3 fix: callStackSize in ExecutionContext (not ThreadLocal)
// ---------------------------------------------------------------------------

/**
 * Fix-check tests for Bug 3: callStackSize must be tracked in ExecutionContext,
 * not in a ThreadLocal, so it survives coroutine thread switches.
 *
 * Bug condition: execution.isCoroutine = true AND execution.threadSwitches > 0
 */
class FunctionCallActionCallStackSizeTest {

    private lateinit var registry: FunctionRegistry
    private val plotId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        registry = FunctionRegistry()
    }

    /**
     * Fix check (Bug 3): callStackSize on the context reflects the correct depth
     * even when the coroutine switches to a different dispatcher mid-execution.
     *
     * With the old ThreadLocal approach, switching dispatchers would reset the counter
     * to 0 on the new thread. With callStackSize in ExecutionContext, the counter
     * persists correctly across thread switches.
     */
    @Test
    fun `callStackSize on context is correct after coroutine thread switch`() = runTest {
        val capturedDepths = mutableListOf<Int>()

        // A dispatcher that always uses a different thread pool (simulates thread switch)
        val altDispatcher = kotlinx.coroutines.Dispatchers.Default

        // Context that switches to altDispatcher in syncContext (simulating a thread switch)
        val ctx = object : ExecutionContext {
            override val plotId: UUID = this@FunctionCallActionCallStackSizeTest.plotId
            override val player = null
            override val eventData: Map<String, Any> = emptyMap()
            override val localScope: VariableScope = FakeVariableScope()
            override val plotScope: VariableScope = FakeVariableScope()
            override val savedScope: VariableScope = FakeVariableScope()
            override val operationCount: AtomicInteger = AtomicInteger(0)
            override val callStackSize: AtomicInteger = AtomicInteger(0)
            override val targets: MutableList<org.bukkit.entity.Entity> = mutableListOf()
            override suspend fun <T> syncContext(block: () -> T): T =
                kotlinx.coroutines.withContext(altDispatcher) { block() }
        }

        // Action that records the current callStackSize after a thread switch
        val recordDepthAfterSwitch = object : IAction {
            override val nodeId = "record_depth"
            override val displayName = "Record Depth"
            override suspend fun execute(context: ExecutionContext) {
                // Switch threads, then record the depth — with ThreadLocal this would be 0
                context.syncContext {
                    capturedDepths.add(context.callStackSize.get())
                }
            }
        }

        registry.register(
            plotId, FunctionRegistry.FunctionDefinition(
                name = "fn",
                parameterNames = emptyList(),
                actions = listOf(recordDepthAfterSwitch)
            )
        )

        FunctionCallAction("fn", emptyMap(), registry).execute(ctx)

        // After the thread switch, callStackSize should be 1 (we are inside one function call)
        // With the old ThreadLocal bug, this would be 0 on the new thread
        assertEquals(listOf(1), capturedDepths,
            "callStackSize must be 1 inside the function body even after a thread switch")

        // After execute() returns, callStackSize must be back to 0
        assertEquals(0, ctx.callStackSize.get(),
            "callStackSize must be 0 after function call completes")
    }

    /**
     * Fix check: nested calls increment callStackSize correctly across thread switches.
     * Verifies that depth tracking works for 3 levels of nesting with thread switches.
     */
    @Test
    fun `callStackSize tracks nested call depth correctly across thread switches`() = runTest {
        val altDispatcher = kotlinx.coroutines.Dispatchers.Default
        val depthsAtLevel = mutableListOf<Int>()

        val ctx = object : ExecutionContext {
            override val plotId: UUID = this@FunctionCallActionCallStackSizeTest.plotId
            override val player = null
            override val eventData: Map<String, Any> = emptyMap()
            override val localScope: VariableScope = FakeVariableScope()
            override val plotScope: VariableScope = FakeVariableScope()
            override val savedScope: VariableScope = FakeVariableScope()
            override val operationCount: AtomicInteger = AtomicInteger(0)
            override val callStackSize: AtomicInteger = AtomicInteger(0)
            override val targets: MutableList<org.bukkit.entity.Entity> = mutableListOf()
            override suspend fun <T> syncContext(block: () -> T): T =
                kotlinx.coroutines.withContext(altDispatcher) { block() }
        }

        // "level3" records depth after thread switch, then returns
        val recordAtLevel3 = object : IAction {
            override val nodeId = "record3"
            override val displayName = "Record3"
            override suspend fun execute(context: ExecutionContext) {
                context.syncContext { depthsAtLevel.add(context.callStackSize.get()) }
            }
        }
        registry.register(plotId, FunctionRegistry.FunctionDefinition("level3", emptyList(), listOf(recordAtLevel3)))

        // "level2" calls level3
        val callLevel3 = FunctionCallAction("level3", emptyMap(), registry)
        registry.register(plotId, FunctionRegistry.FunctionDefinition("level2", emptyList(), listOf(callLevel3)))

        // "level1" calls level2
        val callLevel2 = FunctionCallAction("level2", emptyMap(), registry)
        registry.register(plotId, FunctionRegistry.FunctionDefinition("level1", emptyList(), listOf(callLevel2)))

        FunctionCallAction("level1", emptyMap(), registry).execute(ctx)

        // At level3 body, we are 3 calls deep: level1 → level2 → level3
        assertEquals(listOf(3), depthsAtLevel,
            "callStackSize must be 3 at level3 body even after thread switch")

        // After all calls complete, counter must be 0
        assertEquals(0, ctx.callStackSize.get())
    }

    /**
     * Preservation test (Bug 3): a non-recursive function call does not trigger
     * the recursion limit and callStackSize returns to 0 after completion.
     */
    @Test
    fun `non-recursive function does not trigger recursion limit and callStackSize resets to zero`() = runTest {
        val log = mutableListOf<String>()
        registry.register(
            plotId, FunctionRegistry.FunctionDefinition(
                name = "simple",
                parameterNames = emptyList(),
                actions = listOf(recordingAction(log, "ran"))
            )
        )

        val ctx = FakeExecutionContext(plotId)

        // Should not throw
        FunctionCallAction("simple", emptyMap(), registry).execute(ctx)

        assertEquals(listOf("ran"), log)
        assertEquals(0, ctx.callStackSize.get(),
            "callStackSize must be 0 after a non-recursive call completes")
    }

    /**
     * Preservation test: calling the same function multiple times sequentially
     * does not accumulate callStackSize.
     */
    @Test
    fun `sequential calls do not accumulate callStackSize`() = runTest {
        val log = mutableListOf<String>()
        registry.register(
            plotId, FunctionRegistry.FunctionDefinition(
                name = "fn",
                parameterNames = emptyList(),
                actions = listOf(recordingAction(log, "ok"))
            )
        )

        val ctx = FakeExecutionContext(plotId)

        repeat(5) {
            FunctionCallAction("fn", emptyMap(), registry).execute(ctx)
            assertEquals(0, ctx.callStackSize.get(),
                "callStackSize must be 0 after each call, iteration $it")
        }

        assertEquals(List(5) { "ok" }, log)
    }
}
