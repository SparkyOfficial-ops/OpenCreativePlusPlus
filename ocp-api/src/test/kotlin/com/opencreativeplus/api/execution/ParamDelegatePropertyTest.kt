@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.api.execution

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based tests for [ParamDelegate] / [param] / [optionalParam].
 *
 *  9.1, 9.3, 9.4
 */
class ParamDelegatePropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Minimal no-op VariableScope backed by a mutable map. */
    fun mapScope(initial: Map<String, Any> = emptyMap()): VariableScope {
        val store = initial.toMutableMap()
        return object : VariableScope {
            override fun get(name: String): Any? = store[name]
            override fun set(name: String, value: Any) { store[name] = value }
            override fun has(name: String): Boolean = store.containsKey(name)
            override fun clear() = store.clear()
        }
    }

    /**
     * Build a minimal [ExecutionContext] where [eventData] contains the given entries
     * and [localScope] is empty.
     */
    fun contextWithEventData(data: Map<String, Any>): ExecutionContext =
        object : ExecutionContext {
            override val plotId: UUID = UUID.randomUUID()
            override val player = null
            override val eventData: Map<String, Any> = data
            override val localScope: VariableScope = mapScope()
            override val plotScope: VariableScope = mapScope()
            override val savedScope: VariableScope = mapScope()
            override val operationCount: AtomicInteger = AtomicInteger(0)
            override val callStackSize: AtomicInteger = AtomicInteger(0)
            override val targets: MutableList<org.bukkit.entity.Entity> = mutableListOf()
            override suspend fun <T> syncContext(block: () -> T): T = block()
        }

    /**
     * Build a minimal [ExecutionContext] where [localScope] contains the given entries
     * and [eventData] is empty.
     */
    fun contextWithLocalScope(data: Map<String, Any>): ExecutionContext =
        object : ExecutionContext {
            override val plotId: UUID = UUID.randomUUID()
            override val player = null
            override val eventData: Map<String, Any> = emptyMap()
            override val localScope: VariableScope = mapScope(data)
            override val plotScope: VariableScope = mapScope()
            override val savedScope: VariableScope = mapScope()
            override val operationCount: AtomicInteger = AtomicInteger(0)
            override val callStackSize: AtomicInteger = AtomicInteger(0)
            override val targets: MutableList<org.bukkit.entity.Entity> = mutableListOf()
            override suspend fun <T> syncContext(block: () -> T): T = block()
        }

    // -----------------------------------------------------------------------
    // Property 14a — round-trip: stored value is retrieved correctly
    // -----------------------------------------------------------------------

    "Property 14a: param<T> round-trip via eventData" - {

        "String values round-trip" {
            //  9.1
            checkAll(PropTestConfig(iterations = 20), Arb.string(), Arb.string(1..30)) { value, key ->
                val ctx = contextWithEventData(mapOf(key to value))
                val delegate = ctx.param<String>(key)
                val result by delegate
                result shouldBe value
            }
        }

        "Int values round-trip" {
            //  9.1
            checkAll(PropTestConfig(iterations = 20), Arb.int(), Arb.string(1..30)) { value, key ->
                val ctx = contextWithEventData(mapOf(key to value))
                val delegate = ctx.param<Int>(key)
                val result by delegate
                result shouldBe value
            }
        }

        "Double values round-trip" {
            //  9.1
            checkAll(PropTestConfig(iterations = 20), Arb.double(), Arb.string(1..30)) { value, key ->
                val ctx = contextWithEventData(mapOf(key to value))
                val delegate = ctx.param<Double>(key)
                val result by delegate
                result shouldBe value
            }
        }

        "Boolean values round-trip" {
            //  9.1
            checkAll(PropTestConfig(iterations = 20), Arb.boolean(), Arb.string(1..30)) { value, key ->
                val ctx = contextWithEventData(mapOf(key to value))
                val delegate = ctx.param<Boolean>(key)
                val result by delegate
                result shouldBe value
            }
        }

        "String values round-trip via localScope (fallback)" {
            //  9.1 — localScope is the fallback lookup
            checkAll(PropTestConfig(iterations = 20), Arb.string(), Arb.string(1..30)) { value, key ->
                val ctx = contextWithLocalScope(mapOf(key to value))
                val delegate = ctx.param<String>(key)
                val result by delegate
                result shouldBe value
            }
        }

        "eventData takes priority over localScope" {
            //  9.1 — eventData is checked first
            checkAll(PropTestConfig(iterations = 20), Arb.string(), Arb.string(), Arb.string(1..30)) { eventVal, localVal, key ->
                val ctx = object : ExecutionContext {
                    override val plotId: UUID = UUID.randomUUID()
                    override val player = null
                    override val eventData: Map<String, Any> = mapOf(key to eventVal)
                    override val localScope: VariableScope = mapScope(mapOf(key to localVal))
                    override val plotScope: VariableScope = mapScope()
                    override val savedScope: VariableScope = mapScope()
                    override val operationCount: AtomicInteger = AtomicInteger(0)
                    override val callStackSize: AtomicInteger = AtomicInteger(0)
                    override val targets: MutableList<org.bukkit.entity.Entity> = mutableListOf()
                    override suspend fun <T> syncContext(block: () -> T): T = block()
                }
                val delegate = ctx.param<String>(key)
                val result by delegate
                result shouldBe eventVal
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 14b — type mismatch throws ParameterTypeMismatchException
    // -----------------------------------------------------------------------

    "Property 14b: param<T> throws ParameterTypeMismatchException on type mismatch" - {

        "Int stored, String expected → throws" {
            //  9.3
            checkAll(PropTestConfig(iterations = 20), Arb.int(), Arb.string(1..30)) { value, key ->
                val ctx = contextWithEventData(mapOf(key to value))
                val delegate = ctx.param<String>(key)
                shouldThrow<ParameterTypeMismatchException> {
                    @Suppress("UNUSED_VARIABLE")
                    val result by delegate
                    result // force getValue
                }
            }
        }

        "String stored, Int expected → throws" {
            //  9.3
            checkAll(PropTestConfig(iterations = 20), Arb.string(), Arb.string(1..30)) { value, key ->
                val ctx = contextWithEventData(mapOf(key to value))
                val delegate = ctx.param<Int>(key)
                shouldThrow<ParameterTypeMismatchException> {
                    @Suppress("UNUSED_VARIABLE")
                    val result by delegate
                    result
                }
            }
        }

        "Boolean stored, Double expected → throws" {
            //  9.3
            checkAll(PropTestConfig(iterations = 20), Arb.boolean(), Arb.string(1..30)) { value, key ->
                val ctx = contextWithEventData(mapOf(key to value))
                val delegate = ctx.param<Double>(key)
                shouldThrow<ParameterTypeMismatchException> {
                    @Suppress("UNUSED_VARIABLE")
                    val result by delegate
                    result
                }
            }
        }

        "exception message contains parameter name" {
            //  9.3 — exception carries the param name
            checkAll(PropTestConfig(iterations = 20), Arb.int(), Arb.string(1..30)) { value, key ->
                val ctx = contextWithEventData(mapOf(key to value))
                val delegate = ctx.param<String>(key)
                val ex = shouldThrow<ParameterTypeMismatchException> {
                    @Suppress("UNUSED_VARIABLE")
                    val result by delegate
                    result
                }
                ex.message?.contains(key) shouldBe true
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 14c — optionalParam returns null when key is absent
    // -----------------------------------------------------------------------

    "Property 14c: optionalParam<T> returns null when parameter is absent" - {

        "absent String param returns null" {
            //  9.4
            checkAll(PropTestConfig(iterations = 20), Arb.string(1..30)) { key ->
                val ctx = contextWithEventData(emptyMap())
                val delegate = ctx.optionalParam<String>(key)
                val result by delegate
                result.shouldBeNull()
            }
        }

        "absent Int param returns null" {
            //  9.4
            checkAll(PropTestConfig(iterations = 20), Arb.string(1..30)) { key ->
                val ctx = contextWithEventData(emptyMap())
                val delegate = ctx.optionalParam<Int>(key)
                val result by delegate
                result.shouldBeNull()
            }
        }

        "absent Boolean param returns null" {
            //  9.4
            checkAll(PropTestConfig(iterations = 20), Arb.string(1..30)) { key ->
                val ctx = contextWithEventData(emptyMap())
                val delegate = ctx.optionalParam<Boolean>(key)
                val result by delegate
                result.shouldBeNull()
            }
        }

        "key absent from both eventData and localScope returns null" {
            //  9.4
            checkAll(PropTestConfig(iterations = 20), Arb.string(1..30), Arb.string(1..30)) { presentKey, absentKey ->
                // Ensure the keys are different so absentKey is truly absent
                if (presentKey != absentKey) {
                    val ctx = contextWithEventData(mapOf(presentKey to "someValue"))
                    val delegate = ctx.optionalParam<String>(absentKey)
                    val result by delegate
                    result.shouldBeNull()
                }
            }
        }

        "optionalParam returns value when key IS present" {
            //  9.4 — null only when absent, not when present
            checkAll(PropTestConfig(iterations = 20), Arb.string(), Arb.string(1..30)) { value, key ->
                val ctx = contextWithEventData(mapOf(key to value))
                val delegate = ctx.optionalParam<String>(key)
                val result by delegate
                result shouldBe value
            }
        }
    }
})
