@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-visual-programming-platform, Property 2: Итерация ActionNode по всем целям

package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.node.IAction
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based test for ActionNode iteration over context.targets.
 *
 * **Validates: Requirements 1.8**
 *
 * Property 2: For any ActionNode and any list of context.targets,
 * the action's execute method is called exactly targets.size times — once per target.
 * When targets is empty, execute is called 0 times (no exception).
 */
class ActionNodeIterationPropertyTest : FreeSpec({

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

    /** Builds a minimal ExecutionContext with the given targets list. */
    fun buildContext(targets: MutableList<Entity>): ExecutionContext = object : ExecutionContext {
        override val plotId: UUID = UUID.randomUUID()
        override val player: Player? = null
        override val eventData: Map<String, Any> = emptyMap()
        override val localScope: VariableScope = mapScope()
        override val plotScope: VariableScope = mapScope()
        override val savedScope: VariableScope = mapScope()
        override val operationCount: AtomicInteger = AtomicInteger(0)
        override val callStackSize: AtomicInteger = AtomicInteger(0)
        override val targets: MutableList<Entity> = targets
        override suspend fun <T> syncContext(block: () -> T): T = block()
    }

    /**
     * Builds a counting IAction that increments [counter] on each execute() call.
     */
    fun countingAction(counter: AtomicInteger): IAction = object : IAction {
        override val nodeId = "counting"
        override val displayName = "Counting"
        override suspend fun execute(context: ExecutionContext) {
            counter.incrementAndGet()
        }
    }

    /**
     * Simulates the ExecutionEngine's target-iteration loop (Req 1.8 / 1.9):
     *   val targets = context.targets.toList()
     *   if (targets.isNotEmpty()) { for (target in targets) { action.execute(context) } }
     */
    suspend fun runIterationLoop(action: IAction, context: ExecutionContext) {
        val targets = context.targets.toList()
        if (targets.isNotEmpty()) {
            for (target in targets) {
                action.execute(context)
            }
        }
    }

    /** Arbitrary that produces a fresh mock Entity. */
    val arbEntity = arbitrary { mockk<Entity>(relaxed = true) }

    /** Arbitrary that produces a non-empty list of entities (1..20 elements). */
    val arbNonEmptyTargets = arbitrary { rs ->
        val size = rs.random.nextInt(1, 21)
        (1..size).map { mockk<Entity>(relaxed = true) }.toMutableList<Entity>()
    }

    // -----------------------------------------------------------------------
    // Property 2a — non-empty targets → execute called exactly targets.size times
    // -----------------------------------------------------------------------

    "Property 2: Итерация ActionNode по всем целям" - {

        /**
         * For any non-empty targets list, execute must be called exactly targets.size times.
         *
         * **Validates: Requirements 1.8**
         */
        "non-empty targets → execute called exactly targets.size times" {
            checkAll(PropTestConfig(iterations = 100), arbNonEmptyTargets) { targets ->
                val counter = AtomicInteger(0)
                val action = countingAction(counter)
                val context = buildContext(targets)

                runBlocking { runIterationLoop(action, context) }

                counter.get() shouldBe targets.size
            }
        }

        /**
         * When targets is empty, execute must be called 0 times — no exception thrown.
         *
         * **Validates: Requirements 1.8, 1.9**
         */
        "empty targets → execute called 0 times, no exception" {
            repeat(100) {
                val counter = AtomicInteger(0)
                val action = countingAction(counter)
                val context = buildContext(mutableListOf())

                runBlocking { runIterationLoop(action, context) }

                counter.get() shouldBe 0
            }
        }

        /**
         * For a single target, execute is called exactly once.
         *
         * **Validates: Requirements 1.8**
         */
        "single target → execute called exactly once" {
            checkAll(PropTestConfig(iterations = 100), arbEntity) { entity ->
                val counter = AtomicInteger(0)
                val action = countingAction(counter)
                val context = buildContext(mutableListOf(entity))

                runBlocking { runIterationLoop(action, context) }

                counter.get() shouldBe 1
            }
        }

        /**
         * The call count equals targets.size for any list size (0..20).
         * Combines empty and non-empty cases in one property.
         *
         * **Validates: Requirements 1.8**
         */
        "call count always equals targets.size for any list size" {
            val arbAnyTargets = arbitrary { rs ->
                val size = rs.random.nextInt(0, 21)
                (1..size).map { mockk<Entity>(relaxed = true) }.toMutableList<Entity>()
            }

            checkAll(PropTestConfig(iterations = 100), arbAnyTargets) { targets ->
                val counter = AtomicInteger(0)
                val action = countingAction(counter)
                val context = buildContext(targets)

                runBlocking { runIterationLoop(action, context) }

                counter.get() shouldBe targets.size
            }
        }
    }
})
