@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.compiler

// Feature: ocp-plugin-fixes-and-completions, Property 2: loop operation count

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.plugin.node.loop.ForEachNode
import com.opencreativeplus.plugin.node.loop.RepeatNode
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property 2: Счётчик операций цикла
 *
 * For any ForEachNode or RepeatNode with a body of N actions and K iterations
 * (K ≤ MAX_ITERATIONS), the total number of execute() calls on body actions
 * must be exactly N × K.
 *
 * Validates: Requirements 2.5, 2.6
 */
class ASTCompilerLoopBodyPropertyTest : FreeSpec({

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
    fun buildContext(items: List<Any> = emptyList(), count: Int = 0): ExecutionContext {
        val localScope = mapScope(
            buildMap {
                if (items.isNotEmpty()) put("items", items)
                if (count > 0) put("count", count)
            }
        )
        return object : ExecutionContext {
            override val plotId: UUID = UUID.randomUUID()
            override val player: Player? = null
            override val eventData: Map<String, Any> = emptyMap()
            override val localScope: VariableScope = localScope
            override val plotScope: VariableScope = mapScope()
            override val savedScope: VariableScope = mapScope()
            override val operationCount: AtomicInteger = AtomicInteger(0)
            override val callStackSize: AtomicInteger = AtomicInteger(0)
            override val targets: MutableList<Entity> = mutableListOf()
            override var currentTarget: Entity? = null
            override suspend fun <T> syncContext(block: () -> T): T = block()
        }
    }

    /**
     * IAction that increments a shared AtomicInteger counter on each execution.
     * Used to count the total number of body-action invocations.
     */
    class CountingAction(private val counter: AtomicInteger) : IAction {
        override val nodeId = "counting"
        override val displayName = "Counting"
        override suspend fun execute(context: ExecutionContext) {
            counter.incrementAndGet()
        }
    }

    // -----------------------------------------------------------------------
    // Property 2a: ForEachNode executes exactly N × K actions (Req 2.5)
    // -----------------------------------------------------------------------

    "Property 2a: ForEachNode with N body actions and K list items executes exactly N×K actions (Req 2.5)" - {
        // Validates: Requirement 2.5
        // For any N body actions (1..10) and K list items (1..20),
        // ForEachNode must invoke each body action exactly K times → total N×K calls.
        "total execute() calls equals N * K" {
            checkAll(
                PropTestConfig(iterations = 30),
                Arb.int(1..10),
                Arb.int(1..10)
            ) { n, k ->
                val counter = AtomicInteger(0)
                val body = List(n) { CountingAction(counter) }
                val node = ForEachNode(mapOf("list" to "items", "body" to body))
                val ctx = buildContext(items = List(k) { it })
                node.execute(ctx)
                counter.get() shouldBe n * k
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 2b: ForEachNode with empty body executes 0 actions (Req 2.5)
    // -----------------------------------------------------------------------

    "Property 2b: ForEachNode with empty body executes 0 actions regardless of list size (Req 2.5)" - {
        // Validates: Requirement 2.5 (edge case: N=0)
        "zero body actions → zero total calls" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(0..10)
            ) { k ->
                val counter = AtomicInteger(0)
                val node = ForEachNode(mapOf("list" to "items", "body" to emptyList<IAction>()))
                val ctx = buildContext(items = List(k) { it })
                node.execute(ctx)
                counter.get() shouldBe 0
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 2c: RepeatNode executes exactly N × K actions (Req 2.6)
    // -----------------------------------------------------------------------

    "Property 2c: RepeatNode with N body actions and K repetitions executes exactly N×K actions (Req 2.6)" - {
        // Validates: Requirement 2.6
        // For any N body actions (1..10) and K repetitions (1..20),
        // RepeatNode must invoke each body action exactly K times → total N×K calls.
        "total execute() calls equals N * K" {
            checkAll(
                PropTestConfig(iterations = 30),
                Arb.int(1..10),
                Arb.int(1..10)
            ) { n, k ->
                val counter = AtomicInteger(0)
                val body = List(n) { CountingAction(counter) }
                val node = RepeatNode(mapOf("count" to k, "body" to body))
                val ctx = buildContext()
                node.execute(ctx)
                counter.get() shouldBe n * k
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 2d: RepeatNode with empty body executes 0 actions (Req 2.6)
    // -----------------------------------------------------------------------

    "Property 2d: RepeatNode with empty body executes 0 actions regardless of count (Req 2.6)" - {
        // Validates: Requirement 2.6 (edge case: N=0)
        "zero body actions → zero total calls" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(0..10)
            ) { k ->
                val counter = AtomicInteger(0)
                val node = RepeatNode(mapOf("count" to k, "body" to emptyList<IAction>()))
                val ctx = buildContext()
                node.execute(ctx)
                counter.get() shouldBe 0
            }
        }
    }
})
