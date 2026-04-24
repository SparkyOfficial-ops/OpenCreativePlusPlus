@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.event

// Feature: ocp-manifest-roadmap, Property 9: EventDispatcher не вызывает CodeLine при несоответствии условия

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.execution.ExecutionEngine
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property 9: EventDispatcher не вызывает CodeLine при несоответствии условия
 *
 * For any event and registered CodeLine with a trigger condition (event type),
 * if the dispatched event type does NOT match the registered script's event type,
 * EventDispatcher MUST NOT invoke CodeLine execution.
 *
 * Validates: Requirements 7.5 (ocp-manifest-roadmap)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDispatcherConditionPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
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
    // Property 9: EventDispatcher does NOT invoke execution when event type mismatches
    // -----------------------------------------------------------------------

    "Property 9: EventDispatcher does not invoke CodeLine when trigger condition is false" - {
        // Validates: Requirements 7.5
        // For any registered event type and any dispatched event type that differs,
        // EventDispatcher must NOT call executeScript.
        "no execution when dispatched event type differs from registered event type" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.string(3..20),
                Arb.string(3..20)
            ) { registeredType, dispatchedType ->
                // Ensure the two types are different — skip if they happen to be equal
                if (registeredType == dispatchedType) return@checkAll

                val engine = mockk<ExecutionEngine>(relaxed = true)
                val testDispatcher = StandardTestDispatcher()
                val testScope = TestScope(testDispatcher)

                val dispatcher = EventDispatcher(engine, testScope)
                val plotId = UUID.randomUUID()

                // Register a script that listens to registeredType
                dispatcher.registerScripts(plotId, listOf(makeScript(registeredType)))

                // Dispatch a DIFFERENT event type — condition is NOT met
                dispatcher.dispatchEvent(plotId, dispatchedType, emptyMap(), null)
                testScope.advanceUntilIdle()

                // ExecutionEngine must NOT have been called
                coVerify(exactly = 0) { engine.executeScript(any(), any(), any(), any()) }
            }
        }

        "no execution when no scripts are registered for the plot" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.string(3..20)
            ) { eventType ->
                val engine = mockk<ExecutionEngine>(relaxed = true)
                val testDispatcher = StandardTestDispatcher()
                val testScope = TestScope(testDispatcher)

                val dispatcher = EventDispatcher(engine, testScope)
                val plotId = UUID.randomUUID()

                // No scripts registered — condition is never met
                dispatcher.dispatchEvent(plotId, eventType, emptyMap(), null)
                testScope.advanceUntilIdle()

                coVerify(exactly = 0) { engine.executeScript(any(), any(), any(), any()) }
            }
        }

        "no execution after scripts are unregistered" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.string(3..20)
            ) { eventType ->
                val engine = mockk<ExecutionEngine>(relaxed = true)
                val testDispatcher = StandardTestDispatcher()
                val testScope = TestScope(testDispatcher)

                val dispatcher = EventDispatcher(engine, testScope)
                val plotId = UUID.randomUUID()

                // Register then immediately unregister — condition can never be met
                dispatcher.registerScripts(plotId, listOf(makeScript(eventType)))
                dispatcher.unregisterScripts(plotId)

                dispatcher.dispatchEvent(plotId, eventType, emptyMap(), null)
                testScope.advanceUntilIdle()

                coVerify(exactly = 0) { engine.executeScript(any(), any(), any(), any()) }
            }
        }
    }
})
