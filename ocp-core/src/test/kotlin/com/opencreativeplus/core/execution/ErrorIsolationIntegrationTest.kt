package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.core.watchdog.TPSMonitor
import com.opencreativeplus.core.watchdog.Watchdog
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/
 * Integration tests for error isolation between plots.
 *
 * Verifies that:
 * - When an Action_Node throws an exception, only that Code_Line is terminated ( 38.1)
 * - Scripts on other plots are not affected by a failure on one plot ( 38.1)
 * - Multiple concurrent scripts on the same plot are isolated from each other ( 38.1)
 * - Error count tracking per plot does not bleed across plots ( 38.4)
 *
 38.1, 38.2
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ErrorIsolationIntegrationTest {

    private lateinit var watchdog: Watchdog
    private lateinit var variableManager: VariableManager
    private lateinit var coroutineConfig: CoroutineConfiguration
    private lateinit var engine: ExecutionEngine

    @BeforeAll
    fun setup() {
        val tpsMonitor = mockk<TPSMonitor>()
        every { tpsMonitor.getCurrentTPS() } returns 20.0
        watchdog = Watchdog(tpsMonitor)

        val db = mockk<com.mongodb.kotlin.client.coroutine.MongoDatabase>(relaxed = true)
        variableManager = VariableManager(db)

        coroutineConfig = CoroutineConfiguration(syncRunner = { it() })
        engine = ExecutionEngine(watchdog, variableManager, coroutineConfig)
    }

    @AfterAll
    fun teardown() {
        coroutineConfig.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun mockEvent(type: String = "test"): IEvent {
        val e = mockk<IEvent>()
        every { e.nodeId } returns "event_$type"
        every { e.displayName } returns "Event $type"
        every { e.eventType } returns type
        return e
    }

    private fun script(vararg actions: IAction): CompiledScript =
        CompiledScript(event = mockEvent(), actions = actions.toList(), sourceLocation = "test@0,0,0")

    private fun completingAction(log: CopyOnWriteArrayList<String>, label: String, delayMs: Long = 0): IAction =
        object : IAction {
            override val nodeId = "completing_$label"
            override val displayName = "Completing $label"
            override suspend fun execute(context: ExecutionContext) {
                if (delayMs > 0) delay(delayMs)
                log.add(label)
            }
        }

    private fun throwingAction(message: String = "intentional error"): IAction =
        object : IAction {
            override val nodeId = "throwing"
            override val displayName = "Throwing"
            override suspend fun execute(context: ExecutionContext) {
                throw RuntimeException(message)
            }
        }

    private fun longRunningAction(latch: CountDownLatch, completedFlag: AtomicBoolean): IAction =
        object : IAction {
            override val nodeId = "long_running"
            override val displayName = "Long Running"
            override suspend fun execute(context: ExecutionContext) {
                withContext(Dispatchers.IO) { latch.await(5, TimeUnit.SECONDS) }
                completedFlag.set(true)
            }
        }

    // =========================================================================
    //  38.1 — Exception terminates only the affected Code_Line
    // =========================================================================

    @Test
    fun `exception in one script terminates only that Code_Line, not others on same plot`() = runBlocking {
        // Given: two scripts on the same plot — one throws, one succeeds
        val plotId = UUID.randomUUID()
        val log = CopyOnWriteArrayList<String>()

        val failingScript = script(throwingAction("code line error"))
        val successScript = script(completingAction(log, "success", delayMs = 50))

        // When: both scripts are launched on the same plot
        engine.executeScript(failingScript, plotId, null, emptyMap())
        engine.executeScript(successScript, plotId, null, emptyMap())

        delay(300)

        // Then: the successful Code_Line completed despite the other throwing
        assertTrue(log.contains("success"),
            "Successful Code_Line should complete even when another Code_Line throws")
    }

    @Test
    fun `exception in script on plot A does not affect scripts on plot B`() = runBlocking {
        // Given: a failing script on plotA and a succeeding script on plotB
        val plotA = UUID.randomUUID()
        val plotB = UUID.randomUUID()
        val log = CopyOnWriteArrayList<String>()

        // When
        engine.executeScript(script(throwingAction("plot A error")), plotA, null, emptyMap())
        engine.executeScript(script(completingAction(log, "plotB_done", delayMs = 50)), plotB, null, emptyMap())

        delay(300)

        // Then: plot B's script is unaffected
        assertTrue(log.contains("plotB_done"),
            "Script on plot B should not be affected by an exception on plot A")
    }

    @Test
    fun `exception mid-script stops only that script, not subsequent actions of other scripts`() = runBlocking {
        // Given: a script that throws after the first action, and a separate script that runs all actions
        val plotId = UUID.randomUUID()
        val log = CopyOnWriteArrayList<String>()

        val partialScript = script(
            completingAction(log, "partial_step1"),
            throwingAction("mid-script error"),
            completingAction(log, "partial_step3_should_not_run")
        )
        val fullScript = script(
            completingAction(log, "full_step1", delayMs = 20),
            completingAction(log, "full_step2", delayMs = 20),
            completingAction(log, "full_step3", delayMs = 20)
        )

        // When
        engine.executeScript(partialScript, plotId, null, emptyMap())
        engine.executeScript(fullScript, plotId, null, emptyMap())

        delay(400)

        // Then: partial script stopped at the throw, full script ran completely
        assertTrue(log.contains("partial_step1"), "Step before throw should have executed")
        assertFalse(log.contains("partial_step3_should_not_run"), "Step after throw should NOT execute")
        assertTrue(log.contains("full_step1"), "Full script step 1 should complete")
        assertTrue(log.contains("full_step2"), "Full script step 2 should complete")
        assertTrue(log.contains("full_step3"), "Full script step 3 should complete")
    }

    @Test
    fun `many concurrent scripts on same plot — one failure does not cascade`() = runBlocking {
        // Given: 5 scripts on the same plot, one of which throws
        val plotId = UUID.randomUUID()
        val completedCount = AtomicInteger(0)
        val scriptCount = 5

        engine.executeScript(script(throwingAction("one bad script")), plotId, null, emptyMap())

        repeat(scriptCount - 1) { i ->
            val action = object : IAction {
                override val nodeId = "good_$i"
                override val displayName = "Good $i"
                override suspend fun execute(context: ExecutionContext) {
                    delay(30)
                    completedCount.incrementAndGet()
                }
            }
            engine.executeScript(script(action), plotId, null, emptyMap())
        }

        delay(500)

        assertEquals(scriptCount - 1, completedCount.get(),
            "All ${scriptCount - 1} healthy scripts should complete despite one failure")
    }

    @Test
    fun `scripts on multiple plots all complete when one plot has a failing script`() = runBlocking {
        // Given: 3 plots, each with one script; plot 1 has a failing script
        val plots = List(3) { UUID.randomUUID() }
        val log = CopyOnWriteArrayList<String>()

        engine.executeScript(script(throwingAction("plot 0 failure")), plots[0], null, emptyMap())
        engine.executeScript(script(completingAction(log, "plot1_done", delayMs = 30)), plots[1], null, emptyMap())
        engine.executeScript(script(completingAction(log, "plot2_done", delayMs = 30)), plots[2], null, emptyMap())

        delay(300)

        assertTrue(log.contains("plot1_done"), "Plot 1 script should complete")
        assertTrue(log.contains("plot2_done"), "Plot 2 script should complete")
    }

    // =========================================================================
    // Local scope isolation — each Code_Line gets its own scope
    // =========================================================================

    @Test
    fun `local scope of failing script is cleared and does not leak to other scripts`() = runBlocking {
        // Given: a script that sets a local variable then throws
        val plotId = UUID.randomUUID()
        var failingScope: com.opencreativeplus.api.execution.VariableScope? = null
        var successScope: com.opencreativeplus.api.execution.VariableScope? = null

        val failingAction = object : IAction {
            override val nodeId = "fail_set"
            override val displayName = "Fail Set"
            override suspend fun execute(context: ExecutionContext) {
                context.localScope.set("secret", "leaked_value")
                failingScope = context.localScope
                throw RuntimeException("intentional")
            }
        }

        val successAction = object : IAction {
            override val nodeId = "success_check"
            override val displayName = "Success Check"
            override suspend fun execute(context: ExecutionContext) {
                delay(100) // run after the failing script
                successScope = context.localScope
            }
        }

        engine.executeScript(script(failingAction), plotId, null, emptyMap())
        engine.executeScript(script(successAction), plotId, null, emptyMap())

        delay(400)

        // Failing script's local scope was cleared in finally block
        assertFalse(failingScope?.has("secret") ?: true,
            "Failing script's local scope should be cleared after exception")

        // Successful script has its own independent scope
        assertFalse(successScope?.has("secret") ?: false,
            "Successful script should not see variables from the failing script's scope")
    }

    // =========================================================================
    // Plot scope isolation — plot scope is shared but not corrupted by errors
    // =========================================================================

    @Test
    fun `plot scope variable set before exception is preserved for other scripts`() = runBlocking {
        // Given: script A sets a plot-scope variable then throws; script B reads it
        val plotId = UUID.randomUUID()
        var readValue: Any? = null

        val writerThenThrow = object : IAction {
            override val nodeId = "writer_throw"
            override val displayName = "Writer Then Throw"
            override suspend fun execute(context: ExecutionContext) {
                context.plotScope.set("shared_key", "shared_value")
                throw RuntimeException("writer threw after setting")
            }
        }

        val reader = object : IAction {
            override val nodeId = "reader"
            override val displayName = "Reader"
            override suspend fun execute(context: ExecutionContext) {
                delay(100) // ensure writer ran first
                readValue = context.plotScope.get("shared_key")
            }
        }

        engine.executeScript(script(writerThenThrow), plotId, null, emptyMap())
        engine.executeScript(script(reader), plotId, null, emptyMap())

        delay(400)

        // Plot scope is shared — the value written before the throw is still accessible
        assertEquals("shared_value", readValue,
            "Plot scope value set before exception should be accessible to other scripts")
    }

    // =========================================================================
    // Cancellation does not affect other plots ( 38.4)
    // =========================================================================

    @Test
    fun `cancelAllExecutions on plot A does not cancel scripts on plot B`() = runBlocking {
        // Given: long-running scripts on two plots
        val plotA = UUID.randomUUID()
        val plotB = UUID.randomUUID()
        val latchA = CountDownLatch(1)
        val plotBCompleted = AtomicBoolean(false)

        engine.executeScript(script(longRunningAction(latchA, AtomicBoolean())), plotA, null, emptyMap())

        val plotBAction = object : IAction {
            override val nodeId = "plotB_action"
            override val displayName = "Plot B Action"
            override suspend fun execute(context: ExecutionContext) {
                delay(200)
                plotBCompleted.set(true)
            }
        }
        engine.executeScript(script(plotBAction), plotB, null, emptyMap())

        delay(50) // let both coroutines start

        // When: cancel all executions on plot A only
        engine.cancelAllExecutions(plotA)

        // Then: plot B's script continues and completes
        delay(400)
        assertTrue(plotBCompleted.get(),
            "Cancelling plot A should not affect plot B's scripts")

        latchA.countDown() // cleanup
    }

    @Test
    fun `executeScript does not throw when action throws — error is isolated`() = runBlocking {
        //  38.1: the engine catches exceptions per Code_Line
        val plotId = UUID.randomUUID()

        // When / Then: no exception propagates out of executeScript
        assertDoesNotThrow {
            runBlocking {
                engine.executeScript(script(throwingAction("isolated error")), plotId, null, emptyMap())
            }
        }

        delay(100) // let the coroutine run and fail internally
    }

    @Test
    fun `rapid successive failures on same plot do not degrade subsequent executions`() = runBlocking {
        // Given: 3 failing scripts followed by 1 successful script on the same plot
        val plotId = UUID.randomUUID()
        val successCompleted = AtomicBoolean(false)

        repeat(3) {
            engine.executeScript(script(throwingAction("rapid failure $it")), plotId, null, emptyMap())
        }

        val successAction = object : IAction {
            override val nodeId = "final_success"
            override val displayName = "Final Success"
            override suspend fun execute(context: ExecutionContext) {
                delay(50)
                successCompleted.set(true)
            }
        }
        engine.executeScript(script(successAction), plotId, null, emptyMap())

        delay(400)

        assertTrue(successCompleted.get(),
            "Successful script should run normally even after multiple prior failures on the same plot")
    }
}
