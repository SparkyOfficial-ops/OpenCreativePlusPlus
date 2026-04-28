@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.node.loop

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.node.IAction
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property 11: Loop iteration count and variable binding
 * Validates: Requirements 7.1, 7.2, 7.3, 7.4
 *
 * s 7.1: ForEach Loop_Node iterates over a list variable and executes a nested Code_Line for each element
 * s 7.2: Repeat Loop_Node executes a nested Code_Line a specified number of times
 * s 7.3: ForEach sets a loop variable in the local scope to the current element before each iteration
 * s 7.4: Repeat sets a loop index variable in the local scope to the current iteration index (0-based)
 */
class LoopNodePropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Minimal mutable VariableScope backed by a map. */
    fun mapScope(initial: Map<String, Any> = emptyMap()): VariableScope {
        val store = initial.toMutableMap()
        return object : VariableScope {
            override fun get(name: String): Any? = store[name]
            override fun set(name: String, value: Any) { store[name] = value }
            override fun has(name: String): Boolean = store.containsKey(name)
            override fun clear() = store.clear()
        }
    }

    /** Minimal ExecutionContext with a pre-populated localScope. */
    fun contextWithLocalScope(local: Map<String, Any> = emptyMap()): ExecutionContext {
        val localScope = mapScope(local)
        return object : ExecutionContext {
            override val plotId: UUID = UUID.randomUUID()
            override val player: Player? = null
            override val eventData: Map<String, Any> = emptyMap()
            override val localScope: VariableScope = localScope
            override val plotScope: VariableScope = mapScope()
            override val savedScope: VariableScope = mapScope()
            override val operationCount: AtomicInteger = AtomicInteger(0)
            override val callStackSize: AtomicInteger = AtomicInteger(0)
            override val targets: MutableList<org.bukkit.entity.Entity> = mutableListOf()
            override suspend fun <T> syncContext(block: () -> T): T = block()
        }
    }

    /**
     * IAction that records the value of [varName] from localScope at each invocation.
     * Used to verify variable binding per iteration.
     */
    class RecordingAction(
        private val ctx: ExecutionContext,
        private val varName: String,
        val recorded: MutableList<Any?> = mutableListOf()
    ) : IAction {
        override val nodeId = "recording"
        override val displayName = "Recording"
        override suspend fun execute(context: ExecutionContext) {
            recorded.add(context.localScope.get(varName))
        }
    }

    /** Simple counter action — increments a shared counter on each execution. */
    class CountingAction(val count: AtomicInteger = AtomicInteger(0)) : IAction {
        override val nodeId = "counting"
        override val displayName = "Counting"
        override suspend fun execute(context: ExecutionContext) { count.incrementAndGet() }
    }

    // -----------------------------------------------------------------------
    // Property 11a: ForEach iteration count (s 7.1)
    // -----------------------------------------------------------------------

    "Property 11a: ForEachNode executes exactly N iterations for a list of size N (s 7.1)" - {
        // Validates: Requirements 7.1
        // For any list of size N (0..50), ForEachNode must execute the body exactly N times.
        "body is called exactly list.size times" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.string(1..10), 0..50)
            ) { items ->
                val ctx = contextWithLocalScope(mapOf("myList" to items))
                val counter = CountingAction()
                val node = ForEachNode(
                    mapOf(
                        "list" to "myList",
                        "var" to "item",
                        "body" to listOf(counter)
                    )
                )
                node.execute(ctx)
                counter.count.get() shouldBe items.size
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 11b: ForEach variable binding (s 7.3)
    // -----------------------------------------------------------------------

    "Property 11b: ForEachNode sets loop variable to current element at each iteration (s 7.3)" - {
        // Validates: Requirements 7.3
        // For each element in the list, the loop variable in localScope must equal
        // the current element at that iteration.
        "loop variable equals each element in order" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.string(1..10), 1..30)
            ) { items ->
                val ctx = contextWithLocalScope(mapOf("myList" to items))
                val recorder = RecordingAction(ctx, "item")
                val node = ForEachNode(
                    mapOf(
                        "list" to "myList",
                        "var" to "item",
                        "body" to listOf(recorder)
                    )
                )
                node.execute(ctx)
                recorder.recorded shouldBe items
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 11c: Repeat iteration count (s 7.2)
    // -----------------------------------------------------------------------

    "Property 11c: RepeatNode executes exactly N iterations for count N (s 7.2)" - {
        // Validates: Requirements 7.2
        // For any count N (0..1000), RepeatNode must execute the body exactly N times.
        "body is called exactly count times" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(0..1000)
            ) { n ->
                val ctx = contextWithLocalScope()
                val counter = CountingAction()
                val node = RepeatNode(
                    mapOf(
                        "count" to n,
                        "index_var" to "i",
                        "body" to listOf(counter)
                    )
                )
                node.execute(ctx)
                counter.count.get() shouldBe n
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 11d: Repeat index binding (s 7.4)
    // -----------------------------------------------------------------------

    "Property 11d: RepeatNode sets index variable to 0-based iteration index (s 7.4)" - {
        // Validates: Requirements 7.4
        // For each iteration i (0-based), the index variable in localScope must equal i.
        "index variable equals 0, 1, 2, ... N-1 in order" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1..50)
            ) { n ->
                val ctx = contextWithLocalScope()
                val recorder = RecordingAction(ctx, "i")
                val node = RepeatNode(
                    mapOf(
                        "count" to n,
                        "index_var" to "i",
                        "body" to listOf(recorder)
                    )
                )
                node.execute(ctx)
                recorder.recorded shouldBe (0 until n).toList()
            }
        }
    }
})
