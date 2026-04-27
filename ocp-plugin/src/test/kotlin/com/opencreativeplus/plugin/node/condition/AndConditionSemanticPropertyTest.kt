@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.node.condition

// Feature: ocp-gameplay-systems, Property 10: AndCondition semantics

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
 * Property 10: AndCondition корректная семантика
 *
 * For any list of conditions, `AndConditionNode.evaluate()` must return `true`
 * if and only if ALL conditions are true; upon the first false condition,
 * remaining conditions must NOT be evaluated (short-circuit).
 *
 * **Validates: Requirements 6.2, 6.3, 6.4**
 */
class AndConditionSemanticPropertyTest : FreeSpec({

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
    // Property 10a: AND semantics — true iff all children are true (Req 6.2, 6.3, 6.4)
    // -----------------------------------------------------------------------

    "Property 10a: AndConditionNode returns true iff all child conditions are true" - {
        // **Validates: Requirements 6.2, 6.3**
        // For any list of boolean values, AndConditionNode.evaluate() must equal
        // the logical AND of all values (i.e., all must be true).
        "result equals conjunction of all child results" {
            checkAll(
                PropTestConfig(iterations = 500),
                Arb.list(Arb.boolean(), 0..20)
            ) { booleans ->
                val ctx = minimalContext()
                val conditions = booleans.map { TrackingCondition(it) }
                val node = AndConditionNode(conditions)

                val result = node.evaluate(ctx)
                val expected = booleans.all { it }

                result shouldBe expected
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 10b: Short-circuit — stops at first false (Req 6.4)
    // -----------------------------------------------------------------------

    "Property 10b: AndConditionNode stops evaluating after the first false condition" - {
        // **Validates: Requirements 6.4**
        // When a false condition is encountered at index i, conditions at indices > i
        // must NOT be evaluated (short-circuit evaluation).
        "conditions after the first false are never evaluated" {
            checkAll(
                PropTestConfig(iterations = 500),
                Arb.list(Arb.boolean(), 1..20)
            ) { booleans ->
                val ctx = minimalContext()
                val conditions = booleans.map { TrackingCondition(it) }
                val node = AndConditionNode(conditions)

                node.evaluate(ctx)

                val firstFalseIndex = booleans.indexOfFirst { !it }
                if (firstFalseIndex == -1) {
                    // All true — every condition must have been evaluated exactly once
                    conditions.forEach { it.evaluationCount shouldBe 1 }
                } else {
                    // Conditions up to and including the first false must be evaluated
                    for (i in 0..firstFalseIndex) {
                        conditions[i].evaluationCount shouldBe 1
                    }
                    // Conditions after the first false must NOT be evaluated
                    for (i in (firstFalseIndex + 1) until conditions.size) {
                        conditions[i].evaluationCount shouldBe 0
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 10c: Empty list → vacuous truth (Req 6.3)
    // -----------------------------------------------------------------------

    "Property 10c: AndConditionNode with empty child list returns true (vacuous truth)" - {
        // **Validates: Requirements 6.3**
        // An empty AND is vacuously true.
        "empty AndConditionNode evaluates to true" {
            val ctx = minimalContext()
            val node = AndConditionNode(emptyList())
            node.evaluate(ctx) shouldBe true
        }
    }
})
