@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.event

// Feature: ocp-plugin-fixes-and-completions, Property 5: Каждое изменение переменной в scope `plot`/`saved` порождает ровно одно событие `variable_change` с корректным `variable_name`

import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.model.VariableScopeType
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.execution.ExecutionEngine
import com.opencreativeplus.core.execution.VariableManager
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import java.util.UUID

/**
 * Property 5: Каждое изменение переменной в scope `plot`/`saved` порождает ровно одно событие
 * `variable_change` с корректным `variable_name`.
 *
 * **Validates: Requirements 5.2, 5.3, 5.4**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VariableChangeReactivityPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    fun makeVariableManager(): VariableManager {
        val db = mockk<MongoDatabase>(relaxed = true)
        return VariableManager(db)
    }

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
    // Property 5a: emitChange for PLOT scope produces exactly one VariableChange with correct name
    // -----------------------------------------------------------------------

    "Property 5a: emitChange for PLOT scope produces exactly one VariableChange event with correct name" - {
        // **Validates: Requirements 5.2**
        "for any plotId, varName, varValue: PLOT scope emits exactly 1 event with matching name" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid(),
                Arb.string(1..32),
                Arb.string()
            ) { plotId, varName, varValue ->
                val testDispatcher = StandardTestDispatcher()
                val testScope = TestScope(testDispatcher)

                val variableManager = makeVariableManager()

                val received = mutableListOf<com.opencreativeplus.api.model.VariableChange>()
                val collectJob = testScope.launch {
                    variableManager.changes(plotId).take(1).toList(received)
                }

                testScope.launch {
                    variableManager.emitChange(plotId, varName, varValue, VariableScopeType.PLOT)
                }

                testScope.advanceUntilIdle()
                collectJob.join()

                received.size shouldBe 1
                received[0].name shouldBe varName
                received[0].scope shouldBe VariableScopeType.PLOT
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 5b: emitChange for SAVED scope produces exactly one VariableChange with correct name
    // -----------------------------------------------------------------------

    "Property 5b: emitChange for SAVED scope produces exactly one VariableChange event with correct name" - {
        // **Validates: Requirements 5.2**
        "for any plotId, varName, varValue: SAVED scope emits exactly 1 event with matching name" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid(),
                Arb.string(1..32),
                Arb.string()
            ) { plotId, varName, varValue ->
                val testDispatcher = StandardTestDispatcher()
                val testScope = TestScope(testDispatcher)

                val variableManager = makeVariableManager()

                val received = mutableListOf<com.opencreativeplus.api.model.VariableChange>()
                val collectJob = testScope.launch {
                    variableManager.changes(plotId).take(1).toList(received)
                }

                testScope.launch {
                    variableManager.emitChange(plotId, varName, varValue, VariableScopeType.SAVED)
                }

                testScope.advanceUntilIdle()
                collectJob.join()

                received.size shouldBe 1
                received[0].name shouldBe varName
                received[0].scope shouldBe VariableScopeType.SAVED
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 5c: subscribeToVariableChanges causes EventDispatcher to dispatch
    //              "variable_change" with correct variable_name
    // -----------------------------------------------------------------------

    "Property 5c: subscribeToVariableChanges dispatches variable_change with correct variable_name" - {
        // **Validates: Requirements 5.3, 5.4**
        "for any plotId, varName, varValue: dispatcher receives variable_change with variable_name == varName" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid(),
                Arb.string(1..32),
                Arb.string(),
                Arb.element(listOf(VariableScopeType.PLOT, VariableScopeType.SAVED))
            ) { plotId, varName, varValue, scope ->
                val testDispatcher = StandardTestDispatcher()
                val testScope = TestScope(testDispatcher)

                val variableManager = makeVariableManager()
                val engine = mockk<ExecutionEngine>(relaxed = true)
                val dispatcher = EventDispatcher(engine, testScope)

                // Register a script that listens to "variable_change" so dispatchEvent actually calls executeScript
                dispatcher.registerScripts(plotId, listOf(makeScript("variable_change")))

                // Subscribe to variable changes
                dispatcher.subscribeToVariableChanges(plotId, variableManager, testScope)

                // Emit a change
                testScope.launch {
                    variableManager.emitChange(plotId, varName, varValue, scope)
                }

                testScope.advanceUntilIdle()

                // Verify executeScript was called with eventData containing "variable_name" to varName
                coVerify(exactly = 1) {
                    engine.executeScript(
                        any(),
                        plotId,
                        null,
                        match { it["variable_name"] == varName }
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 5d: N changes produce exactly N dispatched events (one-to-one)
    // -----------------------------------------------------------------------

    "Property 5d: N changes produce exactly N dispatched variable_change events" - {
        // **Validates: Requirements 5.3, 5.4**
        "for any N in 1..10 and any varName: N emits produce exactly N executeScript calls" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid(),
                Arb.int(1..10),
                Arb.string(1..32),
                Arb.element(listOf(VariableScopeType.PLOT, VariableScopeType.SAVED))
            ) { plotId, n, varName, scope ->
                val testDispatcher = StandardTestDispatcher()
                val testScope = TestScope(testDispatcher)

                val variableManager = makeVariableManager()
                val engine = mockk<ExecutionEngine>(relaxed = true)
                val dispatcher = EventDispatcher(engine, testScope)

                // Register a script that listens to "variable_change" so dispatchEvent actually calls executeScript
                dispatcher.registerScripts(plotId, listOf(makeScript("variable_change")))

                dispatcher.subscribeToVariableChanges(plotId, variableManager, testScope)

                // Emit N changes
                testScope.launch {
                    repeat(n) { i ->
                        variableManager.emitChange(plotId, "$varName$i", "value$i", scope)
                    }
                }

                testScope.advanceUntilIdle()

                coVerify(exactly = n) {
                    engine.executeScript(any(), plotId, null, any())
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 5e: changes for a different plotId are NOT received by the subscriber
    // -----------------------------------------------------------------------

    "Property 5e: changes for a different plotId do not trigger the subscriber" - {
        // **Validates: Requirements 5.2**
        "emitChange for plotB does not produce events in changes(plotA)" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid(),
                Arb.uuid(),
                Arb.string(1..32)
            ) { plotA, plotB, varName ->
                // Skip if UUIDs happen to be equal
                if (plotA == plotB) return@checkAll

                val testDispatcher = StandardTestDispatcher()
                val testScope = TestScope(testDispatcher)

                val variableManager = makeVariableManager()
                val engine = mockk<ExecutionEngine>(relaxed = true)
                val dispatcher = EventDispatcher(engine, testScope)

                // Subscribe only for plotA
                dispatcher.subscribeToVariableChanges(plotA, variableManager, testScope)

                // Emit change for plotB
                testScope.launch {
                    variableManager.emitChange(plotB, varName, "value", VariableScopeType.PLOT)
                }

                testScope.advanceUntilIdle()

                // No events should be dispatched for plotA
                coVerify(exactly = 0) {
                    engine.executeScript(any(), plotA, null, any())
                }
            }
        }
    }
})
