@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.core.input

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
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
 * Property-based tests for [ChatInputManager.awaitChatInput] round-trip.
 *
 *  3.3
 *
 * Property 7: awaitChatInput round-trip — for any arbitrary non-"cancel" string sent by a
 * player, `awaitChatInput` must return exactly that string (input == output). The coroutine
 * must resume with the exact value passed to `onChatMessage`.
 */
class ChatInputPropertyTest : FreeSpec({

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
    // Property 7a — round-trip: awaitChatInput returns the exact message
    // -----------------------------------------------------------------------

    "Property 7a: awaitChatInput returns the exact message for any non-cancel string" - {

        "input equals output (round-trip)" {
            //  3.3
            checkAll(PropTestConfig(iterations = 20), arbNonCancelString) { message ->
                val manager = ChatInputManager()
                val player = mockPlayer()
                var result: String? = null

                runTest {
                    val job = launch {
                        result = manager.awaitChatInput(player, "prompt")
                    }
                    advanceUntilIdle()
                    manager.onChatMessage(player.uniqueId, message)
                    job.join()
                }

                result shouldBe message
            }
        }

        "onChatMessage returns true when session is active" {
            //  3.3 — session is registered before message delivery
            checkAll(PropTestConfig(iterations = 20), arbNonCancelString) { message ->
                val manager = ChatInputManager()
                val player = mockPlayer()
                var consumed = false

                runTest {
                    val job = launch {
                        manager.awaitChatInput(player, "prompt")
                    }
                    advanceUntilIdle()
                    consumed = manager.onChatMessage(player.uniqueId, message)
                    job.join()
                }

                consumed shouldBe true
            }
        }

        "session is cleaned up after coroutine resumes" {
            //  3.3 — finally block removes the session
            checkAll(PropTestConfig(iterations = 20), arbNonCancelString) { message ->
                val manager = ChatInputManager()
                val player = mockPlayer()

                runTest {
                    val job = launch {
                        manager.awaitChatInput(player, "prompt")
                    }
                    advanceUntilIdle()
                    manager.onChatMessage(player.uniqueId, message)
                    job.join()
                }

                manager.hasActiveSession(player.uniqueId) shouldBe false
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 7b — cancel case: "cancel" (any casing) returns null
    // -----------------------------------------------------------------------

    "Property 7b: awaitChatInput returns null when player sends 'cancel' (case-insensitive)" - {

        val cancelVariants = listOf("cancel", "CANCEL", "Cancel", "cAnCeL", "CaNcEl")

        cancelVariants.forEach { variant ->
            "cancel variant '$variant' returns null" {
                //  3.3
                val manager = ChatInputManager()
                val player = mockPlayer()
                var result: String? = "not_set"

                runTest {
                    val job = launch {
                        result = manager.awaitChatInput(player, "prompt")
                    }
                    advanceUntilIdle()
                    manager.onChatMessage(player.uniqueId, variant)
                    job.join()
                }

                result.shouldBeNull()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 7c — session isolation: multiple players don't interfere
    // -----------------------------------------------------------------------

    "Property 7c: concurrent sessions for different players are independent" - {

        "each player receives their own message" {
            //  3.3 — sessions keyed by UUID
            checkAll(
                PropTestConfig(iterations = 20),
                arbNonCancelString,
                arbNonCancelString
            ) { msg1, msg2 ->
                val manager = ChatInputManager()
                val player1 = mockPlayer()
                val player2 = mockPlayer()
                var result1: String? = null
                var result2: String? = null

                runTest {
                    val job1 = launch { result1 = manager.awaitChatInput(player1, "p1") }
                    val job2 = launch { result2 = manager.awaitChatInput(player2, "p2") }
                    advanceUntilIdle()
                    manager.onChatMessage(player1.uniqueId, msg1)
                    manager.onChatMessage(player2.uniqueId, msg2)
                    job1.join()
                    job2.join()
                }

                result1 shouldBe msg1
                result2 shouldBe msg2
            }
        }
    }
})
