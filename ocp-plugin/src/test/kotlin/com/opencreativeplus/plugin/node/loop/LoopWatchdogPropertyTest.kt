@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.node.loop

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.core.watchdog.Watchdog
import com.opencreativeplus.core.watchdog.WatchdogException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
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
 * Property 12: Watchdog counts loop iterations
 * Validates: Requirement 7.5
 *
 * s 7.5: WHILE a Loop_Node is executing, THE Watchdog SHALL count each iteration
 *        as a separate operation toward the operation limit.
 *
 * Each iteration of ForEachNode / RepeatNode increments operationCount by 1.
 * The Watchdog observes this counter via checkExecution and throws WatchdogException
 * when operationCount >= MAX_OPERATIONS.
 */
class LoopWatchdogPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers (mirrors LoopNodePropertyTest)
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

    fun contextWithLocalScope(
        local: Map<String, Any> = emptyMap(),
        startCount: Int = 0
    ): ExecutionContext {
        val localScope = mapScope(local)
        val opCount = AtomicInteger(startCount)
        return object : ExecutionContext {
            override val plotId: UUID = UUID.randomUUID()
            override val player: Player? = null
            override val eventData: Map<String, Any> = emptyMap()
            override val localScope: VariableScope = localScope
            override val plotScope: VariableScope = mapScope()
            override val savedScope: VariableScope = mapScope()
            override val operationCount: AtomicInteger = opCount
            override val callStackSize: AtomicInteger = AtomicInteger(0)
            override val targets: MutableList<org.bukkit.entity.Entity> = mutableListOf()
            override var currentTarget: org.bukkit.entity.Entity? = null
            override suspend fun <T> syncContext(block: () -> T): T = block()
        }
    }

    /** No-op body action — does not touch operationCount. */
    val noopAction = object : IAction {
        override val nodeId = "noop"
        override val displayName = "Noop"
        override suspend fun execute(context: ExecutionContext) {}
    }

    // -----------------------------------------------------------------------
    // Property 12a: ForEachNode increments operationCount once per iteration
    // -----------------------------------------------------------------------

    "Property 12a: ForEachNode increments operationCount exactly once per iteration (s 7.5)" - {
        // For any list of size N, after ForEachNode executes, operationCount == N.
        "operationCount equals list size after ForEach" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.string(1..8), 0..50)
            ) { items ->
                val ctx = contextWithLocalScope(mapOf("items" to items))
                val node = ForEachNode(
                    mapOf(
                        "list" to "items",
                        "var" to "item",
                        "body" to listOf(noopAction)
                    )
                )
                node.execute(ctx)
                ctx.operationCount.get() shouldBe items.size
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 12b: RepeatNode increments operationCount once per iteration
    // -----------------------------------------------------------------------

    "Property 12b: RepeatNode increments operationCount exactly once per iteration (s 7.5)" - {
        // For any count N (0..200), after RepeatNode executes, operationCount == N.
        "operationCount equals count after Repeat" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(0..200)
            ) { n ->
                val ctx = contextWithLocalScope()
                val node = RepeatNode(
                    mapOf(
                        "count" to n,
                        "index_var" to "i",
                        "body" to listOf(noopAction)
                    )
                )
                node.execute(ctx)
                ctx.operationCount.get() shouldBe n
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 12c: operationCount accumulates across nested loops
    // -----------------------------------------------------------------------

    "Property 12c: operationCount accumulates across outer and inner RepeatNode (s 7.5)" - {
        // Outer repeat M times, inner repeat N times → total operationCount == M * N + M
        // (outer increments M times, inner increments M*N times).
        "total operationCount equals outer*inner + outer" {
            checkAll(
                PropTestConfig(iterations = 50),
                Arb.int(1..10),
                Arb.int(1..10)
            ) { outer, inner ->
                val ctx = contextWithLocalScope()

                val innerNode = RepeatNode(
                    mapOf(
                        "count" to inner,
                        "index_var" to "j",
                        "body" to listOf(noopAction)
                    )
                )
                val outerNode = RepeatNode(
                    mapOf(
                        "count" to outer,
                        "index_var" to "i",
                        "body" to listOf(innerNode)
                    )
                )
                outerNode.execute(ctx)
                // outer increments outer times; inner increments outer*inner times
                ctx.operationCount.get() shouldBe (outer + outer * inner)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 12d: Watchdog detects operation limit reached via loop iterations
    // -----------------------------------------------------------------------

    "Property 12d: Watchdog.checkExecution throws WatchdogException when operationCount from loop reaches MAX_OPERATIONS (s 7.5)" - {
        // If we pre-seed operationCount to MAX_OPERATIONS - 1 and run one more iteration,
        // the watchdog check should detect the breach.
        "watchdog throws after operationCount reaches MAX_OPERATIONS" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(1..5)
            ) { extraIterations ->
                // Start just below the limit so that extraIterations pushes us over
                val startCount = Watchdog.MAX_OPERATIONS - 1
                val ctx = contextWithLocalScope(startCount = startCount)

                // Run a RepeatNode with extraIterations — this will push operationCount over the limit
                val node = RepeatNode(
                    mapOf(
                        "count" to extraIterations,
                        "index_var" to "i",
                        "body" to listOf(noopAction)
                    )
                )
                node.execute(ctx)

                // operationCount is now >= MAX_OPERATIONS
                ctx.operationCount.get() shouldBeGreaterThanOrEqual Watchdog.MAX_OPERATIONS

                // The watchdog must detect this
                val fakeTpsMonitor = com.opencreativeplus.core.watchdog.TPSMonitor()
                val watchdog = Watchdog(fakeTpsMonitor)
                shouldThrow<WatchdogException> {
                    watchdog.checkExecution(ctx)
                }
            }
        }
    }
})
