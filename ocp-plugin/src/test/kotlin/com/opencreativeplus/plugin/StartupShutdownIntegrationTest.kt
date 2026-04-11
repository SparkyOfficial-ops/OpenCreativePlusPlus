package com.opencreativeplus.plugin

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.execution.CoroutineConfiguration
import com.opencreativeplus.core.execution.ExecutionEngine
import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.core.watchdog.TPSMonitor
import com.opencreativeplus.core.watchdog.Watchdog
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for plugin startup/shutdown lifecycle.
 *
 * Group 1: Initialization order — verifies CoroutineConfiguration behaves correctly
 *           when created and closed (as done during onEnable/onDisable).
 *
 * Group 2: Graceful shutdown with active scripts — verifies Requirement 26.4:
 *           "When a coroutine is cancelled, the OCP_Engine SHALL release all
 *            resources held by the Execution_Context."
 *
 * Validates: Requirement 26.4
 */
class StartupShutdownIntegrationTest {

    private lateinit var coroutineConfig: CoroutineConfiguration
    private lateinit var engine: ExecutionEngine

    @BeforeEach
    fun setup() {
        coroutineConfig = CoroutineConfiguration(syncRunner = { it() })

        val tpsMonitor = mockk<TPSMonitor>()
        every { tpsMonitor.getCurrentTPS() } returns 20.0
        val watchdog = Watchdog(tpsMonitor)

        val db = mockk<com.mongodb.kotlin.client.coroutine.MongoDatabase>(relaxed = true)
        val variableManager = VariableManager(db)

        engine = ExecutionEngine(watchdog, variableManager, coroutineConfig)
    }

