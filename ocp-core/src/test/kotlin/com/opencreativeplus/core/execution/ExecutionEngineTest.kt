package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.core.watchdog.Watchdog
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for ExecutionEngine.
 *
 * Covers:
 * - Coroutine suspension and resumption ( 6.2, 6.3)
 * - Concurrent script execution ( 6.4)
 * - Cancellation on player leave ( 6.5, 26.1)
 * - Error isolation between scripts ( 38.1)
 */
class ExecutionEngineTest {

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

    private lateinit var watchdog: Watchdog
    private lateinit var variableManager: VariableManager
    private lateinit var coroutineConfig: CoroutineConfiguration
    private lateinit var engine: ExecutionEngine

    @BeforeEach
    fun setup() {
        // Watchdog that always passes (healthy TPS, no memory pressure)
        val tpsMonitor = mockk<com.opencreativeplus.core.watchdog.TPSMonitor>()
        every { tpsMonitor.getCurrentTPS() } returns 20.0
        watchdog = Watchdog(tpsMonitor)

        // VariableManager backed by a no-op database mock
        val db = mockk<com.mongodb.kotlin.client.coroutine.MongoDatabase>(relaxed = true)
        variableManager = VariableManager(db)

        // CoroutineConfiguration using the test dispatcher so we control time
        coroutineConfig = CoroutineConfiguration(syncRunner = { it() })

        engine = ExecutionEngine(watchdog, variableManager, coroutineConfig)
    }

