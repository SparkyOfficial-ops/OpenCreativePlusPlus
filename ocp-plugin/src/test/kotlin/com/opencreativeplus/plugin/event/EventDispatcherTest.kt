package com.opencreativeplus.plugin.event

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.execution.ExecutionEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun makeEvent(type: String): IEvent = object : IEvent {
    override val nodeId = type
    override val displayName = type
    override val eventType = type
}

private fun makeAction(): IAction = object : IAction {
    override val nodeId = "noop"
    override val displayName = "Noop"
    override suspend fun execute(context: ExecutionContext) {}
}

private fun makeScript(eventType: String, location: String = "test@0,0,0"): CompiledScript =
    CompiledScript(event = makeEvent(eventType), actions = listOf(makeAction()), sourceLocation = location)

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
class EventDispatcherTest {

    // -----------------------------------------------------------------------
    // Script indexing by event type (req 16.1, 16.2)
    // -----------------------------------------------------------------------

    @Test
    fun `registerScripts indexes scripts by event type`() = runTest {
        val engine = mockk<ExecutionEngine>(relaxed = true)
        val dispatcher = EventDispatcher(engine, this)

        val plotId = UUID.randomUUID()
        val scripts = listOf(
            makeScript("player_join"),
            makeScript("player_interact"),
            makeScript("player_join")   // second join script
        )

        dispatcher.registerScripts(plotId, scripts)

        // Dispatch join — should trigger 2 scripts
        dispatcher.dispatchEvent(plotId, "player_join", emptyMap(), null)
        advanceUntilIdle()

        coVerify(exactly = 2) { engine.executeScript(any(), plotId, null, any()) }
    }

    @Test
    fun `dispatchEvent does nothing when no scripts registered for plot`() = runTest {
        val engine = mockk<ExecutionEngine>(relaxed = true)
        val dispatcher = EventDispatcher(engine, this)

        dispatcher.dispatchEvent(UUID.randomUUID(), "player_join", emptyMap(), null)
        advanceUntilIdle()

        coVerify(exactly = 0) { engine.executeScript(any(), any(), any(), any()) }
    }

    @Test
    fun `dispatchEvent does nothing when event type has no matching scripts`() = runTest {
        val engine = mockk<ExecutionEngine>(relaxed = true)
        val dispatcher = EventDispatcher(engine, this)

        val plotId = UUID.randomUUID()
        dispatcher.registerScripts(plotId, listOf(makeScript("player_join")))

        dispatcher.dispatchEvent(plotId, "player_interact", emptyMap(), null)
        advanceUntilIdle()

        coVerify(exactly = 0) { engine.executeScript(any(), any(), any(), any()) }
    }

    @Test
    fun `registerScripts replaces previously registered scripts for the same plot`() = runTest {
        val engine = mockk<ExecutionEngine>(relaxed = true)
        val dispatcher = EventDispatcher(engine, this)

        val plotId = UUID.randomUUID()
        dispatcher.registerScripts(plotId, listOf(makeScript("player_join"), makeScript("player_join")))
        // Replace with a single script
        dispatcher.registerScripts(plotId, listOf(makeScript("player_join")))

        dispatcher.dispatchEvent(plotId, "player_join", emptyMap(), null)
        advanceUntilIdle()

        coVerify(exactly = 1) { engine.executeScript(any(), plotId, null, any()) }
    }

    @Test
    fun `unregisterScripts removes all scripts for a plot`() = runTest {
        val engine = mockk<ExecutionEngine>(relaxed = true)
        val dispatcher = EventDispatcher(engine, this)

        val plotId = UUID.randomUUID()
        dispatcher.registerScripts(plotId, listOf(makeScript("player_join")))
        dispatcher.unregisterScripts(plotId)

        dispatcher.dispatchEvent(plotId, "player_join", emptyMap(), null)
        advanceUntilIdle()

        coVerify(exactly = 0) { engine.executeScript(any(), any(), any(), any()) }
    }

    @Test
    fun `scripts for different plots are indexed independently`() = runTest {
        val engine = mockk<ExecutionEngine>(relaxed = true)
        val dispatcher = EventDispatcher(engine, this)

        val plotA = UUID.randomUUID()
        val plotB = UUID.randomUUID()
        dispatcher.registerScripts(plotA, listOf(makeScript("player_join")))
        dispatcher.registerScripts(plotB, listOf(makeScript("player_join"), makeScript("player_join")))

        dispatcher.dispatchEvent(plotA, "player_join", emptyMap(), null)
        advanceUntilIdle()

        coVerify(exactly = 1) { engine.executeScript(any(), plotA, null, any()) }
        coVerify(exactly = 0) { engine.executeScript(any(), plotB, null, any()) }
    }

