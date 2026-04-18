@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.node.array

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
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
 * Property 13: List operations correctness
 * Validates: Requirements 8.2, 8.3, 8.4, 8.6
 *
 * s 8.2: AddToList appends a value to a list variable
 * s 8.3: GetListSize returns the number of elements in a list variable
 * s 8.4: GetListElement returns the element at a specified index; out-of-bounds returns null
 * s 8.6: FilterList returns a new list containing only elements matching a specified condition
 */
class ArrayNodePropertyTest : FreeSpec({

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
            override suspend fun <T> syncContext(block: () -> T): T = block()
        }
    }

    // -----------------------------------------------------------------------
    // Property 13a: AddToList appends element (s 8.2)
    // -----------------------------------------------------------------------

    "Property 13a: AddToListNode appends element to list (s 8.2)" - {
        // Validates: Requirements 8.2
        // For any list of strings and any new string value, after AddToListNode executes
        // the list size increases by 1 and the last element equals the appended value.
        "list size increases by 1 and last element equals appended value" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.string(1..10), 0..30),
                Arb.string(1..10)
            ) { initial, newValue ->
                val ctx = contextWithLocalScope(mapOf("myList" to initial.toMutableList<Any?>()))
                val node = AddToListNode(mapOf("list" to "myList", "value" to newValue))
                node.execute(ctx)

                @Suppress("UNCHECKED_CAST")
                val result = ctx.localScope.get("myList") as List<Any?>
                result.size shouldBe initial.size + 1
                result.last() shouldBe newValue
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 13b: GetListSize returns correct count (s 8.3)
    // -----------------------------------------------------------------------

    "Property 13b: GetListSizeNode returns correct count (s 8.3)" - {
        // Validates: Requirements 8.3
        // For any list of strings of size N, GetListSizeNode returns N.
        "returns exactly list.size" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.string(1..10), 0..30)
            ) { items ->
                val ctx = contextWithLocalScope(mapOf("myList" to items))
                val node = GetListSizeNode(mapOf("list" to "myList"))
                val size = node.compute(ctx)
                size shouldBe items.size
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 13c: GetListElement returns correct element for valid index (s 8.4)
    // -----------------------------------------------------------------------

    "Property 13c: GetListElementNode returns correct element for valid index (s 8.4)" - {
        // Validates: Requirements 8.4
        // For any non-empty list and any valid index i (0..list.size-1),
        // GetListElementNode returns list[i].
        "returns element at valid index" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.string(1..10), 1..30)
            ) { items ->
                val validIndex = items.indices.random()
                val ctx = contextWithLocalScope(mapOf("myList" to items))
                val node = GetListElementNode(mapOf("list" to "myList", "index" to validIndex))
                val result = node.compute(ctx)
                result shouldBe items[validIndex]
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 13d: GetListElement returns null for out-of-bounds index (s 8.4)
    // -----------------------------------------------------------------------

    "Property 13d: GetListElementNode returns null for out-of-bounds index (s 8.4)" - {
        // Validates: Requirements 8.4
        // For any list of size N, GetListElementNode with index N (one past end) returns null.
        // Also test negative index returns null.
        "returns null for index == list.size (one past end)" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.string(1..10), 0..30)
            ) { items ->
                val ctx = contextWithLocalScope(mapOf("myList" to items))
                val node = GetListElementNode(mapOf("list" to "myList", "index" to items.size))
                val result = node.compute(ctx)
                result.shouldBeNull()
            }
        }

        "returns null for negative index" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.string(1..10), 0..30),
                Arb.int(-50..-1)
            ) { items, negIndex ->
                val ctx = contextWithLocalScope(mapOf("myList" to items))
                val node = GetListElementNode(mapOf("list" to "myList", "index" to negIndex))
                val result = node.compute(ctx)
                result.shouldBeNull()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 13e: FilterList returns only matching elements (s 8.6)
    // -----------------------------------------------------------------------

    "Property 13e: FilterListNode returns only elements matching predicate (s 8.6)" - {
        // Validates: Requirements 8.6
        // For any list of integers, FilterListNode with predicate { it is Int && it > 0 }
        // returns only positive integers.
        // The result must be a subset of the original list.
        // All elements in the result must satisfy the predicate.
        val predicate: (Any?) -> Boolean = { it is Int && it > 0 }

        "result is a subset of the original list" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.int(-50..50), 0..30)
            ) { items ->
                val ctx = contextWithLocalScope(mapOf("myList" to items))
                val node = FilterListNode(mapOf("list" to "myList", "predicate" to predicate))
                val result = node.compute(ctx)
                // Every element in result must appear in the original list
                items shouldContainAll result
            }
        }

        "all elements in result satisfy the predicate" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.int(-50..50), 0..30)
            ) { items ->
                val ctx = contextWithLocalScope(mapOf("myList" to items))
                val node = FilterListNode(mapOf("list" to "myList", "predicate" to predicate))
                val result = node.compute(ctx)
                result.all { predicate(it) } shouldBe true
            }
        }

        "result contains all original elements that satisfy the predicate" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.int(-50..50), 0..30)
            ) { items ->
                val ctx = contextWithLocalScope(mapOf("myList" to items))
                val node = FilterListNode(mapOf("list" to "myList", "predicate" to predicate))
                val result = node.compute(ctx)
                val expected = items.filter { predicate(it) }
                result shouldBe expected
            }
        }
    }
})
