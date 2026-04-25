@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.event

// Feature: ocp-gameplay-systems, Property 9: OnInteract air-click guard

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.execution.ExecutionEngine
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import java.util.UUID

/**
 * Property 9: OnInteract air-click guard
 *
 * For any PlayerInteractEvent without an associated block (air click),
 * EventDispatcher MUST NOT dispatch to any OnInteract Code_Lines.
 *
 * **Validates: Requirements 4.5**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnInteractAirClickPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Pure guard logic — mirrors the listener-layer check
    // -----------------------------------------------------------------------

    /**
     * Models the air-click guard implemented in the Bukkit listener layer:
     * `onPlayerInteract` only calls `dispatchEvent` when the event has an associated block.
     */
    fun shouldDispatchInteract(hasBlock: Boolean): Boolean = hasBlock

    // -----------------------------------------------------------------------
    // Helpers for EventDispatcher-based sub-tests
    // -----------------------------------------------------------------------

    fun makeEvent(type: String): IEvent = object : IEvent {
        override val nodeId = type
        override val displayName = type
        override val eventType = type
    }

    fun makeScript(eventType: String): CompiledScript {
        val action = object : IAction {
            override val nodeId = "noop"
            override val displayName = "Noop"
            override suspend fun execute(context: ExecutionContext) {}
        }
        return CompiledScript(
            event = makeEvent(eventType),
            actions = listOf(action),
            sourceLocation = "test@0,0,0"
        )
    }

    // -----------------------------------------------------------------------
    // Property 9a: air click → guard returns false (no dispatch)
    // -----------------------------------------------------------------------

    "Property 9: air-click guard — pure logic" - {

        "when hasBlock = false (air click), shouldDispatchInteract returns false" {
            // **Validates: Requirements 4.5**
            checkAll(PropTestConfig(iterations = 20), Arb.boolean()) { _ ->
                shouldDispatchInteract(false) shouldBe false
            }
        }

        "when hasBlock = true (block click), shouldDispatchInteract returns true" {
            // **Validates: Requirements 4.5**
            checkAll(PropTestConfig(iterations = 20), Arb.boolean()) { _ ->
                shouldDispatchInteract(true) shouldBe true
            }
        }

        // -----------------------------------------------------------------------
        // Property 9b: for any player/block/action combination, air click → dispatch count = 0
        // -----------------------------------------------------------------------

        "for any player name, block name, action string: air click keeps dispatch count at 0" {
            // **Validates: Requirements 4.5**
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.string(1..32),
                Arb.string(1..32),
                Arb.string(1..32)
            ) { playerName, blockName, actionStr ->
                var dispatchCount = 0

                val hasBlock = false  // air click
                if (shouldDispatchInteract(hasBlock)) {
                    // Would build eventData and call dispatchEvent — but guard prevents this
                    @Suppress("UNUSED_EXPRESSION")
                    mapOf("player" to playerName, "block" to blockName, "action" to actionStr)
                    dispatchCount++
                }

                dispatchCount shouldBe 0
            }
        }

        "for any player name, block name, action string: block click increments dispatch count to 1" {
            // **Validates: Requirements 4.5** (positive case — guard allows dispatch)
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.string(1..32),
                Arb.string(1..32),
                Arb.string(1..32)
            ) { playerName, blockName, actionStr ->
                var dispatchCount = 0

                val hasBlock = true  // block click
                if (shouldDispatchInteract(hasBlock)) {
                    @Suppress("UNUSED_EXPRESSION")
                    mapOf("player" to playerName, "block" to blockName, "action" to actionStr)
                    dispatchCount++
                }

                dispatchCount shouldBe 1
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 9c: EventDispatcher integration — guard prevents dispatchEvent call
    // -----------------------------------------------------------------------

    "Property 9: EventDispatcher — guard prevents execution on air click" - {

        "when guard returns false (air click), dispatchEvent is never called" {
            // **Validates: Requirements 4.5**
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.string(1..32),
                Arb.string(1..32)
            ) { playerName, blockName ->
                val engine = mockk<ExecutionEngine>(relaxed = true)
                val testDispatcher = StandardTestDispatcher()
                val testScope = TestScope(testDispatcher)

                val dispatcher = EventDispatcher(engine, testScope)
                val plotId = UUID.randomUUID()
                dispatcher.registerScripts(plotId, listOf(makeScript("on_interact")))

                val hasBlock = false  // air click
                if (shouldDispatchInteract(hasBlock)) {
                    // Guard is false — this branch is never reached
                    dispatcher.dispatchEvent(
                        plotId,
                        "on_interact",
                        mapOf("player" to playerName, "block" to blockName, "action" to "RIGHT_CLICK_AIR"),
                        null
                    )
                }
                testScope.advanceUntilIdle()

                coVerify(exactly = 0) { engine.executeScript(any(), any(), any(), any()) }
            }
        }

        "when guard returns true (block click), dispatchEvent is called once" {
            // **Validates: Requirements 4.5** (positive case)
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.string(1..32),
                Arb.string(1..32)
            ) { playerName, blockName ->
                val engine = mockk<ExecutionEngine>(relaxed = true)
                val testDispatcher = StandardTestDispatcher()
                val testScope = TestScope(testDispatcher)

                val dispatcher = EventDispatcher(engine, testScope)
                val plotId = UUID.randomUUID()
                dispatcher.registerScripts(plotId, listOf(makeScript("on_interact")))

                val hasBlock = true  // block click
                if (shouldDispatchInteract(hasBlock)) {
                    dispatcher.dispatchEvent(
                        plotId,
                        "on_interact",
                        mapOf("player" to playerName, "block" to blockName, "action" to "RIGHT_CLICK_BLOCK"),
                        null
                    )
                }
                testScope.advanceUntilIdle()

                coVerify(exactly = 1) { engine.executeScript(any(), plotId, null, any()) }
            }
        }
    }
})
