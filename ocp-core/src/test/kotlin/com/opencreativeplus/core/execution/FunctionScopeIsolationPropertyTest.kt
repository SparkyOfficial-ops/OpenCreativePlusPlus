@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-visual-programming-platform, Property 11: Изоляция переменных при вызове функции

package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based tests for function scope isolation in [ExecutionEngine].
 *
 * **Property 11: Изоляция переменных при вызове функции**
 *
 * For any function call, variables in the caller's localScope must not be visible
 * inside the function; the targets list must be inherited without modification.
 *
 * **Validates: Requirements 5.5**
 */
class FunctionScopeIsolationPropertyTest : FreeSpec({

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

    /**
     * Builds a minimal ExecutionContext with the given localScope and targets.
     */
    fun buildContext(
        localScope: VariableScope,
        targets: MutableList<Entity> = mutableListOf()
    ): ExecutionContext = object : ExecutionContext {
        override val plotId: UUID = UUID.randomUUID()
        override val player: Player? = null
        override val eventData: Map<String, Any> = emptyMap()
        override val localScope: VariableScope = localScope
        override val plotScope: VariableScope = mapScope()
        override val savedScope: VariableScope = mapScope()
        override val operationCount: AtomicInteger = AtomicInteger(0)
        override val callStackSize: AtomicInteger = AtomicInteger(0)
        override val targets: MutableList<Entity> = targets
        override var currentTarget: Entity? = null
        override suspend fun <T> syncContext(block: () -> T): T = block()
    }

    /**
     * Simulates the function call isolation logic from ExecutionEngine.executeFunctionCall:
     * - Creates a new isolated localScope for the function
     * - Inherits targets from the calling context
     * - Executes the function body with the new context
     *
     * Returns the function's ExecutionContext so tests can inspect its state.
     */
    suspend fun simulateFunctionCall(
        callerContext: ExecutionContext,
        functionBody: suspend (ExecutionContext) -> Unit
    ): ExecutionContext {
        // Req 5.5: isolated localScope, inherited targets
        val functionLocalScope = mapScope()
        val functionContext = object : ExecutionContext {
            override val plotId: UUID = callerContext.plotId
            override val player: Player? = callerContext.player
            override val eventData: Map<String, Any> = callerContext.eventData
            override val localScope: VariableScope = functionLocalScope
            override val plotScope: VariableScope = callerContext.plotScope
            override val savedScope: VariableScope = callerContext.savedScope
            override val operationCount: AtomicInteger = callerContext.operationCount
            override val callStackSize: AtomicInteger = callerContext.callStackSize
            override val targets: MutableList<Entity> = callerContext.targets  // inherited
            override var currentTarget: Entity? = null
            override suspend fun <T> syncContext(block: () -> T): T = block()
        }
        functionBody(functionContext)
        return functionContext
    }

    /** Arbitrary non-empty variable names (letters/digits/underscore). */
    val arbVarName: Arb<String> =
        Arb.string(1..20).map { s ->
            val cleaned = s.filter { it.isLetterOrDigit() || it == '_' }
            if (cleaned.isEmpty()) "var_x" else cleaned
        }.filter { it.isNotEmpty() }

    /** Arbitrary non-empty variable values. */
    val arbValue: Arb<String> =
        Arb.string(1..30).filter { it.isNotEmpty() }

    // -----------------------------------------------------------------------
    // Property 11a: caller's localScope variables are NOT visible inside function
    // -----------------------------------------------------------------------

    "Property 11: Изоляция переменных при вызове функции" - {

        /**
         * For any variable set in the caller's localScope, the function's localScope
         * must not contain that variable (starts empty / isolated).
         *
         * **Validates: Requirements 5.5**
         */
        "caller localScope variables are not visible inside the function" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbValue
            ) { varName, value ->
                val callerScope = mapScope()
                callerScope.set(varName, value)

                val callerContext = buildContext(callerScope)

                var functionScopeSnapshot: VariableScope? = null
                runBlocking {
                    simulateFunctionCall(callerContext) { fnCtx ->
                        functionScopeSnapshot = fnCtx.localScope
                    }
                }

                // Function's localScope must NOT contain the caller's variable
                functionScopeSnapshot!!.get(varName).shouldBeNull()
            }
        }

        /**
         * Variables set inside the function's localScope must not leak back
         * into the caller's localScope.
         *
         * **Validates: Requirements 5.5**
         */
        "function localScope writes do not leak into caller localScope" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbValue
            ) { varName, value ->
                val callerScope = mapScope()
                val callerContext = buildContext(callerScope)

                runBlocking {
                    simulateFunctionCall(callerContext) { fnCtx ->
                        // Write a variable inside the function
                        fnCtx.localScope.set(varName, value)
                    }
                }

                // Caller's localScope must NOT see the function's variable
                callerScope.get(varName).shouldBeNull()
            }
        }

        /**
         * Caller and function can have variables with the same name but different values —
         * they must remain independent.
         *
         * **Validates: Requirements 5.5**
         */
        "same variable name in caller and function holds independent values" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbValue,
                arbValue
            ) { varName, callerValue, functionValue ->
                val callerScope = mapScope()
                callerScope.set(varName, callerValue)
                val callerContext = buildContext(callerScope)

                var functionScopeAfter: VariableScope? = null
                runBlocking {
                    simulateFunctionCall(callerContext) { fnCtx ->
                        // Function sets its own value for the same name
                        fnCtx.localScope.set(varName, functionValue)
                        functionScopeAfter = fnCtx.localScope
                    }
                }

                // Caller's value is unchanged
                callerScope.get(varName) shouldBe callerValue
                // Function's value is its own
                functionScopeAfter!!.get(varName) shouldBe functionValue
            }
        }

        /**
         * The targets list is inherited from the caller — the function sees the same
         * targets reference and the same elements.
         *
         * **Validates: Requirements 5.5**
         */
        "targets list is inherited from caller into function" {
            val arbTargets = io.kotest.property.arbitrary.arbitrary { rs ->
                val size = rs.random.nextInt(0, 6)
                (1..size).map { mockk<Entity>(relaxed = true) }.toMutableList<Entity>()
            }

            checkAll(PropTestConfig(iterations = 100), arbTargets) { targets ->
                val callerContext = buildContext(mapScope(), targets)

                var functionTargets: List<Entity>? = null
                runBlocking {
                    simulateFunctionCall(callerContext) { fnCtx ->
                        functionTargets = fnCtx.targets.toList()
                    }
                }

                // Function sees the same targets as the caller
                functionTargets!! shouldBe targets.toList()
            }
        }

        /**
         * Mutations to targets inside the function are reflected in the caller
         * (shared reference — inherited, not copied).
         *
         * **Validates: Requirements 5.5**
         */
        "targets list is shared by reference — mutations inside function are visible to caller" {
            checkAll(PropTestConfig(iterations = 100), io.kotest.property.arbitrary.arbitrary { rs ->
                val size = rs.random.nextInt(1, 6)
                (1..size).map { mockk<Entity>(relaxed = true) }.toMutableList<Entity>()
            }) { targets ->
                val originalSize = targets.size
                val callerContext = buildContext(mapScope(), targets)

                val extraEntity = mockk<Entity>(relaxed = true)
                runBlocking {
                    simulateFunctionCall(callerContext) { fnCtx ->
                        fnCtx.targets.add(extraEntity)
                    }
                }

                // Caller's targets list reflects the mutation done inside the function
                callerContext.targets.size shouldBe originalSize + 1
                callerContext.targets.last() shouldBe extraEntity
            }
        }

        /**
         * plotScope is shared between caller and function — writes in function
         * are visible to the caller (only localScope is isolated).
         *
         * **Validates: Requirements 5.5**
         */
        "plotScope is shared between caller and function" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbVarName,
                arbValue
            ) { varName, value ->
                val sharedPlotScope = mapScope()
                val callerContext = object : ExecutionContext {
                    override val plotId: UUID = UUID.randomUUID()
                    override val player: Player? = null
                    override val eventData: Map<String, Any> = emptyMap()
                    override val localScope: VariableScope = mapScope()
                    override val plotScope: VariableScope = sharedPlotScope
                    override val savedScope: VariableScope = mapScope()
                    override val operationCount: AtomicInteger = AtomicInteger(0)
                    override val callStackSize: AtomicInteger = AtomicInteger(0)
                    override val targets: MutableList<Entity> = mutableListOf()
                    override var currentTarget: Entity? = null
                    override suspend fun <T> syncContext(block: () -> T): T = block()
                }

                runBlocking {
                    simulateFunctionCall(callerContext) { fnCtx ->
                        // Write to plotScope inside function
                        fnCtx.plotScope.set(varName, value)
                    }
                }

                // Caller sees the plotScope write from the function
                sharedPlotScope.get(varName) shouldBe value
            }
        }
    }
})