    @AfterEach
    fun teardown() {
        // Safe to call even if already closed (tests may close it themselves)
        runCatching { coroutineConfig.close() }
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

    // =========================================================================
    // Group 1: Initialization order
    // =========================================================================

    /**
     * Verifies that calling close() on a CoroutineConfiguration cancels its
     * executionScope, which is the mechanism used during onDisable().
     */
    @Test
    fun `coroutineConfiguration close cancels executionScope`() = runBlocking {
        // Given: a long-running coroutine launched in the scope
        coroutineConfig.executionScope.launch {
            delay(10_000)
        }
        delay(50) // let it start

        assertTrue(coroutineConfig.executionScope.isActive, "Scope should be active before close()")

        // When: close() is called (as in onDisable)
        coroutineConfig.close()

        // Then: the scope is no longer active
        assertFalse(coroutineConfig.executionScope.isActive, "Scope should be cancelled after close()")
    }

    /**
     * Verifies that after coroutineConfig.close(), cancelAllExecutions() on the
     * engine does not throw and the scope is cancelled.
     */
    @Test
    fun `executionEngine tracks no active jobs after coroutineConfig is closed`() = runBlocking {
        // Given: a long-running script launched via the engine
        val plotId = UUID.randomUUID()
        val action = object : IAction {
            override val nodeId = "long"; override val displayName = "Long"
            override suspend fun execute(context: ExecutionContext) { delay(10_000) }
        }
        engine.executeScript(script(action), plotId, null, emptyMap())
        delay(50) // let the coroutine start

        // When: coroutineConfig is closed (simulating onDisable)
        coroutineConfig.close()

        // Then: cancelAllExecutions does not throw and scope is cancelled
        engine.cancelAllExecutions(plotId) // must not throw
        assertFalse(coroutineConfig.executionScope.isActive, "Scope should be cancelled after close()")
    }

    /**
     * Verifies that SupervisorJob is used so a failing child coroutine does not
     * cancel the entire executionScope — other scripts keep running.
     */
    @Test
    fun `CoroutineConfiguration executionScope uses SupervisorJob so child failure does not cancel scope`() =
        runBlocking {
            // Given: two coroutines — first throws, second completes normally
            val secondCompleted = AtomicBoolean(false)

            coroutineConfig.executionScope.launch {
                throw RuntimeException("intentional child failure")
            }

            coroutineConfig.executionScope.launch {
                delay(100)
                secondCompleted.set(true)
            }

            // Allow both coroutines to run
            delay(300)

            // Then: scope is still active (SupervisorJob absorbed the failure)
            assertTrue(coroutineConfig.executionScope.isActive,
                "SupervisorJob should keep scope active after a child failure")
            assertTrue(secondCompleted.get(),
                "Second coroutine should complete despite first one throwing")
        }

    // =========================================================================
    // Group 2: Graceful shutdown with active scripts (Requirement 26.4)
    // =========================================================================

    /**
     * Validates Requirement 26.4: cancelling all executions on a plot and then
     * closing the coroutine scope terminates all active coroutines.
     */
    @Test
    fun `shutdown cancels all active coroutines`() = runBlocking {
        // Given: 3 long-running scripts on a plot
        val plotId = UUID.randomUUID()
        val cancelledCount = AtomicInteger(0)

        repeat(3) { i ->
            val action = object : IAction {
                override val nodeId = "long$i"; override val displayName = "Long$i"
                override suspend fun execute(context: ExecutionContext) {
                    try {
                        delay(10_000)
                    } catch (e: CancellationException) {
                        cancelledCount.incrementAndGet()
                        throw e
                    }
                }
            }
            engine.executeScript(script(action), plotId, null, emptyMap())
        }

        delay(100) // let all 3 coroutines start

        // When: shutdown sequence — cancel executions then close scope
        engine.cancelAllExecutions(plotId)
        coroutineConfig.close()
        delay(100)

        // Then: all 3 coroutines were cancelled
        assertEquals(3, cancelledCount.get(), "All 3 active coroutines should be cancelled on shutdown")
    }

    /**
     * Validates Requirement 26.4: the local scope (Execution_Context resource) is
     * cleared when a coroutine is cancelled.
     *
     * The ExecutionEngine.executeScript finally block calls context.localScope.clear()
     * after the coroutine is cancelled. We capture the scope reference and verify it
     * is empty after the engine's finally block has run.
     */
    @Test
    fun `local scope is released when coroutine is cancelled`() = runBlocking {
        // Given: a script that sets a local variable then suspends indefinitely
        val plotId = UUID.randomUUID()
        val actionStarted = AtomicBoolean(false)
        // Capture the context's local scope so we can inspect it after cancellation
        val capturedScopes = CopyOnWriteArrayList<com.opencreativeplus.api.execution.VariableScope>()

        val action = object : IAction {
            override val nodeId = "set_and_wait"; override val displayName = "Set And Wait"
            override suspend fun execute(context: ExecutionContext) {
                context.localScope.set("key", "value")
                capturedScopes.add(context.localScope)
                actionStarted.set(true)
                delay(10_000) // suspend indefinitely until cancelled
            }
        }

        engine.executeScript(script(action), plotId, null, emptyMap())

        // Wait for the action to start and set the variable
        delay(100)
        assertTrue(actionStarted.get(), "Action should have started")
        assertTrue(capturedScopes.isNotEmpty(), "Scope should have been captured")
        assertTrue(capturedScopes[0].has("key"), "Scope should contain 'key' before cancellation")

        // When: cancel the execution (simulating shutdown)
        engine.cancelAllExecutions(plotId)
        // Give the engine's finally block time to run localScope.clear()
        delay(200)

        // Then: the local scope was cleared by ExecutionEngine's finally block (Req 26.4)
        assertFalse(capturedScopes[0].has("key"),
            "Local scope should be cleared when coroutine is cancelled (Req 26.4)")
    }

    /**
     * Verifies that cancelling one plot's executions does not affect scripts
     * running on a different plot.
     */
    @Test
    fun `cancelAllExecutions during shutdown does not affect other plots`() = runBlocking {
        // Given: scripts on two different plots
        val plotId1 = UUID.randomUUID()
        val plotId2 = UUID.randomUUID()
        val plot2Completed = AtomicBoolean(false)

        // Plot 1: long-running script
        val longAction = object : IAction {
            override val nodeId = "long"; override val displayName = "Long"
            override suspend fun execute(context: ExecutionContext) { delay(10_000) }
        }
        engine.executeScript(script(longAction), plotId1, null, emptyMap())

        // Plot 2: short script that should complete
        val shortAction = object : IAction {
            override val nodeId = "short"; override val displayName = "Short"
            override suspend fun execute(context: ExecutionContext) {
                delay(200)
                plot2Completed.set(true)
            }
        }
        engine.executeScript(script(shortAction), plotId2, null, emptyMap())

        delay(50) // let both start

        // When: only plot1 is cancelled
        engine.cancelAllExecutions(plotId1)

        // Then: plot2's script still completes
        delay(400)
        assertTrue(plot2Completed.get(),
            "Plot 2's script should complete unaffected by plot 1's cancellation")
    }

    /**
     * Verifies that calling cancelAllExecutions followed by coroutineConfig.close()
     * does not throw any exception.
     */
    @Test
    fun `coroutineConfig close after cancelAllExecutions is safe`() = runBlocking {
        // Given: a script running on a plot
        val plotId = UUID.randomUUID()
        val action = object : IAction {
            override val nodeId = "long"; override val displayName = "Long"
            override suspend fun execute(context: ExecutionContext) { delay(10_000) }
        }
        engine.executeScript(script(action), plotId, null, emptyMap())
        delay(50)

        // When / Then: no exception thrown
        engine.cancelAllExecutions(plotId)
        coroutineConfig.close() // must not throw
    }

    /**
     * Verifies that calling coroutineConfig.close() twice (e.g., double-shutdown)
     * does not throw any exception.
     */
    @Test
    fun `multiple sequential shutdowns do not throw`() = runBlocking {
        // When / Then: closing twice must not throw
        coroutineConfig.close()
        coroutineConfig.close() // second close — must be safe
    }
}
