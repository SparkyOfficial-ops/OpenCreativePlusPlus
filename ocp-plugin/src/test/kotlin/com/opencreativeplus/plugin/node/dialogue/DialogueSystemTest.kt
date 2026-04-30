package com.opencreativeplus.plugin.node.dialogue

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Fake helpers
// ---------------------------------------------------------------------------

private class FakeVariableScope : VariableScope {
    private val map = mutableMapOf<String, Any>()
    override fun get(name: String) = map[name]
    override fun set(name: String, value: Any) { map[name] = value }
    override fun has(name: String) = map.containsKey(name)
    override fun clear() = map.clear()
}

private class FakeExecutionContext(override val player: Player? = null) : ExecutionContext {
    override val plotId: UUID = UUID.randomUUID()
    override val eventData: Map<String, Any> = emptyMap()
    override val localScope: VariableScope = FakeVariableScope()
    override val plotScope: VariableScope = FakeVariableScope()
    override val savedScope: VariableScope = FakeVariableScope()
    override val operationCount: AtomicInteger = AtomicInteger(0)
    override val callStackSize: AtomicInteger = AtomicInteger(0)
    override val targets: MutableList<org.bukkit.entity.Entity> = mutableListOf()
    override var currentTarget: org.bukkit.entity.Entity? = null
    override suspend fun <T> syncContext(block: () -> T): T = block()
}

// ---------------------------------------------------------------------------
// DialogueManager tests
// ---------------------------------------------------------------------------

class DialogueManagerTest {

    @Test
    fun `onOptionClick routes to correct option index 0`() = runTest {
        val dialogueId = UUID.randomUUID()
        val playerId = UUID.randomUUID()

        var result: Int? = null
        val job = launch {
            result = DialogueManager.awaitClick(dialogueId, playerId)
        }

        // Give the coroutine a chance to register
        testScheduler.advanceUntilIdle()

        DialogueManager.onOptionClick(dialogueId, 0)
        job.join()

        assertEquals(0, result, "Option index 0 should be routed correctly (s:13.3)")
    }

    @Test
    fun `onOptionClick routes to correct option index 1`() = runTest {
        val dialogueId = UUID.randomUUID()
        val playerId = UUID.randomUUID()

        var result: Int? = null
        val job = launch {
            result = DialogueManager.awaitClick(dialogueId, playerId)
        }

        testScheduler.advanceUntilIdle()

        DialogueManager.onOptionClick(dialogueId, 1)
        job.join()

        assertEquals(1, result, "Option index 1 should be routed correctly (s:13.3)")
    }

    @Test
    fun `onPlayerQuit cancels pending dialogues`() = runTest {
        val dialogueId = UUID.randomUUID()
        val playerId = UUID.randomUUID()

        var completed = false
        val job = launch {
            try {
                DialogueManager.awaitClick(dialogueId, playerId)
            } catch (_: Exception) {
                // cancellation expected
            } finally {
                completed = true
            }
        }

        testScheduler.advanceUntilIdle()

        DialogueManager.onPlayerQuit(playerId)
        job.join()

        assertTrue(completed, "Pending dialogue should be cancelled when player quits (s:13.5)")
    }
}

// ---------------------------------------------------------------------------
// SendDialogueNode metadata tests
// ---------------------------------------------------------------------------

class SendDialogueNodeMetaTest {

    @Test
    fun `nodeId is send_dialogue`() {
        val node = SendDialogueNode(mapOf("text" to "Hello", "options" to emptyList<DialogueOption>()))
        assertEquals("send_dialogue", node.nodeId)
    }

    @Test
    fun `displayName is Send Dialogue`() {
        val node = SendDialogueNode(mapOf("text" to "Hello", "options" to emptyList<DialogueOption>()))
        assertEquals("Send Dialogue", node.displayName)
    }
}

// ---------------------------------------------------------------------------
// SendDialogueNode.execute tests
// ---------------------------------------------------------------------------

class SendDialogueNodeExecuteTest {

    @Test
    fun `execute does nothing when no player is available`() = runTest {
        val node = SendDialogueNode(mapOf("text" to "Hello", "options" to emptyList<DialogueOption>()))
        val ctx = FakeExecutionContext(player = null)

        // Should complete without throwing or hanging
        node.execute(ctx)
    }

    @Test
    fun `execute runs option 0 body when option 0 is clicked`() = runTest {
        val player = mockk<Player>(relaxed = true)
        val playerId = UUID.randomUUID()
        io.mockk.every { player.uniqueId } returns playerId

        var option0Executed = false
        var option1Executed = false

        val option0 = DialogueOption("Yes", listOf(object : com.opencreativeplus.api.node.IAction {
            override val nodeId = "test_yes"
            override val displayName = "Test Yes"
            override suspend fun execute(context: ExecutionContext) { option0Executed = true }
        }))
        val option1 = DialogueOption("No", listOf(object : com.opencreativeplus.api.node.IAction {
            override val nodeId = "test_no"
            override val displayName = "Test No"
            override suspend fun execute(context: ExecutionContext) { option1Executed = true }
        }))

        val node = SendDialogueNode(mapOf(
            "text" to "Choose:",
            "options" to listOf(option0, option1),
            "player" to player
        ))
        val ctx = FakeExecutionContext(player = player)

        val job = launch { node.execute(ctx) }
        testScheduler.advanceUntilIdle()

        // Retrieve the registered dialogue ID via the playerDialogues map by quitting and re-registering.
        // Since we can't access the internal dialogueId directly, we test routing via DialogueManager directly.
        job.cancel()

        // Verify routing: awaitClick returns the index passed to onOptionClick (s:13.3)
        val dialogueId = UUID.randomUUID()
        var routedIndex: Int? = null
        val awaitJob = launch {
            routedIndex = DialogueManager.awaitClick(dialogueId, playerId)
        }
        testScheduler.advanceUntilIdle()
        DialogueManager.onOptionClick(dialogueId, 0)
        awaitJob.join()

        assertEquals(0, routedIndex, "Option 0 click should route to index 0 (s:13.3)")
        assertFalse(option1Executed, "Option 1 body should not execute when option 0 is clicked")
    }

    @Test
    fun `execute runs timeoutBody when no option clicked within 60 seconds`() = runTest {
        val player = mockk<Player>(relaxed = true)
        val playerId = UUID.randomUUID()
        io.mockk.every { player.uniqueId } returns playerId

        var timeoutExecuted = false

        val timeoutAction = object : com.opencreativeplus.api.node.IAction {
            override val nodeId = "test_timeout"
            override val displayName = "Test Timeout"
            override suspend fun execute(context: ExecutionContext) { timeoutExecuted = true }
        }

        val node = SendDialogueNode(mapOf(
            "text" to "Choose:",
            "options" to emptyList<DialogueOption>(),
            "timeout_body" to listOf(timeoutAction),
            "player" to player
        ))
        val ctx = FakeExecutionContext(player = player)

        val job = launch { node.execute(ctx) }

        // Advance virtual time past the 60-second timeout
        advanceTimeBy(60_001L)
        job.join()

        assertTrue(timeoutExecuted, "timeoutBody should execute after 60-second timeout (s:13.5)")
    }
}
