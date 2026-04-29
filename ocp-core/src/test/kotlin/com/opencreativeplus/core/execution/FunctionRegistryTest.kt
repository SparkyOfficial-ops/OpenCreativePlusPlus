package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.api.node.IFunctionCall
import com.opencreativeplus.core.watchdog.Watchdog
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [FunctionRegistry] and [ExecutionEngine] function call handling.
 *
 * Covers:
 * - Calling an existing function (Req 5.3): function is found and its actions execute
 * - Calling a non-existent function (Req 5.4): no crash, call is silently skipped
 * - Exceeding recursion depth of 32 (Req 5.6): chain terminates with a stack overflow warning
 */
class FunctionRegistryTest {

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

    private lateinit var registry: FunctionRegistry
    private lateinit var engine: ExecutionEngine
    private lateinit var coroutineConfig: CoroutineConfiguration
    private lateinit var variableManager: VariableManager
    private lateinit var logMessages: MutableList<String>

    @BeforeEach
    fun setup() {
        registry = FunctionRegistry()

        val tpsMonitor = mockk<com.opencreativeplus.core.watchdog.TPSMonitor>()
        every { tpsMonitor.getCurrentTPS() } returns 20.0
        val watchdog = Watchdog(tpsMonitor)

        val db = mockk<com.mongodb.kotlin.client.coroutine.MongoDatabase>(relaxed = true)
        variableManager = VariableManager(db)

        coroutineConfig = CoroutineConfiguration(syncRunner = { it() })

        // Capture log messages to verify warnings
        logMessages = mutableListOf()
        val captureLogger = Logger.getLogger("FunctionRegistryTest.${System.nanoTime()}")
        captureLogger.useParentHandlers = false
        captureLogger.addHandler(object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) {
                logMessages.add(record.message)
            }
            override fun flush() {}
            override fun close() {}
        })

        engine = ExecutionEngine(
            watchdog = watchdog,
            variableManager = variableManager,
            coroutineConfig = coroutineConfig,
            functionRegistry = registry,
            logger = captureLogger
        )
    }

    @AfterEach
    fun teardown() {
        coroutineConfig.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun mockEvent(): IEvent {
        val e = mockk<IEvent>()
        every { e.nodeId } returns "test_event"
        every { e.displayName } returns "Test Event"
        every { e.eventType } returns "test"
        return e
    }

    /** Build a CompiledScript from a list of IAction lambdas. */
    private fun script(vararg actions: IAction): CompiledScript =
        CompiledScript(event = mockEvent(), actions = actions.toList(), sourceLocation = "test@0,0,0")

    /** Build a named function CompiledScript. */
    private fun functionScript(name: String, vararg actions: IAction): CompiledScript =
        CompiledScript(
            event = mockEvent(),
            actions = actions.toList(),
            sourceLocation = "func@0,0,0",
            isFunctionEntry = true,
            functionName = name
        )

    /** An IAction that records its execution. */
    private fun recordingAction(log: MutableList<String>, label: String): IAction = object : IAction {
        override val nodeId = "recording"
        override val displayName = "Recording"
        override suspend fun execute(context: ExecutionContext) {
            log.add(label)
        }
    }

    /**
     * Returns a mock Player so that ExecutionContextImpl initialises targets = [player].
     * This is required because the engine only executes non-IFunctionCall actions when
     * targets is non-empty (Req 1.8/1.9).
     */
    private fun mockPlayer(): Player {
        val p = mockk<Player>(relaxed = true)
        every { p.uniqueId } returns UUID.randomUUID()
        return p
    }

    /** An IFunctionCall action that calls the named function. */
    private fun callFunctionAction(name: String): IFunctionCall = object : IFunctionCall {
        override val nodeId = "call_function"
        override val displayName = "Call Function"
        override val targetFunctionName = name
        override suspend fun execute(context: ExecutionContext) {
            // Handled by ExecutionEngine — body not called directly
        }
    }

    // =========================================================================
    // 1. FunctionRegistry — basic register / get / loadFromAST
    // =========================================================================

    @Test
    fun `register and get returns the registered script`() {
        // Given: a compiled function script
        val funcScript = functionScript("myFunc")

        // When: registered
        registry.register("myFunc", funcScript)

        // Then: get returns the same instance
        assertEquals(funcScript, registry.get("myFunc"))
    }

    @Test
    fun `get returns null for unknown function name`() {
        // Given: empty registry
        // When / Then: unknown name returns null
        assertNull(registry.get("nonExistent"))
    }

    @Test
    fun `register overwrites existing function with same name`() {
        // Given: two scripts with the same name
        val first = functionScript("fn")
        val second = functionScript("fn")
        registry.register("fn", first)

        // When: registering again with the same name
        registry.register("fn", second)

        // Then: the second script replaces the first
        assertEquals(second, registry.get("fn"))
    }

    @Test
    fun `loadFromAST registers only function entry scripts`() {
        // Given: a mix of regular and function-entry scripts
        val regularScript = script()
        val funcA = functionScript("funcA")
        val funcB = functionScript("funcB")

        // When: loading from AST
        registry.loadFromAST(listOf(regularScript, funcA, funcB))

        // Then: only function entries are registered
        assertEquals(funcA, registry.get("funcA"))
        assertEquals(funcB, registry.get("funcB"))
        assertEquals(2, registry.size())
    }

    @Test
    fun `loadFromAST ignores scripts with null functionName`() {
        // Given: a script marked as function entry but with null name
        val noNameScript = CompiledScript(
            event = mockEvent(),
            actions = emptyList(),
            sourceLocation = "test@0,0,0",
            isFunctionEntry = true,
            functionName = null
        )

        // When: loading from AST
        registry.loadFromAST(listOf(noNameScript))

        // Then: nothing is registered
        assertEquals(0, registry.size())
    }

    @Test
    fun `clear removes all registered functions`() {
        // Given: a registry with two functions
        registry.register("fn1", functionScript("fn1"))
        registry.register("fn2", functionScript("fn2"))

        // When: clearing
        registry.clear()

        // Then: all functions are gone
        assertEquals(0, registry.size())
        assertNull(registry.get("fn1"))
        assertNull(registry.get("fn2"))
    }

    // =========================================================================
    // 2. Req 5.3 — Calling an existing function executes its actions
    // =========================================================================

    @Test
    fun `calling an existing function executes its actions (Req 5_3)`() = runBlocking {
        // Given: a function "greet" with a recording action
        val executedActions = mutableListOf<String>()
        registry.register("greet", functionScript("greet", recordingAction(executedActions, "greet_body")))

        // A script that calls "greet"
        val callerScript = script(callFunctionAction("greet"))
        val plotId = UUID.randomUUID()

        // When: executing the caller script
        // Note: a player is required so targets is non-empty — the engine only executes
        // non-IFunctionCall actions when targets is non-empty (Req 1.8/1.9).
        engine.executeScript(callerScript, plotId, mockPlayer(), emptyMap())
        delay(200)

        // Then: the function body was executed
        assertTrue(executedActions.contains("greet_body"), "Function body should have been executed")
    }

    @Test
    fun `calling an existing function executes all its actions in order (Req 5_3)`() = runBlocking {
        // Given: a function with multiple actions
        val log = mutableListOf<String>()
        registry.register("multi", functionScript(
            "multi",
            recordingAction(log, "step1"),
            recordingAction(log, "step2"),
            recordingAction(log, "step3")
        ))

        val callerScript = script(callFunctionAction("multi"))
        val plotId = UUID.randomUUID()

        // When
        engine.executeScript(callerScript, plotId, mockPlayer(), emptyMap())
        delay(200)

        // Then: all actions executed in order
        assertEquals(listOf("step1", "step2", "step3"), log)
    }

    @Test
    fun `actions after a function call still execute (Req 5_3)`() = runBlocking {
        // Given: a script that calls a function and then has another action
        val log = mutableListOf<String>()
        registry.register("fn", functionScript("fn", recordingAction(log, "inside_fn")))

        val callerScript = script(
            callFunctionAction("fn"),
            recordingAction(log, "after_call")
        )
        val plotId = UUID.randomUUID()

        // When
        engine.executeScript(callerScript, plotId, mockPlayer(), emptyMap())
        delay(200)

        // Then: both the function body and the action after the call executed
        assertEquals(listOf("inside_fn", "after_call"), log)
    }

    // =========================================================================
    // 3. Req 5.4 — Calling a non-existent function: no crash, error is logged
    // =========================================================================

    @Test
    fun `calling a non-existent function does not crash (Req 5_4)`() = runBlocking {
        // Given: an empty registry (no functions registered)
        val log = mutableListOf<String>()
        val callerScript = script(
            callFunctionAction("doesNotExist"),
            recordingAction(log, "after_missing_call")
        )
        val plotId = UUID.randomUUID()

        // When: executing — should not throw
        engine.executeScript(callerScript, plotId, mockPlayer(), emptyMap())
        delay(200)

        // Then: execution continues after the missing call
        assertTrue(log.contains("after_missing_call"), "Execution should continue after a missing function call")
    }

    @Test
    fun `calling a non-existent function logs a warning (Req 5_4)`() = runBlocking {
        // Given: an empty registry
        val callerScript = script(callFunctionAction("missingFn"))
        val plotId = UUID.randomUUID()

        // When
        engine.executeScript(callerScript, plotId, mockPlayer(), emptyMap())
        delay(200)

        // Then: a warning was logged mentioning the missing function name
        assertTrue(
            logMessages.any { it.contains("missingFn") },
            "A warning should be logged for the missing function. Logged: $logMessages"
        )
    }

    @Test
    fun `calling a non-existent function does not affect other scripts (Req 5_4)`() = runBlocking {
        // Given: a script that calls a missing function, and another independent script
        val log = mutableListOf<String>()
        val failingScript = script(callFunctionAction("ghost"))
        val normalScript = script(recordingAction(log, "normal_executed"))
        val plotId = UUID.randomUUID()

        // When: both scripts run
        engine.executeScript(failingScript, plotId, mockPlayer(), emptyMap())
        engine.executeScript(normalScript, plotId, mockPlayer(), emptyMap())
        delay(200)

        // Then: the normal script still completes
        assertTrue(log.contains("normal_executed"), "Normal script should complete even when another calls a missing function")
    }

    // =========================================================================
    // 4. Req 5.6 — Exceeding recursion depth of 32 terminates chain with warning
    // =========================================================================

    @Test
    fun `recursion depth exceeding 32 terminates chain and logs stack overflow warning (Req 5_6)`() = runBlocking {
        // Given: a function "recurse" that calls itself — infinite recursion
        // We register it first, then build the recursive call action
        val recursiveCallAction = callFunctionAction("recurse")
        val recursiveScript = functionScript("recurse", recursiveCallAction)
        registry.register("recurse", recursiveScript)

        // A top-level script that starts the recursion
        val callerScript = script(callFunctionAction("recurse"))
        val plotId = UUID.randomUUID()

        // When: executing — should terminate without crashing
        engine.executeScript(callerScript, plotId, null, emptyMap())
        delay(500)

        // Then: a stack overflow warning was logged
        assertTrue(
            logMessages.any { it.contains("stack overflow", ignoreCase = true) || it.contains("call stack", ignoreCase = true) },
            "A stack overflow warning should be logged. Logged: $logMessages"
        )
    }

    @Test
    fun `recursion terminates at exactly depth 32 — no crash (Req 5_6)`() = runBlocking {
        // Given: a chain of 40 nested function calls (exceeds the limit of 32)
        // Build a chain: fn0 calls fn1, fn1 calls fn2, ..., fn39 has a recording action
        val log = mutableListOf<String>()
        val chainDepth = 40

        // Register the deepest function (no further calls)
        registry.register("fn${chainDepth - 1}", functionScript("fn${chainDepth - 1}", recordingAction(log, "deepest")))

        // Register intermediate functions that call the next one
        for (i in chainDepth - 2 downTo 0) {
            registry.register("fn$i", functionScript("fn$i", callFunctionAction("fn${i + 1}")))
        }

        // A top-level script that starts the chain
        val callerScript = script(callFunctionAction("fn0"))
        val plotId = UUID.randomUUID()

        // When: executing — should not throw even though depth exceeds 32
        engine.executeScript(callerScript, plotId, null, emptyMap())
        delay(500)

        // Then: a stack overflow warning was logged (chain was cut at depth 32)
        assertTrue(
            logMessages.any { it.contains("stack overflow", ignoreCase = true) || it.contains("call stack", ignoreCase = true) },
            "Stack overflow warning should be logged when depth exceeds ${ExecutionEngine.MAX_CALL_STACK_SIZE}. Logged: $logMessages"
        )
        // And: the deepest function was NOT reached (chain was terminated before depth 40)
        assertTrue(
            !log.contains("deepest"),
            "The deepest function should not execute when the call stack limit is exceeded"
        )
    }

    @Test
    fun `call stack depth of exactly 32 is allowed — no warning (Req 5_6)`() = runBlocking {
        // Given: a chain of exactly 32 nested function calls (at the limit, not exceeding it)
        val log = mutableListOf<String>()
        val chainDepth = ExecutionEngine.MAX_CALL_STACK_SIZE  // 32

        // Register the deepest function
        registry.register("fn${chainDepth - 1}", functionScript("fn${chainDepth - 1}", recordingAction(log, "deepest")))

        // Register intermediate functions
        for (i in chainDepth - 2 downTo 0) {
            registry.register("fn$i", functionScript("fn$i", callFunctionAction("fn${i + 1}")))
        }

        // Top-level script starts the chain (this is depth 1)
        val callerScript = script(callFunctionAction("fn0"))
        val plotId = UUID.randomUUID()

        // When
        engine.executeScript(callerScript, plotId, null, emptyMap())
        delay(500)

        // Then: no stack overflow warning (depth 32 is the limit, not exceeded)
        val hasStackOverflowWarning = logMessages.any {
            it.contains("stack overflow", ignoreCase = true) || it.contains("call stack", ignoreCase = true)
        }
        assertTrue(
            !hasStackOverflowWarning,
            "No stack overflow warning should be logged for depth exactly at the limit. Logged: $logMessages"
        )
    }

    @Test
    fun `execution continues normally after a stack overflow is caught (Req 5_6)`() = runBlocking {
        // Given: a recursive function that will overflow, plus a normal action after the call
        val log = mutableListOf<String>()
        val recursiveCallAction = callFunctionAction("infiniteRecurse")
        val recursiveScript = functionScript("infiniteRecurse", recursiveCallAction)
        registry.register("infiniteRecurse", recursiveScript)

        // Script: call the recursive function, then record "after_overflow"
        val callerScript = script(
            callFunctionAction("infiniteRecurse"),
            recordingAction(log, "after_overflow")
        )
        val plotId = UUID.randomUUID()

        // When
        engine.executeScript(callerScript, plotId, mockPlayer(), emptyMap())
        delay(500)

        // Then: execution continues after the stack overflow — the action after the call runs
        assertTrue(
            log.contains("after_overflow"),
            "Execution should continue after a stack overflow is caught. Log: $log"
        )
    }
}
