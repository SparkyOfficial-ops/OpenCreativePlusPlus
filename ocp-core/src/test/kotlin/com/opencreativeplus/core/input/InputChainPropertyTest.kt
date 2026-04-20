@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.core.input

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Property-based tests for [ChatInputManager.inputChain] — collects all responses.
 *
 * Property 8: inputChain collects all responses
 *  3.4
 *
 * For any sequence of N labeled prompts, when the player provides N non-cancel responses,
 * `inputChain` must return a map of exactly N entries where each label maps to the
 * corresponding response in order.
 */
class InputChainPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    fun mockPlayer(id: UUID = UUID.randomUUID()): Player {
        val p = mockk<Player>(relaxed = true)
        every { p.uniqueId } returns id
        return p
    }

    /** Arbitrary non-"cancel" response strings. */
    val arbResponse: Arb<String> =
        Arb.string(1..50).filter { it.lowercase() != "cancel" }

    // -----------------------------------------------------------------------
    // Property 8a — all responses collected: result has exactly N entries
    // -----------------------------------------------------------------------

    "Property 8a: inputChain result map has exactly N entries for N prompts" - {

        "result size equals number of prompts" {
            //  3.4
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1..10)
            ) { n ->
                val manager = ChatInputManager()
                val player = mockPlayer()

                // Build index-based prompts to guarantee unique labels
                val prompts = (0 until n).map { i -> "label_$i" to "prompt_$i" }
                val responses = (0 until n).map { i -> "response_$i" }

                var result: Map<String, String>? = null

                runTest {
                    val job = launch {
                        result = manager.inputChain(player, prompts)
                    }
                    // Deliver each response sequentially
                    for (response in responses) {
                        runCurrent()
                        manager.onChatMessage(player.uniqueId, response)
                    }
                    job.join()
                }

                result!! shouldHaveSize n
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8b — correct mapping: each label maps to its response
    // -----------------------------------------------------------------------

    "Property 8b: each label maps to the correct response" - {

        "label_i maps to response_i for all i" {
            //  3.4
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1..10),
                Arb.list(arbResponse, 1..10)
            ) { n, rawResponses ->
                val manager = ChatInputManager()
                val player = mockPlayer()

                val prompts = (0 until n).map { i -> "label_$i" to "prompt_$i" }
                // Pad or trim responses to exactly n entries
                val responses = (0 until n).map { i ->
                    rawResponses.getOrElse(i) { "fallback_$i" }
                }

                var result: Map<String, String>? = null

                runTest {
                    val job = launch {
                        result = manager.inputChain(player, prompts)
                    }
                    for (response in responses) {
                        runCurrent()
                        manager.onChatMessage(player.uniqueId, response)
                    }
                    job.join()
                }

                for (i in 0 until n) {
                    result!!["label_$i"] shouldBe responses[i]
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8c — sequential delivery: prompts consumed in order
    // -----------------------------------------------------------------------

    "Property 8c: responses are consumed in the order prompts were declared" - {

        "first response goes to first label, second to second, etc." {
            //  3.4 — sequential execution of prompts
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.list(arbResponse, 2..8)
            ) { responses ->
                val manager = ChatInputManager()
                val player = mockPlayer()
                val n = responses.size
                val prompts = (0 until n).map { i -> "key_$i" to "ask_$i" }

                var result: Map<String, String>? = null

                runTest {
                    val job = launch {
                        result = manager.inputChain(player, prompts)
                    }
                    responses.forEachIndexed { _, resp ->
                        runCurrent()
                        // Verify only the current prompt's session is active
                        manager.hasActiveSession(player.uniqueId) shouldBe true
                        manager.onChatMessage(player.uniqueId, resp)
                    }
                    job.join()
                }

                // Verify order: key_i -> responses[i]
                responses.forEachIndexed { i, expected ->
                    result!!["key_$i"] shouldBe expected
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8d — cancel mid-chain: throws ChatInputCancelledException
    // -----------------------------------------------------------------------

    "Property 8d: cancel at any position in the chain throws ChatInputCancelledException" - {

        "cancel at first prompt throws immediately" {
            //  3.4 — cancel propagates as exception
            checkAll(PropTestConfig(iterations = 20), Arb.int(1..8)) { n ->
                val manager = ChatInputManager()
                val player = mockPlayer()
                val prompts = (0 until n).map { i -> "label_$i" to "prompt_$i" }

                var thrownException: ChatInputCancelledException? = null

                runTest {
                    val job = launch {
                        try {
                            manager.inputChain(player, prompts)
                        } catch (e: ChatInputCancelledException) {
                            thrownException = e
                        }
                    }
                    runCurrent()
                    manager.onChatMessage(player.uniqueId, "cancel")
                    job.join()
                }

                thrownException?.playerId shouldBe player.uniqueId
            }
        }

        "cancel after k valid responses throws and stops chain" {
            //  3.4 — no further prompts after cancel
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(2..8)
            ) { n ->
                val manager = ChatInputManager()
                val player = mockPlayer()
                val prompts = (0 until n).map { i -> "label_$i" to "prompt_$i" }
                val cancelAt = n / 2 // cancel halfway through

                var thrownException: ChatInputCancelledException? = null

                runTest {
                    val job = launch {
                        try {
                            manager.inputChain(player, prompts)
                        } catch (e: ChatInputCancelledException) {
                            thrownException = e
                        }
                    }
                    // Deliver valid responses up to cancelAt
                    for (i in 0 until cancelAt) {
                        runCurrent()
                        manager.onChatMessage(player.uniqueId, "response_$i")
                    }
                    // Then cancel
                    runCurrent()
                    manager.onChatMessage(player.uniqueId, "cancel")
                    job.join()
                }

                thrownException?.playerId shouldBe player.uniqueId
                // Session should be cleaned up after exception
                manager.hasActiveSession(player.uniqueId) shouldBe false
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8e — empty prompts: returns empty map
    // -----------------------------------------------------------------------

    "Property 8e: inputChain with empty prompts list returns empty map" - {

        "empty prompts -> empty result" {
            //  3.4
            val manager = ChatInputManager()
            val player = mockPlayer()

            runTest {
                val result = manager.inputChain(player, emptyList())
                result shouldHaveSize 0
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8f — session cleanup: no active session after chain completes
    // -----------------------------------------------------------------------

    "Property 8f: no active session remains after inputChain completes" - {

        "session is cleaned up on successful completion" {
            //  3.4
            checkAll(PropTestConfig(iterations = 20), Arb.int(1..5)) { n ->
                val manager = ChatInputManager()
                val player = mockPlayer()
                val prompts = (0 until n).map { i -> "label_$i" to "prompt_$i" }

                runTest {
                    val job = launch {
                        manager.inputChain(player, prompts)
                    }
                    for (i in 0 until n) {
                        runCurrent()
                        manager.onChatMessage(player.uniqueId, "response_$i")
                    }
                    job.join()
                }

                manager.hasActiveSession(player.uniqueId) shouldBe false
            }
        }
    }
})
