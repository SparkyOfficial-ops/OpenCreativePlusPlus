@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.node.condition

// Feature: ocp-gameplay-systems, Property 11: OrCondition semantics

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.node.ICondition
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property 11: OrCondition корректная семантика
 *
 * For any list of conditions, `OrConditionNode.evaluate()` must return `true`
 * if and only if AT LEAST ONE condition is true; upon the first true condition,
 * remaining conditions must NOT be evaluated (short-circuit).
 *
 * **Validates: Requirements 7.2, 7.3, 7.4**
 */
class OrConditionSemanticPropertyTest : FreeSpec({

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
        override suspend fun <T> syncContext(block: () -> T): T = block()
    }

    /** A condition that returns a fixed [result] and records whether it was evaluated. */
    class TrackingCondition(private val result: Boolean) : ICondition {
        override val nodeId = "tracking"
        override val displayName = "Tracking"
        var evaluationCount: Int = 0
            private set

        override suspend fun evaluate(context: ExecutionContext): Boolean {
            evaluationCount++
            return result
        }
    }

    // -----------------------------------------------------------------------
    // Property 11a: OR semantics — true iff at least one child is true (Req 7.2, 7.3, 7.4)
    // -----------------------------------------------------------------------

    "Property 11a: OrConditionNode returns true iff at least one child condition is true" - {
        // **Validates: Requirements 7.2, 7.3, 7.4**
        // For any list of boolean values, OrConditionNode.evaluate() must equal
        // the logical OR of all values (i.e., at least one must be true).
        "result equals disjunction of all child results" {
            checkAll(
                PropTestConfig(iterations = 500),
                Arb.list(Arb.boolean(), 0..20)
            ) { booleans ->
                val ctx = minimalContext()
                val conditions = booleans.map { TrackingCondition(it) }
                val node = OrConditionNode(conditions)

                val result = node.evaluate(ctx)
                val expected = booleans.any { it }

                result shouldBe expected
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 11b: Short-circuit — stops at first true (Req 7.3)
    // -----------------------------------------------------------------------

    "Property 11b: OrConditionNode stops evaluating after the first true condition" - {
        // **Validates: Requirements 7.3**
        // When a true condition is encountered at index i, conditions at indices > i
        // must NOT be evaluated (short-circuit evaluation).
        "conditions after the first true are never evaluated" {
            checkAll(
                PropTestConfig(iterations = 500),
                Arb.list(Arb.boolean(), 1..20)
            ) { booleans ->
                val ctx = minimalContext()
                val conditions = booleans.map { TrackingCondition(it) }
                val node = OrConditionNode(conditions)

                node.evaluate(ctx)

                val firstTrueIndex = booleans.indexOfFirst { it }
                if (firstTrueIndex == -1) {
                    // All false — every condition must have been evaluated exactly once
                    conditions.forEach { it.evaluationCount shouldBe 1 }
                } else {
                    // Conditions up to and including the first true must be evaluated
                    for (i in 0..firstTrueIndex) {
                        conditions[i].evaluationCount shouldBe 1
                    }
                    // Conditions after the first true must NOT be evaluated
                    for (i in (firstTrueIndex + 1) until conditions.size) {
                        conditions[i].evaluationCount shouldBe 0
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 11c: Empty list → false (Req 7.4)
    // -----------------------------------------------------------------------

    "Property 11c: OrConditionNode with empty child list returns false" - {
        // **Validates: Requirements 7.4**
        // An empty OR has no true conditions, so it returns false.
        "empty OrConditionNode evaluates to false" {
            val ctx = minimalContext()
            val node = OrConditionNode(emptyList())
            node.evaluate(ctx) shouldBe false
        }
    }
})
