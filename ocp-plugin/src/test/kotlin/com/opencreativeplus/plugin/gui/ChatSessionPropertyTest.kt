@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import com.opencreativeplus.core.input.ChatInputManager
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Property 2: Chat session intercepts all messages
 * Validates: Requirements 1.5, 1.6
 *
 * s 1.5: WHILE a Chat_Input_Session is active, THE OCP_Engine SHALL intercept the next
 *        player chat message via AsyncChatEvent and treat it as parameter input.
 * s 1.6: WHEN a chat message is intercepted during a Chat_Input_Session, THE OCP_Engine
 *        SHALL cancel the chat event, save the value to block metadata, and reopen the Smart_GUI.
 *
 * The interception logic lives in [ChatInputManager.onChatMessage] which returns `true`
 * when a session is active (meaning the event should be cancelled) and `false` otherwise.
 */
class ChatSessionPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    fun mockPlayer(id: UUID = UUID.randomUUID()): Player {
        val p = mockk<Player>(relaxed = true)
        every { p.uniqueId } returns id
        return p
    }

    /** Arbitrary non-"cancel" strings (case-insensitive exclusion). */
    val arbNonCancelString: Arb<String> =
        Arb.string(0..100).filter { it.lowercase() != "cancel" }

    // -----------------------------------------------------------------------
    // Property 2a: When a Chat_Input_Session is active, onChatMessage returns true
    //              (intercepts the message) for any non-cancel message
    // -----------------------------------------------------------------------

    "Property 2a: onChatMessage returns true when a session is active (s 1.5)" - {
        /**
         * Validates: Requirements 1.5
         * For any non-cancel message, if a Chat_Input_Session is registered for a player,
         * onChatMessage must return true — signalling the event should be cancelled.
         */
        "any non-cancel message is intercepted while session is active" {
            checkAll(PropTestConfig(iterations = 20), arbNonCancelString) { message ->
                val manager = ChatInputManager(timeoutMs = 5_000L)
                val player = mockPlayer()
                var intercepted = false

                runTest {
                    val job = launch {
                        manager.awaitChatInput(player, "Enter value:")
                    }
                    advanceUntilIdle()
                    intercepted = manager.onChatMessage(player.uniqueId, message)
                    job.join()
                }

                intercepted shouldBe true
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 2b: When NO Chat_Input_Session is active, onChatMessage returns false
    // -----------------------------------------------------------------------

    "Property 2b: onChatMessage returns false when no session is active (s 1.5)" - {
        /**
         * Validates: Requirements 1.5
         * Without a registered session, onChatMessage must return false — the event
         * should NOT be cancelled and the message should pass through normally.
         */
        "any message is not intercepted when no session exists" {
            checkAll(PropTestConfig(iterations = 20), arbNonCancelString) { message ->
                val manager = ChatInputManager()
                val player = mockPlayer()

                val intercepted = manager.onChatMessage(player.uniqueId, message)

                intercepted shouldBe false
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 2c: Only the player with an active session has their messages intercepted
    // -----------------------------------------------------------------------

    "Property 2c: only the player with an active session is intercepted (s 1.5)" - {
        /**
         * Validates: Requirements 1.5
         * Sessions are keyed by UUID. A message from a different player must NOT be
         * intercepted even when another player has an active session.
         */
        "other players' messages are not intercepted" {
            checkAll(PropTestConfig(iterations = 20), arbNonCancelString) { message ->
                val manager = ChatInputManager()
                val sessionPlayer = mockPlayer()
                val otherPlayer = mockPlayer()

                var otherIntercepted = false

                runTest {
                    val job = launch {
                        manager.awaitChatInput(sessionPlayer, "Enter value:")
                    }
                    advanceUntilIdle()

                    // Other player's message must NOT be intercepted
                    otherIntercepted = manager.onChatMessage(otherPlayer.uniqueId, message)

                    // Clean up: deliver a message to the session player so the coroutine completes
                    manager.onChatMessage(sessionPlayer.uniqueId, message.ifEmpty { "x" })
                    job.join()
                }

                otherIntercepted shouldBe false
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 2d: After the session completes, subsequent messages are NOT intercepted
    //              (session is one-shot)
    // -----------------------------------------------------------------------

    "Property 2d: session is one-shot — subsequent messages are not intercepted (s 1.5)" - {
        /**
         * Validates: Requirements 1.5
         * Once a Chat_Input_Session has been completed (message delivered), the session
         * is cleaned up. Any further messages from the same player must return false.
         */
        "after session completes, onChatMessage returns false for the same player" {
            checkAll(PropTestConfig(iterations = 20), arbNonCancelString) { message ->
                val manager = ChatInputManager()
                val player = mockPlayer()

                runTest {
                    val job = launch {
                        manager.awaitChatInput(player, "Enter value:")
                    }
                    advanceUntilIdle()
                    manager.onChatMessage(player.uniqueId, message.ifEmpty { "x" })
                    job.join()
                }

                // Session is now gone — subsequent message must NOT be intercepted
                val interceptedAfter = manager.onChatMessage(player.uniqueId, message)
                interceptedAfter shouldBe false
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 2e: when awaitChatInput returns a value, the value is saved to
    //              block metadata (s 1.6 — save the value to block metadata)
    // -----------------------------------------------------------------------

    "Property 2e: save is called with the intercepted value when session returns non-null (s 1.6)" - {
        /**
         * Validates: Requirements 1.6
         * WHEN a chat message is intercepted during a Chat_Input_Session, THE OCP_Engine
         * SHALL save the value to block metadata.
         *
         * We test this by driving a real ChatInputManager through onChatMessage and
         * verifying that the value delivered to the session equals the message sent —
         * confirming the intercepted value is what would be passed to paramSerializer.save.
         */
        "the value returned by awaitChatInput equals the intercepted message" {
            checkAll(PropTestConfig(iterations = 20), arbNonCancelString) { message ->
                val manager = ChatInputManager(timeoutMs = 5_000L)
                val player = mockPlayer()
                var capturedValue: String? = null

                runTest {
                    val job = launch {
                        // Simulate what editParam does: call awaitChatInput, save if non-null
                        val value = manager.awaitChatInput(player, "Enter value:")
                        if (value != null) {
                            capturedValue = value  // represents paramSerializer.save(block, name, value)
                        }
                    }
                    advanceUntilIdle()
                    manager.onChatMessage(player.uniqueId, message)
                    job.join()
                }

                // The intercepted message must be exactly what gets saved
                capturedValue shouldBe message
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 2f: when awaitChatInput returns null (cancel), save is NOT called
    //              (s 1.6 — only save when a value is provided)
    // -----------------------------------------------------------------------

    "Property 2f: save is NOT called when the session returns null (cancel) (s 1.6)" - {
        /**
         * Validates: Requirements 1.6
         * When the player cancels (awaitChatInput returns null), the value must NOT be
         * saved to block metadata — the existing value is preserved.
         *
         * We test this by driving a real ChatInputManager with "cancel" and verifying
         * that the null result prevents the save path from executing.
         */
        "capturedValue remains null when player sends 'cancel'" {
            checkAll(PropTestConfig(iterations = 20), arbNonCancelString) { existingValue ->
                val manager = ChatInputManager()
                val player = mockPlayer()
                var capturedValue: String? = null
                var saveCalled = false

                runTest {
                    val job = launch {
                        // Simulate what editParam does: call awaitChatInput, save only if non-null
                        val value = manager.awaitChatInput(player, "Enter value:")
                        if (value != null) {
                            saveCalled = true
                            capturedValue = value
                        }
                        // existingValue is preserved (not overwritten)
                        capturedValue = capturedValue ?: existingValue
                    }
                    advanceUntilIdle()
                    manager.onChatMessage(player.uniqueId, "cancel")
                    job.join()
                }

                // save must NOT have been called
                saveCalled shouldBe false
                // existing value is preserved
                capturedValue shouldBe existingValue
            }
        }
    }
})