    @AfterEach
    fun teardown() {
        coroutineConfig.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun mockPlayer(id: UUID = UUID.randomUUID()): Player {
        val p = mockk<Player>(relaxed = true)
        every { p.uniqueId } returns id
        return p
    }

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

    /** An IAction that records its execution and optionally suspends. */
    private fun recordingAction(
        log: CopyOnWriteArrayList<String>,
        label: String,
        suspendMs: Long = 0L
    ): IAction = object : IAction {
        override val nodeId = "recording"
        override val displayName = "Recording"
        override suspend fun execute(context: ExecutionContext) {
            if (suspendMs > 0) delay(suspendMs)
            log.add(label)
        }
    }

    /** An IAction that always throws. */
    private fun throwingAction(message: String = "boom"): IAction = object : IAction {
        override val nodeId = "throwing"
        override val displayName = "Throwing"
        override suspend fun execute(context: ExecutionContext) {
            throw RuntimeException(message)
        }
    }

    /** An IAction that suspends until [latch] is counted down. */
    private fun latchAction(latch: CountDownLatch): IAction = object : IAction {
        override val nodeId = "latch"
        override val displayName = "Latch"
        override suspend fun execute(context: ExecutionContext) {
            withContext(Dispatchers.IO) { latch.await(5, TimeUnit.SECONDS) }
        }
    }

    // =========================================================================
    // 1. Coroutine suspension and resumption  (s 6.2, 6.3)
    // =========================================================================

    @Test
    fun `executeScript returns immediately while actions are still running`() = runBlocking {
        // Given: a script with a long-running action
        val started = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val action = object : IAction {
            override val nodeId = "slow"
            override val displayName = "Slow"
            override suspend fun execute(context: ExecutionContext) {
                started.set(true)
                delay(500)
                finished.set(true)
            }
        }
        val plotId = UUID.randomUUID()

        // When: executeScript is called
        engine.executeScript(script(action), plotId, null, emptyMap())

        // Then: the call returns before the action finishes (non-blocking launch)
        // The action may or may not have started yet, but it definitely hasn't finished
        assertFalse(finished.get(), "executeScript should return before the action completes")
    }

    @Test
    fun `Wait-style action suspends coroutine without blocking and resumes after delay`() = runBlocking {
        // Given: a script that records before and after a delay
        val log = CopyOnWriteArrayList<String>()
        val delayMs = 100L
        val action = object : IAction {
            override val nodeId = "wait"
            override val displayName = "Wait"
            override suspend fun execute(context: ExecutionContext) {
                log.add("before_wait")
                delay(delayMs)
                log.add("after_wait")
            }
        }
        val plotId = UUID.randomUUID()

        // When: script executes
        engine.executeScript(script(action), plotId, null, emptyMap())

        // Give the coroutine time to start and record "before_wait"
        delay(20)
        assertTrue(log.contains("before_wait"), "Action should have started")
        assertFalse(log.contains("after_wait"), "Action should still be suspended")

        // After the delay elapses, "after_wait" should appear
        delay(delayMs + 50)
        assertTrue(log.contains("after_wait"), "Action should have resumed after delay")
    }

    @Test
    fun `actions in a script execute in sequential order`() = runBlocking {
        // Given: three actions that record their labels
        val log = CopyOnWriteArrayList<String>()
        val plotId = UUID.randomUUID()

        // When
        engine.executeScript(
            script(
                recordingAction(log, "first"),
                recordingAction(log, "second"),
                recordingAction(log, "third")
            ),
            plotId, null, emptyMap()
        )

        // Allow coroutine to complete
        delay(100)

        // Then: order is preserved
        assertEquals(listOf("first", "second", "third"), log.toList())
    }

    @Test
    fun `local scope is cleared after script execution completes`() = runBlocking {
        // Given: an action that writes to local scope
        val plotId = UUID.randomUUID()
        var capturedScope: VariableScope? = null
        val action = object : IAction {
            override val nodeId = "scope_writer"
            override val displayName = "Scope Writer"
            override suspend fun execute(context: ExecutionContext) {
                context.localScope.set("key", "value")
                capturedScope = context.localScope
            }
        }

        // When
        engine.executeScript(script(action), plotId, null, emptyMap())
        delay(100)

        // Then: local scope was cleared after execution
        assertFalse(capturedScope?.has("key") ?: true, "Local scope should be cleared after execution")
    }

    // =========================================================================
    // 2. Concurrent script execution  ( 6.4)
    // =========================================================================

    @Test
    fun `multiple scripts for the same plot run concurrently`() = runBlocking {
        // Given: two scripts each with a blocking latch action
        val plotId = UUID.randomUUID()
        val latch1 = CountDownLatch(1)
        val latch2 = CountDownLatch(1)
        val started1 = AtomicBoolean(false)
        val started2 = AtomicBoolean(false)

        val action1 = object : IAction {
            override val nodeId = "a1"; override val displayName = "A1"
            override suspend fun execute(context: ExecutionContext) {
                started1.set(true)
                withContext(Dispatchers.IO) { latch1.await(3, TimeUnit.SECONDS) }
            }
        }
        val action2 = object : IAction {
            override val nodeId = "a2"; override val displayName = "A2"
            override suspend fun execute(context: ExecutionContext) {
                started2.set(true)
                withContext(Dispatchers.IO) { latch2.await(3, TimeUnit.SECONDS) }
            }
        }

        // When: both scripts are launched
        engine.executeScript(script(action1), plotId, null, emptyMap())
        engine.executeScript(script(action2), plotId, null, emptyMap())

        // Give both coroutines time to start
        delay(200)

        // Then: both scripts started concurrently (neither blocked the other)
        assertTrue(started1.get(), "Script 1 should have started")
        assertTrue(started2.get(), "Script 2 should have started concurrently")

        // Cleanup
        latch1.countDown()
        latch2.countDown()
    }

    @Test
    fun `scripts for different plots run independently`() = runBlocking {
        // Given: scripts on two different plots
        val plotId1 = UUID.randomUUID()
        val plotId2 = UUID.randomUUID()
        val log = CopyOnWriteArrayList<String>()

        // When
        engine.executeScript(script(recordingAction(log, "plot1")), plotId1, null, emptyMap())
        engine.executeScript(script(recordingAction(log, "plot2")), plotId2, null, emptyMap())

        delay(100)

        // Then: both scripts executed
        assertTrue(log.contains("plot1"))
        assertTrue(log.contains("plot2"))
    }

    @Test
    fun `many concurrent scripts all complete successfully`() = runBlocking {
        // Given: 10 scripts on the same plot
        val plotId = UUID.randomUUID()
        val completedCount = AtomicInteger(0)
        val scriptCount = 10

        // When
        repeat(scriptCount) { i ->
            val action = object : IAction {
                override val nodeId = "a$i"; override val displayName = "A$i"
                override suspend fun execute(context: ExecutionContext) {
                    delay(10) // small delay to ensure concurrency
                    completedCount.incrementAndGet()
                }
            }
            engine.executeScript(script(action), plotId, null, emptyMap())
        }

        // Allow all coroutines to finish
        delay(500)

        // Then: all scripts completed
        assertEquals(scriptCount, completedCount.get(), "All $scriptCount scripts should complete")
    }

    // =========================================================================
    // 3. Cancellation on player leave  (s 6.5, 26.1)
    // =========================================================================

    @Test
    fun `cancelPlayerExecutions cancels jobs associated with that player`() = runBlocking {
        // Given: a player with a long-running script
        val plotId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        val player = mockPlayer(playerId)
        val wasCancelled = AtomicBoolean(false)

        val action = object : IAction {
            override val nodeId = "long"; override val displayName = "Long"
            override suspend fun execute(context: ExecutionContext) {
                try {
                    delay(10_000) // effectively infinite
                } catch (e: CancellationException) {
                    wasCancelled.set(true)
                    throw e
                }
            }
        }

        engine.executeScript(script(action), plotId, player, emptyMap())
        delay(50) // let the coroutine start

        // When: player leaves
        engine.cancelPlayerExecutions(plotId, playerId)
        delay(100)

        // Then: the coroutine was cancelled
        assertTrue(wasCancelled.get(), "Player's script should be cancelled on leave")
    }

    @Test
    fun `cancelPlayerExecutions does not cancel scripts of other players`() = runBlocking {
        // Given: two players with scripts on the same plot
        val plotId = UUID.randomUUID()
        val player1Id = UUID.randomUUID()
        val player2Id = UUID.randomUUID()
        val player1 = mockPlayer(player1Id)
        val player2 = mockPlayer(player2Id)
        val player2Completed = AtomicBoolean(false)

        val longAction = object : IAction {
            override val nodeId = "long"; override val displayName = "Long"
            override suspend fun execute(context: ExecutionContext) { delay(10_000) }
        }
        val shortAction = object : IAction {
            override val nodeId = "short"; override val displayName = "Short"
            override suspend fun execute(context: ExecutionContext) {
                delay(200)
                player2Completed.set(true)
            }
        }

        engine.executeScript(script(longAction), plotId, player1, emptyMap())
        engine.executeScript(script(shortAction), plotId, player2, emptyMap())
        delay(50)

        // When: only player1 leaves
        engine.cancelPlayerExecutions(plotId, player1Id)

        // Then: player2's script still runs to completion
        delay(400)
        assertTrue(player2Completed.get(), "Player 2's script should not be affected by player 1 leaving")
    }

    @Test
    fun `cancelAllExecutions cancels all scripts on a plot`() = runBlocking {
        // Given: multiple scripts on a plot
        val plotId = UUID.randomUUID()
        val cancelledCount = AtomicInteger(0)
        val scriptCount = 3

        repeat(scriptCount) {
            val action = object : IAction {
                override val nodeId = "long$it"; override val displayName = "Long$it"
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

        delay(50) // let all coroutines start

        // When: all executions are cancelled (e.g., plot leaves PLAY mode)
        engine.cancelAllExecutions(plotId)
        delay(100)

        // Then: all scripts were cancelled
        assertEquals(scriptCount, cancelledCount.get(), "All scripts on the plot should be cancelled")
    }

    @Test
    fun `cancelAllExecutions on unknown plotId does not throw`() {
        // Given: a plot with no active executions
        val unknownPlotId = UUID.randomUUID()

        // When / Then: no exception
        engine.cancelAllExecutions(unknownPlotId)
    }

    @Test
    fun `cancelPlayerExecutions on unknown player does not throw`() {
        // Given: a plot and player with no active executions
        val plotId = UUID.randomUUID()
        val unknownPlayerId = UUID.randomUUID()

        // When / Then: no exception
        engine.cancelPlayerExecutions(plotId, unknownPlayerId)
    }

    // =========================================================================
    // 4. Error isolation between scripts  ( 38.1)
    // =========================================================================

    @Test
    fun `exception in one script does not prevent other scripts from completing`() = runBlocking {
        // Given: a failing script and a succeeding script on the same plot
        val plotId = UUID.randomUUID()
        val successCompleted = AtomicBoolean(false)

        val failingScript = script(throwingAction("intentional failure"))
        val successAction = object : IAction {
            override val nodeId = "success"; override val displayName = "Success"
            override suspend fun execute(context: ExecutionContext) {
                delay(50)
                successCompleted.set(true)
            }
        }
        val successScript = script(successAction)

        // When: both scripts are launched
        engine.executeScript(failingScript, plotId, null, emptyMap())
        engine.executeScript(successScript, plotId, null, emptyMap())

        delay(300)

        // Then: the successful script completed despite the other failing
        assertTrue(successCompleted.get(), "Successful script should complete even when another script throws")
    }

    @Test
    fun `exception in script does not propagate to caller of executeScript`() = runBlocking {
        // Given: a script that throws immediately
        val plotId = UUID.randomUUID()

        // When / Then: executeScript itself should not throw
        engine.executeScript(script(throwingAction()), plotId, null, emptyMap())
        delay(100) // let the coroutine run and fail
        // If we reach here without exception, the test passes
    }

    @Test
    fun `local scope is cleared even when script throws`() = runBlocking {
        // Given: an action that sets a local variable then throws
        val plotId = UUID.randomUUID()
        var capturedScope: VariableScope? = null
        val action = object : IAction {
            override val nodeId = "fail_after_set"; override val displayName = "Fail After Set"
            override suspend fun execute(context: ExecutionContext) {
                context.localScope.set("key", "value")
                capturedScope = context.localScope
                throw RuntimeException("intentional")
            }
        }

        engine.executeScript(script(action), plotId, null, emptyMap())
        delay(100)

        // Then: local scope was cleared in the finally block
        assertFalse(capturedScope?.has("key") ?: true, "Local scope should be cleared even after exception")
    }

    @Test
    fun `watchdog exception terminates only the affected script`() = runBlocking {
        // Given: a watchdog that will reject the first script's context
        val tpsMonitor = mockk<com.opencreativeplus.core.watchdog.TPSMonitor>()
        every { tpsMonitor.getCurrentTPS() } returns 20.0
        val strictWatchdog = Watchdog(tpsMonitor)

        val db = mockk<com.mongodb.kotlin.client.coroutine.MongoDatabase>(relaxed = true)
        val vm = VariableManager(db)
        val cfg = CoroutineConfiguration(syncRunner = { it() })
        val strictEngine = ExecutionEngine(strictWatchdog, vm, cfg)

        val plotId = UUID.randomUUID()
        val secondCompleted = AtomicBoolean(false)

        // First script: exceeds operation limit immediately
        val overLimitAction = object : IAction {
            override val nodeId = "over"; override val displayName = "Over"
            override suspend fun execute(context: ExecutionContext) {
                // Manually push the counter over the limit
                repeat(Watchdog.MAX_OPERATIONS + 1) { context.operationCount.incrementAndGet() }
            }
        }

        // Second script: normal
        val normalAction = object : IAction {
            override val nodeId = "normal"; override val displayName = "Normal"
            override suspend fun execute(context: ExecutionContext) {
                delay(50)
                secondCompleted.set(true)
            }
        }

        strictEngine.executeScript(script(overLimitAction), plotId, null, emptyMap())
        strictEngine.executeScript(script(normalAction), plotId, null, emptyMap())

        delay(300)

        assertTrue(secondCompleted.get(), "Second script should complete even when first hits watchdog limit")
        cfg.close()
    }

    @Test
    fun `multiple scripts on different plots are isolated from each other`() = runBlocking {
        // Given: a failing script on plot1 and a succeeding script on plot2
        val plotId1 = UUID.randomUUID()
        val plotId2 = UUID.randomUUID()
        val plot2Completed = AtomicBoolean(false)

        val plot2Action = object : IAction {
            override val nodeId = "p2"; override val displayName = "P2"
            override suspend fun execute(context: ExecutionContext) {
                delay(50)
                plot2Completed.set(true)
            }
        }

        engine.executeScript(script(throwingAction()), plotId1, null, emptyMap())
        engine.executeScript(script(plot2Action), plotId2, null, emptyMap())

        delay(300)

        assertTrue(plot2Completed.get(), "Script on plot2 should not be affected by failure on plot1")
    }
}
