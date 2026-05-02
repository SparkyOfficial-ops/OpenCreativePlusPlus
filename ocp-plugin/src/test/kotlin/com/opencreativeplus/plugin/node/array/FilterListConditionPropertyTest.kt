@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.node.array

// Feature: ocp-plugin-fixes-and-completions
// Property 8a: Результат фильтрации содержит не более N элементов (метаморфическое свойство)
// Property 8b: Двойная фильтрация с одним условием идемпотентна
// Validates: Requirements 8.7, 8.8

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property 8a: FilterListNode result size is at most N (metamorphic property)
 * Property 8b: Double filtering with the same condition is idempotent
 * Validates: Requirements 8.7, 8.8
 */
class FilterListConditionPropertyTest : FreeSpec({

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

    fun contextWithList(items: List<Any?>): ExecutionContext {
        val localScope = mapScope(mapOf("myList" to items))
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
            override var currentTarget: org.bukkit.entity.Entity? = null
            override suspend fun <T> syncContext(block: () -> T): T = block()
        }
    }

    /** Run FilterListNode on a list with the given condition string. */
    suspend fun filterList(items: List<Any?>, condition: String): List<Any?> {
        val ctx = contextWithList(items)
        return FilterListNode(mapOf("list" to "myList", "condition" to condition)).compute(ctx)
    }

    // -----------------------------------------------------------------------
    // Property 8a: Result size ≤ N (Requirement 8.7)
    // -----------------------------------------------------------------------

    "Property 8a: FilterListNode result size is at most N (Requirement 8.7)" - {
        // Feature: ocp-plugin-fixes-and-completions, Property 8a: result size <= input size
        // For any list of N integers and any valid condition, the result contains at most N elements.

        "result size <= input size for == operator" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.list(Arb.int(-100..100), 0..20),
                Arb.int(-100..100)
            ) { items, target ->
                val result = filterList(items, "x == $target")
                result.size shouldBeLessThanOrEqualTo items.size
            }
        }

        "result size <= input size for != operator" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.list(Arb.int(-100..100), 0..20),
                Arb.int(-100..100)
            ) { items, target ->
                val result = filterList(items, "x != $target")
                result.size shouldBeLessThanOrEqualTo items.size
            }
        }

        "result size <= input size for > operator" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.list(Arb.int(-100..100), 0..20),
                Arb.int(-100..100)
            ) { items, threshold ->
                val result = filterList(items, "x > $threshold")
                result.size shouldBeLessThanOrEqualTo items.size
            }
        }

        "result size <= input size for < operator" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.list(Arb.int(-100..100), 0..20),
                Arb.int(-100..100)
            ) { items, threshold ->
                val result = filterList(items, "x < $threshold")
                result.size shouldBeLessThanOrEqualTo items.size
            }
        }

        "result size <= input size for >= operator" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.list(Arb.int(-100..100), 0..20),
                Arb.int(-100..100)
            ) { items, threshold ->
                val result = filterList(items, "x >= $threshold")
                result.size shouldBeLessThanOrEqualTo items.size
            }
        }

        "result size <= input size for <= operator" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.list(Arb.int(-100..100), 0..20),
                Arb.int(-100..100)
            ) { items, threshold ->
                val result = filterList(items, "x <= $threshold")
                result.size shouldBeLessThanOrEqualTo items.size
            }
        }

        "result size <= input size for all supported operators" {
            val operators = listOf("==", "!=", ">", "<", ">=", "<=")
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.list(Arb.int(-50..50), 0..15),
                Arb.int(-50..50),
                Arb.element(operators)
            ) { items, value, op ->
                val result = filterList(items, "x $op $value")
                result.size shouldBeLessThanOrEqualTo items.size
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8b: Double filtering is idempotent (Requirement 8.8)
    // -----------------------------------------------------------------------

    "Property 8b: FilterListNode double filtering with same condition is idempotent (Requirement 8.8)" - {
        // Feature: ocp-plugin-fixes-and-completions, Property 8b: filter(filter(list, cond), cond) == filter(list, cond)
        // Applying the same filter twice must yield the same result as applying it once.

        "idempotent for == condition" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.list(Arb.int(-100..100), 0..20),
                Arb.int(-100..100)
            ) { items, target ->
                val condition = "x == $target"
                val once = filterList(items, condition)
                val twice = filterList(once, condition)
                twice shouldBe once
            }
        }

        "idempotent for != condition" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.list(Arb.int(-100..100), 0..20),
                Arb.int(-100..100)
            ) { items, target ->
                val condition = "x != $target"
                val once = filterList(items, condition)
                val twice = filterList(once, condition)
                twice shouldBe once
            }
        }

        "idempotent for > condition" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.list(Arb.int(-100..100), 0..20),
                Arb.int(-100..100)
            ) { items, threshold ->
                val condition = "x > $threshold"
                val once = filterList(items, condition)
                val twice = filterList(once, condition)
                twice shouldBe once
            }
        }

        "idempotent for < condition" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.list(Arb.int(-100..100), 0..20),
                Arb.int(-100..100)
            ) { items, threshold ->
                val condition = "x < $threshold"
                val once = filterList(items, condition)
                val twice = filterList(once, condition)
                twice shouldBe once
            }
        }

        "idempotent for >= condition" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.list(Arb.int(-100..100), 0..20),
                Arb.int(-100..100)
            ) { items, threshold ->
                val condition = "x >= $threshold"
                val once = filterList(items, condition)
                val twice = filterList(once, condition)
                twice shouldBe once
            }
        }

        "idempotent for <= condition" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.list(Arb.int(-100..100), 0..20),
                Arb.int(-100..100)
            ) { items, threshold ->
                val condition = "x <= $threshold"
                val once = filterList(items, condition)
                val twice = filterList(once, condition)
                twice shouldBe once
            }
        }

        "idempotent for all supported operators" {
            val operators = listOf("==", "!=", ">", "<", ">=", "<=")
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.list(Arb.int(-50..50), 0..15),
                Arb.int(-50..50),
                Arb.element(operators)
            ) { items, value, op ->
                val condition = "x $op $value"
                val once = filterList(items, condition)
                val twice = filterList(once, condition)
                twice shouldBe once
            }
        }
    }
})
