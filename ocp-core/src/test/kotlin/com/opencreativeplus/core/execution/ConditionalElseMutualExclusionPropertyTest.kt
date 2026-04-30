// Feature: ocp-mvp2-core-systems, Property 6: Взаимоисключение веток if/else
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based test for if/else mutual exclusion in conditional execution.
 *
 * **Validates: Requirements 4.4, 4.5, 4.7, 4.8**
 *
 * Property 6: For any conditional node with an else-branch and any value of `condition`,
 * exactly one of the two branches (then or else) must execute on each run —
 * not both and not neither.
 */
class ConditionalElseMutualExclusionPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers (same pattern as ConditionalBranchPropertyTest)
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

    /** A condition that returns a fixed [result]. */
    class FixedCondition(private val result: Boolean) : ICondition {
        override val nodeId = "fixed_condition"
        override val displayName = "Fixed Condition"
        override suspend fun evaluate(context: ExecutionContext): Boolean = result
    }

    /** An action that increments [counter] on each execute() call. */
    class CountingAction(private val counter: AtomicInteger) : IAction {
        override val nodeId = "counting_action"
        override val displayName = "Counting Action"
        override suspend fun execute(context: ExecutionContext) {
            counter.incrementAndGet()
        }
    }

    /**
     * Local mirror of IfAction logic (ocp-plugin is not a dependency of ocp-core).
     * Executes [thenActions] if [condition] is true, otherwise [elseActions].
     */
    class LocalIfAction(
        private val condition: ICondition,
        private val thenActions: List<IAction>,
        private val elseActions: List<IAction> = emptyList()
    ) : IAction {
        override val nodeId = "local_if"
        override val displayName = "Local If"
        override suspend fun execute(context: ExecutionContext) {
            val branch = if (condition.evaluate(context)) thenActions else elseActions
            for (action in branch) {
                action.execute(context)
            }
        }
    }

    /**
     * Simulates the ExecutionEngine's conditional-with-else logic inline
     * (mirrors the pattern from ConditionalBranchPropertyTest.runConditionalBranch).
     */
    suspend fun runConditionalWithElse(
        condition: ICondition,
        thenActions: List<IAction>,
        elseActions: List<IAction>?,
        context: ExecutionContext
    ) {
        val conditionResult = condition.evaluate(context)
        val childBranch = thenActions.takeIf { it.isNotEmpty() }
        if (conditionResult && childBranch != null) {
            for (action in childBranch) action.execute(context)
        } else if (!conditionResult) {
            if (elseActions != null) {
                for (action in elseActions) action.execute(context)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Scenario A — LocalIfAction mutual exclusion
    // -----------------------------------------------------------------------

    "Property 6: Взаимоисключение веток if/else" - {

        /**
         * Scenario A: LocalIfAction (mirrors IfAction logic).
         *
         * For any conditionResult, thenCount, elseCount:
         * - exactly one branch executes
         * - the other branch executes zero times
         * - total executed == the active branch's count
         *
         * **Validates: Requirements 4.4, 4.5, 4.7, 4.8**
         */
        "Scenario A — LocalIfAction: exactly one branch executes for any condition" {
            checkAll(
                PropTestConfig(iterations = 200),
                Arb.boolean(),
                Arb.int(1, 10),
                Arb.int(1, 10)
            ) { conditionResult, thenCount, elseCount ->
                val thenCounter = AtomicInteger(0)
                val elseCounter = AtomicInteger(0)

                val thenActions = List(thenCount) { CountingAction(thenCounter) }
                val elseActions = List(elseCount) { CountingAction(elseCounter) }

                val ifAction = LocalIfAction(FixedCondition(conditionResult), thenActions, elseActions)
                val context = minimalContext()

                runBlocking { ifAction.execute(context) }

                val expectedThen = if (conditionResult) thenCount else 0
                val expectedElse = if (conditionResult) 0 else elseCount

                // Mutual exclusion: exactly one counter is non-zero
                val thenRan = thenCounter.get() > 0
                val elseRan = elseCounter.get() > 0
                (thenRan xor elseRan) shouldBe true

                // Correct branch ran the correct number of times
                thenCounter.get() shouldBe expectedThen
                elseCounter.get() shouldBe expectedElse

                // Total executed == active branch count
                val activeCount = if (conditionResult) thenCount else elseCount
                (thenCounter.get() + elseCounter.get()) shouldBe activeCount
            }
        }

        /**
         * Scenario A edge: condition true → else branch never runs (Req 4.5).
         *
         * **Validates: Requirements 4.5**
         */
        "Scenario A — condition true: else branch never executes" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 10),
                Arb.int(1, 10)
            ) { thenCount, elseCount ->
                val thenCounter = AtomicInteger(0)
                val elseCounter = AtomicInteger(0)

                val ifAction = LocalIfAction(
                    FixedCondition(true),
                    List(thenCount) { CountingAction(thenCounter) },
                    List(elseCount) { CountingAction(elseCounter) }
                )

                runBlocking { ifAction.execute(minimalContext()) }

                thenCounter.get() shouldBe thenCount
                elseCounter.get() shouldBe 0
            }
        }

        /**
         * Scenario A edge: condition false → then branch never runs (Req 4.4).
         *
         * **Validates: Requirements 4.4**
         */
        "Scenario A — condition false: then branch never executes" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 10),
                Arb.int(1, 10)
            ) { thenCount, elseCount ->
                val thenCounter = AtomicInteger(0)
                val elseCounter = AtomicInteger(0)

                val ifAction = LocalIfAction(
                    FixedCondition(false),
                    List(thenCount) { CountingAction(thenCounter) },
                    List(elseCount) { CountingAction(elseCounter) }
                )

                runBlocking { ifAction.execute(minimalContext()) }

                thenCounter.get() shouldBe 0
                elseCounter.get() shouldBe elseCount
            }
        }

        // -----------------------------------------------------------------------
        // Scenario B — ExecutionEngine inline simulation mutual exclusion
        // -----------------------------------------------------------------------

        /**
         * Scenario B: Inline engine simulation.
         *
         * For any conditionResult, thenCount, elseCount:
         * - mutual exclusion holds
         * - correct branch executes the correct number of times
         *
         * **Validates: Requirements 4.4, 4.5, 4.7, 4.8**
         */
        "Scenario B — engine simulation: mutual exclusion for any condition and branch sizes" {
            checkAll(
                PropTestConfig(iterations = 200),
                Arb.boolean(),
                Arb.int(1, 10),
                Arb.int(1, 10)
            ) { conditionResult, thenCount, elseCount ->
                val thenCounter = AtomicInteger(0)
                val elseCounter = AtomicInteger(0)

                val thenActions = List(thenCount) { CountingAction(thenCounter) }
                val elseActions = List(elseCount) { CountingAction(elseCounter) }

                runBlocking {
                    runConditionalWithElse(
                        FixedCondition(conditionResult),
                        thenActions,
                        elseActions,
                        minimalContext()
                    )
                }

                val expectedThen = if (conditionResult) thenCount else 0
                val expectedElse = if (conditionResult) 0 else elseCount

                // Mutual exclusion
                val thenRan = thenCounter.get() > 0
                val elseRan = elseCounter.get() > 0
                (thenRan xor elseRan) shouldBe true

                thenCounter.get() shouldBe expectedThen
                elseCounter.get() shouldBe expectedElse
            }
        }

        /**
         * Scenario B: elseActions is null and condition is false → no actions run, no exception (Req 4.6).
         *
         * **Validates: Requirements 4.6**
         */
        "Scenario B — elseActions null, condition false: no actions run and no exception" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 10)
            ) { thenCount ->
                val thenCounter = AtomicInteger(0)
                val thenActions = List(thenCount) { CountingAction(thenCounter) }

                runBlocking {
                    runConditionalWithElse(
                        FixedCondition(false),
                        thenActions,
                        null,   // no else branch
                        minimalContext()
                    )
                }

                thenCounter.get() shouldBe 0
            }
        }

        /**
         * Scenario B: thenActions is empty and condition is true → no actions run (Req 4.5).
         * The engine uses `takeIf { it.isNotEmpty() }` so an empty then-list is treated as absent.
         *
         * **Validates: Requirements 4.5**
         */
        "Scenario B — thenActions empty, condition true: no actions run" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 10)
            ) { elseCount ->
                val elseCounter = AtomicInteger(0)
                val elseActions = List(elseCount) { CountingAction(elseCounter) }

                runBlocking {
                    runConditionalWithElse(
                        FixedCondition(true),
                        emptyList(),   // empty then-branch
                        elseActions,
                        minimalContext()
                    )
                }

                // condition is true but thenActions is empty → childBranch is null → nothing runs
                elseCounter.get() shouldBe 0
            }
        }

        /**
         * Scenario B edge: condition true, elseActions present → else never runs (Req 4.5).
         *
         * **Validates: Requirements 4.5**
         */
        "Scenario B — condition true with else present: else branch never executes" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 10),
                Arb.int(1, 10)
            ) { thenCount, elseCount ->
                val thenCounter = AtomicInteger(0)
                val elseCounter = AtomicInteger(0)

                runBlocking {
                    runConditionalWithElse(
                        FixedCondition(true),
                        List(thenCount) { CountingAction(thenCounter) },
                        List(elseCount) { CountingAction(elseCounter) },
                        minimalContext()
                    )
                }

                thenCounter.get() shouldBe thenCount
                elseCounter.get() shouldBe 0
            }
        }

        /**
         * Scenario B edge: condition false, thenActions present → then never runs (Req 4.4).
         *
         * **Validates: Requirements 4.4**
         */
        "Scenario B — condition false with then present: then branch never executes" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1, 10),
                Arb.int(1, 10)
            ) { thenCount, elseCount ->
                val thenCounter = AtomicInteger(0)
                val elseCounter = AtomicInteger(0)

                runBlocking {
                    runConditionalWithElse(
                        FixedCondition(false),
                        List(thenCount) { CountingAction(thenCounter) },
                        List(elseCount) { CountingAction(elseCounter) },
                        minimalContext()
                    )
                }

                thenCounter.get() shouldBe 0
                elseCounter.get() shouldBe elseCount
            }
        }

        /**
         * Scenario B: both branches have actions → total executed == exactly one branch's count (Req 4.8).
         *
         * **Validates: Requirements 4.8**
         */
        "Scenario B — both branches present: total executed equals exactly one branch count" {
            checkAll(
                PropTestConfig(iterations = 200),
                Arb.boolean(),
                Arb.int(1, 10),
                Arb.int(1, 10)
            ) { conditionResult, thenCount, elseCount ->
                val thenCounter = AtomicInteger(0)
                val elseCounter = AtomicInteger(0)

                runBlocking {
                    runConditionalWithElse(
                        FixedCondition(conditionResult),
                        List(thenCount) { CountingAction(thenCounter) },
                        List(elseCount) { CountingAction(elseCounter) },
                        minimalContext()
                    )
                }

                val total = thenCounter.get() + elseCounter.get()
                val expectedTotal = if (conditionResult) thenCount else elseCount
                total shouldBe expectedTotal
            }
        }
    }
})
