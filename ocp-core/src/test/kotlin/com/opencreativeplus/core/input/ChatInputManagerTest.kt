@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.opencreativeplus.core.input

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.bukkit.entity.Player
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/
 * Unit tests for [ChatInputManager].
 *
 * Requirements: 1.7, 1.8, 3.5
 */
class ChatInputManagerTest {

    private lateinit var manager: ChatInputManager

    @BeforeEach
    fun setup() {
        manager = ChatInputManager()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun mockPlayer(id: UUID = UUID.randomUUID()): Player {
        val p = mockk<Player>(relaxed = true)
        every { p.uniqueId } returns id
        return p
    }

    // =========================================================================
    // 1. Normal response — awaitChatInput returns the player's message
    // =========================================================================

    @Test
    fun `awaitChatInput returns the player message when a normal message is delivered`() = runTest {
        val player = mockPlayer()
        var result: String? = null

        val job = launch {
            result = manager.awaitChatInput(player, "Enter value:")
        }

        // Let the coroutine run and register the session
        advanceUntilIdle()

        val consumed = manager.onChatMessage(player.uniqueId, "hello world")
        job.join()

        assertTrue(consumed, "onChatMessage should return true when session is active")
        assertEquals("hello world", result)
    }

    @Test
    fun `awaitChatInput sends the prompt to the player`() = runTest {
        val player = mockPlayer()

        val job = launch {
            manager.awaitChatInput(player, "Please enter your name:")
        }

        advanceUntilIdle()
        manager.onChatMessage(player.uniqueId, "Alice")
        job.join()

        verify { player.sendMessage("Please enter your name:") }
    }

    @Test
    fun `onChatMessage returns false when no session is active for that player`() {
        val result = manager.onChatMessage(UUID.randomUUID(), "hello")
        assertFalse(result, "Should return false when no active session exists")
    }

    @Test
    fun `hasActiveSession returns true while session is pending and false after completion`() = runTest {
        val player = mockPlayer()

        assertFalse(manager.hasActiveSession(player.uniqueId))

        val job = launch {
            manager.awaitChatInput(player, "prompt")
        }

        advanceUntilIdle()
        assertTrue(manager.hasActiveSession(player.uniqueId))

        manager.onChatMessage(player.uniqueId, "response")
        job.join()

        assertFalse(manager.hasActiveSession(player.uniqueId), "Session should be cleaned up after completion")
    }

    // =========================================================================
    // 2. Cancel interception — Requirement 1.7
    //    WHEN player types "cancel", awaitChatInput returns null
    // =========================================================================

    @Test
    fun `awaitChatInput returns null when player types 'cancel'`() = runTest {
        val player = mockPlayer()
        var result: String? = "not_set"

        val job = launch {
            result = manager.awaitChatInput(player, "Enter value:")
        }

        advanceUntilIdle()
        manager.onChatMessage(player.uniqueId, "cancel")
        job.join()

        assertNull(result, "awaitChatInput should return null when player types 'cancel'")
    }

    @Test
    fun `cancel interception is case-insensitive - CANCEL`() = runTest {
        val player = mockPlayer()
        var result: String? = "not_set"

        val job = launch {
            result = manager.awaitChatInput(player, "Enter value:")
        }

        advanceUntilIdle()
        manager.onChatMessage(player.uniqueId, "CANCEL")
        job.join()

        assertNull(result)
    }

    @Test
    fun `cancel interception is case-insensitive - Cancel`() = runTest {
        val player = mockPlayer()
        var result: String? = "not_set"

        val job = launch {
            result = manager.awaitChatInput(player, "Enter value:")
        }

        advanceUntilIdle()
        manager.onChatMessage(player.uniqueId, "Cancel")
        job.join()

        assertNull(result)
    }

    @Test
    fun `message 'cancellation' is NOT treated as cancel - only exact word 'cancel'`() = runTest {
        val player = mockPlayer()
        var result: String? = null

        val job = launch {
            result = manager.awaitChatInput(player, "Enter value:")
        }

        advanceUntilIdle()
        manager.onChatMessage(player.uniqueId, "cancellation")
        job.join()

        assertEquals("cancellation", result, "Only exact 'cancel' should trigger null return")
    }

    // =========================================================================
    // 3. Disconnect behavior — Requirements 1.8, 3.5
    //    onPlayerDisconnect throws ChatInputCancelledException to the coroutine
    // =========================================================================

    @Test
    fun `onPlayerDisconnect throws ChatInputCancelledException to the waiting coroutine`() = runTest {
        val player = mockPlayer()
        var thrownException: ChatInputCancelledException? = null

        val job = launch {
            try {
                manager.awaitChatInput(player, "Enter value:")
            } catch (e: ChatInputCancelledException) {
                thrownException = e
            }
        }

        advanceUntilIdle()
        manager.onPlayerDisconnect(player.uniqueId)
        job.join()

        assertTrue(thrownException != null, "ChatInputCancelledException should be thrown on disconnect")
        assertEquals(player.uniqueId, thrownException!!.playerId)
    }

    @Test
    fun `onPlayerDisconnect cleans up the session`() = runTest {
        val player = mockPlayer()

        val job = launch {
            try {
                manager.awaitChatInput(player, "Enter value:")
            } catch (_: ChatInputCancelledException) { }
        }

        advanceUntilIdle()
        assertTrue(manager.hasActiveSession(player.uniqueId))

        manager.onPlayerDisconnect(player.uniqueId)
        job.join()

        assertFalse(manager.hasActiveSession(player.uniqueId), "Session should be removed after disconnect")
    }

    @Test
    fun `onPlayerDisconnect on player with no active session does not throw`() {
        // Should be a no-op
        manager.onPlayerDisconnect(UUID.randomUUID())
    }

    // =========================================================================
    // 4. inputChain collects all responses in order
    // =========================================================================

    @Test
    fun `inputChain returns all responses mapped to their labels`() = runTest {
        val player = mockPlayer()
        val prompts = listOf(
            "name" to "Enter your name:",
            "age" to "Enter your age:",
            "city" to "Enter your city:"
        )

        var result: Map<String, String>? = null
        val job = launch {
            result = manager.inputChain(player, prompts)
        }

        // Deliver responses one by one, advancing between each
        advanceUntilIdle()
        manager.onChatMessage(player.uniqueId, "Alice")
        advanceUntilIdle()
        manager.onChatMessage(player.uniqueId, "30")
        advanceUntilIdle()
        manager.onChatMessage(player.uniqueId, "Paris")
        job.join()

        assertEquals(mapOf("name" to "Alice", "age" to "30", "city" to "Paris"), result)
    }

    @Test
    fun `inputChain throws ChatInputCancelledException when player types cancel mid-chain`() = runTest {
        val player = mockPlayer()
        val prompts = listOf(
            "first" to "First prompt:",
            "second" to "Second prompt:"
        )

        var thrownException: ChatInputCancelledException? = null
        val job = launch {
            try {
                manager.inputChain(player, prompts)
            } catch (e: ChatInputCancelledException) {
                thrownException = e
            }
        }

        advanceUntilIdle()
        manager.onChatMessage(player.uniqueId, "first answer")
        advanceUntilIdle()
        manager.onChatMessage(player.uniqueId, "cancel")
        job.join()

        assertTrue(thrownException != null, "ChatInputCancelledException should be thrown when cancel is typed mid-chain")
        assertEquals(player.uniqueId, thrownException!!.playerId)
    }

    @Test
    fun `inputChain with empty prompts list returns empty map`() = runTest {
        val player = mockPlayer()
        val result = manager.inputChain(player, emptyList())
        assertEquals(emptyMap(), result)
    }

    // =========================================================================
    // 5. inputChain throws ChatInputCancelledException on disconnect — Requirement 3.5
    // =========================================================================

    @Test
    fun `inputChain throws ChatInputCancelledException when player disconnects at first prompt`() = runTest {
        val player = mockPlayer()
        val prompts = listOf(
            "first" to "First prompt:",
            "second" to "Second prompt:"
        )

        var thrownException: ChatInputCancelledException? = null
        val job = launch {
            try {
                manager.inputChain(player, prompts)
            } catch (e: ChatInputCancelledException) {
                thrownException = e
            }
        }

        advanceUntilIdle()
        manager.onPlayerDisconnect(player.uniqueId)
        job.join()

        assertTrue(thrownException != null, "ChatInputCancelledException should propagate from inputChain on disconnect")
        assertEquals(player.uniqueId, thrownException!!.playerId)
    }

    @Test
    fun `inputChain throws ChatInputCancelledException when player disconnects mid-chain`() = runTest {
        val player = mockPlayer()
        val prompts = listOf(
            "first" to "First prompt:",
            "second" to "Second prompt:"
        )

        var thrownException: ChatInputCancelledException? = null
        val job = launch {
            try {
                manager.inputChain(player, prompts)
            } catch (e: ChatInputCancelledException) {
                thrownException = e
            }
        }

        advanceUntilIdle()
        manager.onChatMessage(player.uniqueId, "first answer")
        advanceUntilIdle()
        manager.onPlayerDisconnect(player.uniqueId)
        job.join()

        assertTrue(thrownException != null, "ChatInputCancelledException should be thrown when disconnecting mid-chain")
        assertEquals(player.uniqueId, thrownException!!.playerId)
    }

    // =========================================================================
    // 6. Session isolation — multiple players don't interfere
    // =========================================================================

    @Test
    fun `sessions for different players are independent`() = runTest {
        val player1 = mockPlayer()
        val player2 = mockPlayer()
        var result1: String? = null
        var result2: String? = null

        val job1 = launch { result1 = manager.awaitChatInput(player1, "p1 prompt") }
        val job2 = launch { result2 = manager.awaitChatInput(player2, "p2 prompt") }

        advanceUntilIdle()
        manager.onChatMessage(player1.uniqueId, "response1")
        manager.onChatMessage(player2.uniqueId, "response2")
        job1.join()
        job2.join()

        assertEquals("response1", result1)
        assertEquals("response2", result2)
    }

    @Test
    fun `disconnect of one player does not affect another player's session`() = runTest {
        val player1 = mockPlayer()
        val player2 = mockPlayer()
        var result2: String? = null
        var exception1: ChatInputCancelledException? = null

        val job1 = launch {
            try {
                manager.awaitChatInput(player1, "p1 prompt")
            } catch (e: ChatInputCancelledException) {
                exception1 = e
            }
        }
        val job2 = launch {
            result2 = manager.awaitChatInput(player2, "p2 prompt")
        }

        advanceUntilIdle()
        manager.onPlayerDisconnect(player1.uniqueId)
        manager.onChatMessage(player2.uniqueId, "player2 answer")
        job1.join()
        job2.join()

        assertTrue(exception1 != null, "Player1 should get ChatInputCancelledException")
        assertEquals("player2 answer", result2, "Player2 session should be unaffected")
    }
}
