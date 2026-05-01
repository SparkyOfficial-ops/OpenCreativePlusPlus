package com.opencreativeplus.plugin.node.random

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.node.IAction
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Unit tests for [RandomActionNode].
 *
 * Covers specific examples and edge cases:
 *   - Empty branches → execute() is a no-op
 *   - Single valid branch → always executed
 *   - Single branch with zero weight → no-op (excluded)
 *   - WatchdogException from child → propagates
 *   - RuntimeException from child → propagates
 *   - Seeded Random produces deterministic selection
 *
 * Requirements: 1.2, 1.4, 3.4, 6.2, 6.3
 */
class RandomActionNodeTest : FreeSpec({

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
        override val targets: MutableList<Entity> = mutableListOf()
        override var currentTarget: Entity? = null
        override suspend fun <T> syncContext(block: () -> T): T = block()
    }

    /** Action that increments a counter when executed. */
    class CountingAction(val count: AtomicInteger = AtomicInteger(0)) : IAction {
        override val nodeId = "counting"
        override val displayName = "Counting"
        override suspend fun execute(context: ExecutionContext) { count.incrementAndGet() }
    }

    /** Action that always throws the given exception. */
    class ThrowingAction(private val ex: Throwable) : IAction {
        override val nodeId = "throwing"
        override val displayName = "Throwing"
        override suspend fun execute(context: ExecutionContext) { throw ex }
    }

    // -----------------------------------------------------------------------
    // Empty branches
    // -----------------------------------------------------------------------

    "empty branches — execute() is a no-op, no exception (Req 1.2)" {
        val node = RandomActionNode(emptyMap())
        val ctx = makeContext()
        // Should complete without throwing
        node.execute(ctx)
        // No side effects
        ctx.operationCount.get() shouldBe 0
    }

    "branches param absent — execute() is a no-op (Req 1.2)" {
        val node = RandomActionNode(mapOf("branches" to emptyList<WeightedAction>()))
        val ctx = makeContext()
        node.execute(ctx)
        ctx.operationCount.get() shouldBe 0
    }

    // -----------------------------------------------------------------------
    // Single valid branch
    // -----------------------------------------------------------------------

    "single valid branch — always executed (Req 3.4)" {
        val counter = CountingAction()
        val branch = WeightedAction(counter, 1.0)
        val node = RandomActionNode(mapOf("branches" to listOf(branch)))
        val ctx = makeContext()

        repeat(10) { node.execute(ctx) }

        counter.count.get() shouldBe 10
    }

    // -----------------------------------------------------------------------
    // Zero-weight branch exclusion
    // -----------------------------------------------------------------------

    "single branch with zero weight — excluded, execute() is a no-op (Req 1.4)" {
        val counter = CountingAction()
        val branch = WeightedAction(counter, 0.0)
        val node = RandomActionNode(mapOf("branches" to listOf(branch)))
        val ctx = makeContext()

        node.execute(ctx)

        counter.count.get() shouldBe 0
    }

    "single branch with negative weight — excluded, execute() is a no-op (Req 1.4)" {
        val counter = CountingAction()
        val branch = WeightedAction(counter, -5.0)
        val node = RandomActionNode(mapOf("branches" to listOf(branch)))
        val ctx = makeContext()

        node.execute(ctx)

        counter.count.get() shouldBe 0
    }

    "mix of positive and zero-weight branches — zero-weight branch never executed (Req 1.4)" {
        val goodCounter = CountingAction()
        val badCounter = CountingAction()
        val branches = listOf(
            WeightedAction(goodCounter, 1.0),
            WeightedAction(badCounter, 0.0)
        )
        val node = RandomActionNode(mapOf("branches" to branches))
        val ctx = makeContext()

        repeat(50) { node.execute(ctx) }

        goodCounter.count.get() shouldBe 50
        badCounter.count.get() shouldBe 0
    }

    // -----------------------------------------------------------------------
    // Exception propagation
    // -----------------------------------------------------------------------

    "RuntimeException from child propagates unchanged (Req 6.3)" {
        val ex = RuntimeException("test error")
        val branch = WeightedAction(ThrowingAction(ex), 1.0)
        val node = RandomActionNode(mapOf("branches" to listOf(branch)))
        val ctx = makeContext()

        val thrown = shouldThrow<RuntimeException> { node.execute(ctx) }
        thrown shouldBe ex
    }

    "custom WatchdogException from child propagates unchanged (Req 6.2)" {
        // Simulate a WatchdogException-like RuntimeException
        class WatchdogException(msg: String) : RuntimeException(msg)

        val ex = WatchdogException("watchdog limit exceeded")
        val branch = WeightedAction(ThrowingAction(ex), 1.0)
        val node = RandomActionNode(mapOf("branches" to listOf(branch)))
        val ctx = makeContext()

        val thrown = shouldThrow<WatchdogException> { node.execute(ctx) }
        thrown shouldBe ex
    }

    "IllegalStateException from child propagates unchanged (Req 6.3)" {
        val ex = IllegalStateException("state error")
        val branch = WeightedAction(ThrowingAction(ex), 1.0)
        val node = RandomActionNode(mapOf("branches" to listOf(branch)))
        val ctx = makeContext()

        val thrown = shouldThrow<IllegalStateException> { node.execute(ctx) }
        thrown shouldBe ex
    }

    // -----------------------------------------------------------------------
    // Deterministic selection with seeded Random
    // -----------------------------------------------------------------------

    "seeded Random produces deterministic selection across repeated calls (Req 3.5)" {
        val counters = Array(3) { CountingAction() }
        val branches = counters.mapIndexed { i, c -> WeightedAction(c, (i + 1).toDouble()) }

        // Run twice with the same seed — results must be identical
        val seed = 42L

        val node1 = RandomActionNode(mapOf("branches" to branches), Random(seed))
        val ctx1 = makeContext()
        repeat(30) { node1.execute(ctx1) }
        val counts1 = counters.map { it.count.get() }

        // Reset counters
        counters.forEach { it.count.set(0) }

        val node2 = RandomActionNode(mapOf("branches" to branches), Random(seed))
        val ctx2 = makeContext()
        repeat(30) { node2.execute(ctx2) }
        val counts2 = counters.map { it.count.get() }

        counts1 shouldBe counts2
    }

    // -----------------------------------------------------------------------
    // Int weight conversion
    // -----------------------------------------------------------------------

    "Int weights are accepted and converted to Double (Req 1.3)" {
        val counter = CountingAction()
        // Pass weight as Int via Map form
        val rawBranch = mapOf("action" to counter, "weight" to 2)
        val node = RandomActionNode(mapOf("branches" to listOf(rawBranch)))
        val ctx = makeContext()

        // Should not throw and should execute the branch
        repeat(5) { node.execute(ctx) }
        counter.count.get() shouldBe 5
    }

    // -----------------------------------------------------------------------
    // Branch cap at 64
    // -----------------------------------------------------------------------

    "branches list with more than 64 entries — only first 64 used (Req 1.5, 1.6)" {
        val counters = Array(100) { CountingAction() }
        val branches = counters.map { WeightedAction(it, 1.0) }
        val node = RandomActionNode(mapOf("branches" to branches))

        // Only first 64 should be in the node
        node.branches.size shouldBe 64

        val ctx = makeContext()
        repeat(200) { node.execute(ctx) }

        // Branches at index >= 64 should never be executed
        for (i in 64 until 100) {
            counters[i].count.get() shouldBe 0
        }
    }

    // -----------------------------------------------------------------------
    // getParams round-trip
    // -----------------------------------------------------------------------

    "getParams returns branches as List<Map<String, Any>> with action and weight keys" {
        val counter = CountingAction()
        val branch = WeightedAction(counter, 2.5)
        val node = RandomActionNode(mapOf("branches" to listOf(branch)))

        val params = node.getParams()
        @Suppress("UNCHECKED_CAST")
        val branchList = params["branches"] as List<Map<String, Any>>

        branchList.size shouldBe 1
        branchList[0]["action"] shouldBe counter
        branchList[0]["weight"] shouldBe 2.5
    }
})
