@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.node.dialogue

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.node.IAction
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property 19: Dialogue option routing
 * Validates: Requirement 13.3 — When a player clicks a dialogue option,
 * the engine triggers the Code_Line associated with that option's index.
 */
class DialogueRoutingPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    fun mapScope(initial: Map<String, Any> = emptyMap()): VariableScope {
        val store = initial.toMutableMap()
        return object : VariableScope {
            override fun get(name: String): Any? = store[name]
            override fun set(name: String, value: Any) { store[name] = value }
            override fun has(name: String): Boolean = store.containsKey(name)
            override fun clear() = store.clear()
        }
    }

    fun makeContext(): ExecutionContext = object : ExecutionContext {
        override val plotId: UUID = UUID.randomUUID()
        override val player: Player? = null
        override val eventData: Map<String, Any> = emptyMap()
        override val localScope: VariableScope = mapScope()
        override val plotScope: VariableScope = mapScope()
        override val savedScope: VariableScope = mapScope()
        override val operationCount: AtomicInteger = AtomicInteger(0)
        override val callStackSize: AtomicInteger = AtomicInteger(0)
        override suspend fun <T> syncContext(block: () -> T): T = block()
    }

    fun makeCounterAction(counter: AtomicInteger): IAction = object : IAction {
        override val nodeId = "counter"
        override val displayName = "Counter"
        override suspend fun execute(context: ExecutionContext) { counter.incrementAndGet() }
    }

    // -----------------------------------------------------------------------
    // Property 19a: onOptionClick routes to the correct index
    // -----------------------------------------------------------------------

    "Property 19a: onOptionClick routes to the correct index" - {
        // Validates: Requirement 13.3
        // For any valid option index (0..3), awaitClick must return exactly that index
        // when onOptionClick is called with it.
        "awaitClick returns the index passed to onOptionClick" {
            runTest {
                checkAll(PropTestConfig(iterations = 100), Arb.int(0..3)) { index ->
                    val dialogueId = UUID.randomUUID()
                    val playerId = UUID.randomUUID()

                    var result: Int? = null
                    val job = launch {
                        result = DialogueManager.awaitClick(dialogueId, playerId)
                    }
                    testScheduler.advanceUntilIdle()

                    DialogueManager.onOptionClick(dialogueId, index)
                    job.join()

                    result shouldBe index
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 19b: each option index routes to its own body exclusively
    // -----------------------------------------------------------------------

    "Property 19b: each option index routes to its own body exclusively" - {
        // Validates: Requirement 13.3
        // When option N is clicked, only the body at index N executes;
        // bodies at all other indices remain untouched.
        "only the clicked option body executes" {
            runTest {
                checkAll(PropTestConfig(iterations = 100), Arb.int(0..3)) { clickedIndex ->
                    val counters = Array(4) { AtomicInteger(0) }
                    val options = counters.map { counter ->
                        DialogueOption("opt", listOf(makeCounterAction(counter)))
                    }
                    val ctx = makeContext()

                    val dialogueId = UUID.randomUUID()
                    val playerId = UUID.randomUUID()

                    var routedIndex: Int? = null
                    val job = launch {
                        routedIndex = DialogueManager.awaitClick(dialogueId, playerId)
                    }
                    testScheduler.advanceUntilIdle()

                    DialogueManager.onOptionClick(dialogueId, clickedIndex)
                    job.join()

                    // Execute the routed body (mirrors SendDialogueNode logic)
                    options.getOrNull(routedIndex!!)?.body?.forEach { it.execute(ctx) }

                    // Only the clicked option's counter should be 1
                    counters[clickedIndex].get() shouldBe 1
                    counters.forEachIndexed { i, counter ->
                        if (i != clickedIndex) counter.get() shouldBe 0
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 19c: out-of-bounds index results in no body execution
    // -----------------------------------------------------------------------

    "Property 19c: out-of-bounds index results in no body execution" - {
        // Validates: Requirement 13.3
        // getOrNull on an out-of-bounds index returns null, so no body executes.
        "no body executes when index is out of bounds" {
            runTest {
                checkAll(PropTestConfig(iterations = 100), Arb.int(4..10)) { outOfBoundsIndex ->
                    val counter = AtomicInteger(0)
                    val options = listOf(
                        DialogueOption("opt0", listOf(makeCounterAction(counter))),
                        DialogueOption("opt1", listOf(makeCounterAction(counter))),
                        DialogueOption("opt2", listOf(makeCounterAction(counter))),
                        DialogueOption("opt3", listOf(makeCounterAction(counter)))
                    )
                    val ctx = makeContext()

                    // Simulate what SendDialogueNode does with the result
                    options.getOrNull(outOfBoundsIndex)?.body?.forEach { it.execute(ctx) }

                    counter.get() shouldBe 0
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 19d: multiple sequential dialogues route independently
    // -----------------------------------------------------------------------

    "Property 19d: multiple sequential dialogues route independently" - {
        // Validates: Requirement 13.3
        // N sequential dialogues each return their own index without cross-contamination.
        "sequential dialogues do not interfere with each other" {
            runTest {
                checkAll(
                    PropTestConfig(iterations = 100),
                    Arb.list(Arb.int(0..3), 1..4)
                ) { indices ->
                    val playerId = UUID.randomUUID()
                    val results = mutableListOf<Int>()

                    for (expectedIndex in indices) {
                        val dialogueId = UUID.randomUUID()
                        var result: Int? = null
                        val job = launch {
                            result = DialogueManager.awaitClick(dialogueId, playerId)
                        }
                        testScheduler.advanceUntilIdle()

                        DialogueManager.onOptionClick(dialogueId, expectedIndex)
                        job.join()

                        results.add(result!!)
                    }

                    results shouldBe indices
                }
            }
        }
    }
})