    // -----------------------------------------------------------------------
    // Concurrent event dispatching (req 16.3)
    // -----------------------------------------------------------------------

    @Test
    fun `each matching script is launched in its own coroutine`() = runTest {
        val launchCount = AtomicInteger(0)
        val engine = mockk<ExecutionEngine>()
        coEvery { engine.executeScript(any(), any(), any(), any()) } answers {
            launchCount.incrementAndGet()
        }

        val dispatcher = EventDispatcher(engine, this)
        val plotId = UUID.randomUUID()
        dispatcher.registerScripts(plotId, listOf(
            makeScript("player_join"),
            makeScript("player_join"),
            makeScript("player_join")
        ))

        dispatcher.dispatchEvent(plotId, "player_join", emptyMap(), null)
        advanceUntilIdle()

        assertEquals(3, launchCount.get(), "Expected 3 separate coroutine launches")
    }

    @Test
    fun `dispatchEvent passes event data and player to execution engine`() = runTest {
        val engine = mockk<ExecutionEngine>(relaxed = true)
        val dispatcher = EventDispatcher(engine, this)

        val plotId = UUID.randomUUID()
        val player = mockk<Player>()
        val eventData = mapOf("key" to "value")
        dispatcher.registerScripts(plotId, listOf(makeScript("player_join")))

        dispatcher.dispatchEvent(plotId, "player_join", eventData, player)
        advanceUntilIdle()

        coVerify { engine.executeScript(any(), plotId, player, eventData) }
    }

    @Test
    fun `multiple plots can dispatch events concurrently without interference`() = runTest {
        val engine = mockk<ExecutionEngine>(relaxed = true)
        val dispatcher = EventDispatcher(engine, this)

        val plots = List(5) { UUID.randomUUID() }
        plots.forEach { plotId ->
            dispatcher.registerScripts(plotId, listOf(makeScript("player_join")))
        }

        plots.forEach { plotId ->
            dispatcher.dispatchEvent(plotId, "player_join", emptyMap(), null)
        }
        advanceUntilIdle()

        // Each plot should have had exactly 1 script executed
        plots.forEach { plotId ->
            coVerify(exactly = 1) { engine.executeScript(any(), plotId, null, any()) }
        }
    }

    // -----------------------------------------------------------------------
    // Error isolation between handlers (req 16.5)
    // -----------------------------------------------------------------------

    @Test
    fun `exception in one script does not prevent other scripts from executing`() = runTest {
        val executedCount = AtomicInteger(0)
        val engine = mockk<ExecutionEngine>()

        var callCount = 0
        coEvery { engine.executeScript(any(), any(), any(), any()) } answers {
            callCount++
            if (callCount == 1) throw RuntimeException("Script 1 failed")
            executedCount.incrementAndGet()
        }

        val dispatcher = EventDispatcher(engine, this)
        val plotId = UUID.randomUUID()
        dispatcher.registerScripts(plotId, listOf(
            makeScript("player_join"),
            makeScript("player_join"),
            makeScript("player_join")
        ))

        dispatcher.dispatchEvent(plotId, "player_join", emptyMap(), null)
        advanceUntilIdle()

        assertEquals(2, executedCount.get(), "Expected 2 scripts to run despite 1 failure")
    }

    @Test
    fun `exception in handler does not propagate to caller`() = runTest {
        val engine = mockk<ExecutionEngine>()
        coEvery { engine.executeScript(any(), any(), any(), any()) } throws RuntimeException("boom")

        val dispatcher = EventDispatcher(engine, this)
        val plotId = UUID.randomUUID()
        dispatcher.registerScripts(plotId, listOf(makeScript("player_join")))

        // Should not throw
        dispatcher.dispatchEvent(plotId, "player_join", emptyMap(), null)
        advanceUntilIdle()
    }

    @Test
    fun `all scripts execute even when every handler throws`() = runTest {
        val callCount = AtomicInteger(0)
        val engine = mockk<ExecutionEngine>()
        coEvery { engine.executeScript(any(), any(), any(), any()) } answers {
            callCount.incrementAndGet()
            throw RuntimeException("always fails")
        }

        val dispatcher = EventDispatcher(engine, this)
        val plotId = UUID.randomUUID()
        dispatcher.registerScripts(plotId, listOf(
            makeScript("player_join"),
            makeScript("player_join")
        ))

        dispatcher.dispatchEvent(plotId, "player_join", emptyMap(), null)
        advanceUntilIdle()

        assertEquals(2, callCount.get(), "Both scripts should have been attempted despite failures")
    }
}
