// Feature: ocp-visual-programming-platform, Integration Test: FunctionCallIntegrationTest

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
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [ExecutionEngine] + [FunctionRegistry] function call pipeline.
 *
 * Exercises the full end-to-end execution path with real [ExecutionEngine], real
 * [FunctionRegistry], real [VariableManager] (mocked MongoDB), and real
 * [CoroutineConfiguration].
 *
 * Verifies:
 * - A function call executes the function body (Req 5.3)
 * - The function's localScope is isolated from the caller's localScope (Req 5.5)
 * - The targets list is inherited from the caller into the function (Req 5.5)
 * - Writes to localScope inside the function do NOT leak back to the caller (Req 5.5)
 * - Writes to plotScope inside the function ARE visible to the caller (Req 5.5)
 *
 * Requirements: 5.5
 */
class FunctionCallIntegrationTest {

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

        logMessages = mutableListOf()
        val captureLogger = Logger.getLogger("FunctionCallIntegrationTest.${System.nanoTime()}")
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

    private fun script(vararg actions: IAction): CompiledScript =
        CompiledScript(event = mockEvent(), actions = actions.toList(), sourceLocation = "test@0,0,0")

    private fun functionScript(name: String, vararg actions: IAction): CompiledScript =
        CompiledScript(
            event = mockEvent(),
            actions = actions.toList(),
            sourceLocation = "func@0,0,0",
            isFunctionEntry = true,
            functionName = name
        )

    private fun recordingAction(log: MutableList<String>, label: String): IAction = object : IAction {
        override val nodeId = "recording"
        override val displayName = "Recording"
        override suspend fun execute(context: ExecutionContext) {
            log.add(label)
        }
    }

    private fun mockPlayer(): Player {
        val p = mockk<Player>(relaxed = true)
        every { p.uniqueId } returns UUID.randomUUID()
        return p
    }

    private fun callFunctionAction(name: String): IFunctionCall = object : IFunctionCall {
        override val nodeId = "call_function"
        override val displayName = "Call Function"
        override val targetFunctionName = name
        override suspend fun execute(context: ExecutionContext) {
            // Handled by ExecutionEngine — body not called directly
        }
    }

    // =========================================================================
    // 1. Req 5.3 — Function body executes when called
    // =========================================================================

    /**
     * Verifies that when a "call_function" action is executed, the function body
     * (its registered actions) actually runs.
     *
     * Requirements: 5.3
     */
    @Test
    fun `function body executes when called (Req 5_3)`() = runBlocking {
        // Given: a function "greet" with a recording action
        val executedActions = mutableListOf<String>()
        registry.register("greet", functionScript("greet", recordingAction(executedActions, "greet_body")))

        // A caller script that invokes "greet"
        val callerScript = script(callFunctionAction("greet"))
        val plotId = UUID.randomUUID()

        // When: executing the caller script with a player (so targets is non-empty)
        engine.executeScript(callerScript, plotId, mockPlayer(), emptyMap())
        delay(200)

        // Then: the function body was executed
        assertTrue(
            executedActions.contains("greet_body"),
            "Function body should have been executed. Executed: $executedActions"
        )
    }

    // =========================================================================
    // 2. Req 5.5 — Caller localScope variable is NOT visible inside function
    // =========================================================================

    /**
     * Verifies that a variable set in the caller's localScope is not visible inside
     * the function's localScope (scope isolation).
     *
     * Requirements: 5.5
     */
    @Test
    fun `caller localScope variable is not visible inside function (Req 5_5)`() = runBlocking {
        // Shared state to capture what the function saw
        val functionSawValue = AtomicReference<Any?>("NOT_SET")

        // Action that writes a variable to localScope in the caller
        val callerWriteAction = object : IAction {
            override val nodeId = "caller_write"
            override val displayName = "Caller Write"
            override suspend fun execute(context: ExecutionContext) {
                context.localScope.set("myVar", "caller_value")
            }
        }

        // Action inside the function that reads the same variable name from its own localScope
        val functionReadAction = object : IAction {
            override val nodeId = "function_read"
            override val displayName = "Function Read"
            override suspend fun execute(context: ExecutionContext) {
                // Record what the function's localScope contains for "myVar"
                functionSawValue.set(context.localScope.get("myVar"))
            }
        }

        registry.register("isolatedFn", functionScript("isolatedFn", functionReadAction))

        // Caller: write to localScope, then call the function
        val callerScript = script(
            callerWriteAction,
            callFunctionAction("isolatedFn")
        )
        val plotId = UUID.randomUUID()

        // When
        engine.executeScript(callerScript, plotId, mockPlayer(), emptyMap())
        delay(200)

        // Then: the function saw null — caller's localScope was not inherited
        assertNull(
            functionSawValue.get(),
            "Function's localScope should not contain caller's variable. Saw: ${functionSawValue.get()}"
        )
    }

