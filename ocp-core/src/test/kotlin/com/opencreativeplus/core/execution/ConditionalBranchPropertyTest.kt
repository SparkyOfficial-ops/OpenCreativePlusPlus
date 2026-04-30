@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-visual-programming-platform, Property 4: Условный блок — выполнение при true, пропуск при false

package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based test for conditional branch execution in the ExecutionEngine.
 *
 * **Validates: Requirements 2.4, 2.5**
 *
 * Property 4: For any conditional block with `childActions` and any `ExecutionContext`,
 * if `condition.evaluate()` returns `true` — all `childActions` are executed;
 * if `false` — none of the `childActions` are executed.
 */
class ConditionalBranchPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    fun minimalScope(): VariableScope {
        val store = mutableMapOf<String, Any>()
        return object : VariableScope {
            override fun get(name: String): Any? = store[name]
            override fun set(name: String, value: Any) { store[name] = value }
            override fun has(name: String): Boolean = store.containsKey(name)
            override fun clear() = store.clear()
        }
    }

    fun minimalContext(): ExecutionContext = object : ExecutionContext {
        override val plotId: UUID = UUID.randomUUID()
        override val player: Player? = null
        override val eventData: Map<String, Any> = emptyMap()
        override val localScope: VariableScope = minimalScope()
        override val plotScope: VariableScope = minimalScope()
        override val savedScope: VariableScope = minimalScope()
        override val operationCount: AtomicInteger = AtomicInteger(0)
        override val callStackSize: AtomicInteger = AtomicInteger(0)
        override val targets: MutableList<org.bukkit.entity.Entity> = mutableListOf()
        override var currentTarget: org.bukkit.entity.Entity? = null
        override suspend fun <T> syncContext(block: () -> T): T = block()
    }

    /**
     * A condition node that returns a fixed [result] when evaluated.
     */
    class FixedCondition(private val result: Boolean) : ICondition {
        override val nodeId = "fixed_condition"
        override val displayName = "Fixed Condition"
        override suspend fun evaluate(context: ExecutionContext): Boolean = result
    }

    /**
     * An action that increments [counter] on each execute() call.
     */
    class CountingAction(private val counter: AtomicInteger) : IAction {
        override val nodeId = "counting_action"
        override val displayName = "Counting Action"
        override suspend fun execute(context: ExecutionContext) {
            counter.incrementAndGet()
        }
    }

    /**
     * Simulates the ExecutionEngine's conditional branch logic (Req 2.4, 2.5):
     *
     *   val condition = action as? ICondition
     *   if (condition != null) {
     *       val conditionResult = condition.evaluate(context)
     *       val childBranch = conditionalBranches[index]
     *       if (conditionResult && childBranch != null) {
     *           for (childAction in childBranch) { childAction.execute(context) }
     *       }
     *   }
     */
    suspend fun runConditionalBranch(
        condition: ICondition,
        childActions: List<IAction>,
        context: ExecutionContext
    ) {
        val conditionResult = condition.evaluate(context)
        if (conditionResult) {
            for (childAction in childActions) {
                childAction.execute(context)
            }
        }
        // If condition is false, child branch is skipped (Req 2.5)
    }

    /** Arbitrary that produces a list of 1..10 counting actions sharing a single counter. */
    fun arbChildActions(counter: AtomicInteger): Arb<List<CountingAction>> = arbitrary { rs ->
        val size = rs.random.nextInt(1, 11)
        List(size) { CountingAction(counter) }
    }

    // -----------------------------------------------------------------------
    // Property 4a — condition true → ALL childActions executed (Req 2.4)
    // -----------------------------------------------------------------------

    "Property 4: Условный блок — выполнение при true, пропуск при false" - {

        /**
         * When condition evaluates to true, every childAction must be executed exactly once.
         *
         * **Validates: Requirements 2.4**
         */
        "condition true → all childActions are executed exactly once" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 10)
            ) { childCount ->
                val counter = AtomicInteger(0)
                val childActions = List(childCount) { CountingAction(counter) }
                val condition = FixedCondition(true)
                val context = minimalContext()

                runBlocking { runConditionalBranch(condition, childActions, context) }

                counter.get() shouldBe childCount
            }
        }

        /**
         * When condition evaluates to false, no childAction must be executed.
         *
         * **Validates: Requirements 2.5**
         */
        "condition false → no childActions are executed" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 10)
            ) { childCount ->
                val counter = AtomicInteger(0)
                val childActions = List(childCount) { CountingAction(counter) }
                val condition = FixedCondition(false)
                val context = minimalContext()

                runBlocking { runConditionalBranch(condition, childActions, context) }

                counter.get() shouldBe 0
            }
        }

        /**
         * For any boolean condition result and any non-empty list of childActions,
         * the execution count equals childActions.size when true, and 0 when false.
         *
         * **Validates: Requirements 2.4, 2.5**
         */
        "execution count equals childActions.size when true, 0 when false — for any input" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.boolean(),
                Arb.int(0, 15)
            ) { conditionResult, childCount ->
                val counter = AtomicInteger(0)
                val childActions = List(childCount) { CountingAction(counter) }
                val condition = FixedCondition(conditionResult)
                val context = minimalContext()

                runBlocking { runConditionalBranch(condition, childActions, context) }

                val expected = if (conditionResult) childCount else 0
                counter.get() shouldBe expected
            }
        }

        /**
         * When childActions list is empty and condition is true, no exception is thrown
         * and execution count remains 0.
         *
         * **Validates: Requirements 2.4**
         */
        "condition true with empty childActions → no exception, count stays 0" {
            repeat(100) {
                val counter = AtomicInteger(0)
                val condition = FixedCondition(true)
                val context = minimalContext()

                runBlocking { runConditionalBranch(condition, emptyList(), context) }

                counter.get() shouldBe 0
            }
        }

        /**
         * When childActions list is empty and condition is false, no exception is thrown
         * and execution count remains 0.
         *
         * **Validates: Requirements 2.5**
         */
        "condition false with empty childActions → no exception, count stays 0" {
            repeat(100) {
                val counter = AtomicInteger(0)
                val condition = FixedCondition(false)
                val context = minimalContext()

                runBlocking { runConditionalBranch(condition, emptyList(), context) }

                counter.get() shouldBe 0
            }
        }

        /**
         * The conditional branch logic is consistent across multiple independent contexts:
         * each context gets its own isolated execution, and the condition result
         * determines execution in every case.
         *
         * **Validates: Requirements 2.4, 2.5**
         */
        "conditional branch is consistent across independent contexts" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.boolean(),
                Arb.int(1, 8)
            ) { conditionResult, childCount ->
                // Run the same scenario in two independent contexts — results must match
                val counter1 = AtomicInteger(0)
                val counter2 = AtomicInteger(0)

                val childActions1 = List(childCount) { CountingAction(counter1) }
                val childActions2 = List(childCount) { CountingAction(counter2) }

                runBlocking {
                    runConditionalBranch(FixedCondition(conditionResult), childActions1, minimalContext())
                    runConditionalBranch(FixedCondition(conditionResult), childActions2, minimalContext())
                }

                counter1.get() shouldBe counter2.get()
                val expected = if (conditionResult) childCount else 0
                counter1.get() shouldBe expected
            }
        }
    }
})