    // =========================================================================
    // 3. Req 5.5 — Function localScope write does NOT leak to caller
    // =========================================================================

    /**
     * Verifies that a variable written to localScope inside the function does not
     * leak back into the caller's localScope after the function returns.
     *
     * Requirements: 5.5
     */
    @Test
    fun `function localScope write does not leak to caller (Req 5_5)`() = runBlocking {
        // Shared state to capture what the caller sees after the function returns
        val callerSawAfterCall = AtomicReference<Any?>("NOT_SET")

        // Action inside the function that writes to its own localScope
        val functionWriteAction = object : IAction {
            override val nodeId = "function_write"
            override val displayName = "Function Write"
            override suspend fun execute(context: ExecutionContext) {
                context.localScope.set("fnVar", "function_value")
            }
        }

        // Action in the caller that reads the variable after the function returns
        val callerReadAfterCallAction = object : IAction {
            override val nodeId = "caller_read_after"
            override val displayName = "Caller Read After"
            override suspend fun execute(context: ExecutionContext) {
                callerSawAfterCall.set(context.localScope.get("fnVar"))
            }
        }

        registry.register("writingFn", functionScript("writingFn", functionWriteAction))

        // Caller: call the function, then read from localScope
        val callerScript = script(
            callFunctionAction("writingFn"),
            callerReadAfterCallAction
        )
        val plotId = UUID.randomUUID()

        // When
        engine.executeScript(callerScript, plotId, mockPlayer(), emptyMap())
        delay(200)

        // Then: the caller's localScope does not contain the function's variable
        assertNull(
            callerSawAfterCall.get(),
            "Caller's localScope should not contain function's variable after call returns. Saw: ${callerSawAfterCall.get()}"
        )
    }

    // =========================================================================
    // 4. Req 5.5 — targets list is inherited from caller into function
    // =========================================================================

    /**
     * Verifies that the function inherits the caller's targets list (same elements).
     *
     * Requirements: 5.5
     */
    @Test
    fun `targets list is inherited from caller into function (Req 5_5)`() = runBlocking {
        // Shared state to capture targets size seen inside the function
        val functionTargetsSize = AtomicReference<Int>(-1)

        // Action inside the function that records the targets size
        val functionCheckTargetsAction = object : IAction {
            override val nodeId = "function_check_targets"
            override val displayName = "Function Check Targets"
            override suspend fun execute(context: ExecutionContext) {
                functionTargetsSize.set(context.targets.size)
            }
        }

        registry.register("targetsFn", functionScript("targetsFn", functionCheckTargetsAction))

        // Caller script with a real player (so targets = [player])
        val callerScript = script(callFunctionAction("targetsFn"))
        val plotId = UUID.randomUUID()
        val player = mockPlayer()

        // When: execute with a player so targets is initialized to [player]
        engine.executeScript(callerScript, plotId, player, emptyMap())
        delay(200)

        // Then: the function saw exactly 1 target (the player)
        assertEquals(
            1,
            functionTargetsSize.get(),
            "Function should inherit caller's targets list (size 1). Saw: ${functionTargetsSize.get()}"
        )
    }

    // =========================================================================
    // 5. Req 5.5 — plotScope write inside function IS visible to caller
    // =========================================================================

    /**
     * Verifies that writes to plotScope inside the function are visible to the caller
     * after the function returns (plotScope is shared, not isolated).
     *
     * Requirements: 5.5
     */
    @Test
    fun `plotScope write inside function is visible to caller (Req 5_5)`() = runBlocking {
        // Shared state to capture what the caller reads from plotScope after the call
        val callerSawPlotValue = AtomicReference<Any?>("NOT_SET")

        // Action inside the function that writes to plotScope
        val functionPlotWriteAction = object : IAction {
            override val nodeId = "function_plot_write"
            override val displayName = "Function Plot Write"
            override suspend fun execute(context: ExecutionContext) {
                context.plotScope.set("sharedKey", "from_function")
            }
        }

        // Action in the caller that reads from plotScope after the function returns
        val callerPlotReadAction = object : IAction {
            override val nodeId = "caller_plot_read"
            override val displayName = "Caller Plot Read"
            override suspend fun execute(context: ExecutionContext) {
                callerSawPlotValue.set(context.plotScope.get("sharedKey"))
            }
        }

        registry.register("plotWriteFn", functionScript("plotWriteFn", functionPlotWriteAction))

        // Caller: call the function, then read from plotScope
        val callerScript = script(
            callFunctionAction("plotWriteFn"),
            callerPlotReadAction
        )
        val plotId = UUID.randomUUID()

        // When
        engine.executeScript(callerScript, plotId, mockPlayer(), emptyMap())
        delay(200)

        // Then: the caller sees the value written by the function in plotScope
        assertEquals(
            "from_function",
            callerSawPlotValue.get(),
            "Caller should see plotScope value written inside the function. Saw: ${callerSawPlotValue.get()}"
        )
    }
}
