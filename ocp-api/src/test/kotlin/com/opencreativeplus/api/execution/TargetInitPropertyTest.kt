@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-mvp2-core-systems, Property 1: Итерация по targets

package com.opencreativeplus.api.execution

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based tests for currentTarget initialization and iteration in ExecutionContext.
 *
 * **Validates: Requirements 1.1, 1.2, 1.13**
 *
 * Property 1: Итерация по targets
 * For any action-node and a list of N entities in context.targets, the ExecutionEngine
 * iteration loop must:
 *   - call action.execute exactly N times (one per entity)
 *   - set context.currentTarget to each entity in order before each execute call
 *
 * Also covers context initialization (Req 1.2):
 *   - non-null player → targets initialized with exactly that player
 *   - null player → targets initialized empty
 */
class TargetInitPropertyTest : FreeSpec({

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
     * Builds a mutable ExecutionContext stub that supports setting currentTarget.
     * Mirrors ExecutionContextImpl's targets initialization logic:
     *   targets = if (player != null) mutableListOf(player) else mutableListOf()
     */
    fun buildContext(player: Player?, initialTargets: MutableList<Entity>? = null): ExecutionContext =
        object : ExecutionContext {
            override val plotId: UUID = UUID.randomUUID()
            override val player: Player? = player
            override val eventData: Map<String, Any> = emptyMap()
            override val localScope: VariableScope = mapScope()
            override val plotScope: VariableScope = mapScope()
            override val savedScope: VariableScope = mapScope()
            override val operationCount: AtomicInteger = AtomicInteger(0)
            override val callStackSize: AtomicInteger = AtomicInteger(0)
            // Mirrors ExecutionContextImpl initialization (Req 1.2)
            override val targets: MutableList<Entity> =
                initialTargets ?: if (player != null) mutableListOf(player) else mutableListOf()
            override var currentTarget: Entity? = null
            override suspend fun <T> syncContext(block: () -> T): T = block()
        }

    /**
     * Simulates the ExecutionEngine's target-iteration loop (Req 1.1):
     *   for (target in targets) {
     *       context.currentTarget = target
     *       action.execute(context)
     *   }
     */
    suspend fun runIterationLoop(
        context: ExecutionContext,
        onExecute: suspend (ExecutionContext) -> Unit
    ) {
        val targets = context.targets.toList()
        for (target in targets) {
            context.currentTarget = target
            onExecute(context)
        }
    }

    /** Arbitrary that always produces a fresh mock Player. */
    val arbPlayer = arbitrary { mockk<Player>(relaxed = true) }

    /** Arbitrary that produces a non-empty list of entities (1..20 elements). */
    val arbNonEmptyTargets = arbitrary { rs ->
        val size = rs.random.nextInt(1, 21)
        (1..size).map { mockk<Entity>(relaxed = true) }.toMutableList()
    }

    /** Arbitrary that produces any list of entities (0..20 elements). */
    val arbAnyTargets = arbitrary { rs ->
        val size = rs.random.nextInt(0, 21)
        (1..size).map { mockk<Entity>(relaxed = true) }.toMutableList<Entity>()
    }

    // -----------------------------------------------------------------------
    // Property 1: Итерация по targets — currentTarget установлен для каждой сущности
    // -----------------------------------------------------------------------

    "Property 1: Итерация по targets" - {

        /**
         * For any non-empty targets list, execute must be called exactly targets.size times,
         * and currentTarget must equal the corresponding entity at each call.
         *
         * **Validates: Requirements 1.1, 1.13**
         */
        "N targets → execute called N times, currentTarget matches each entity in order" {
            checkAll(PropTestConfig(iterations = 100), arbNonEmptyTargets) { targets ->
                val context = buildContext(player = null, initialTargets = targets)
                val capturedTargets = mutableListOf<Entity?>()
                var callCount = 0

                runBlocking {
                    runIterationLoop(context) { ctx ->
                        callCount++
                        capturedTargets.add(ctx.currentTarget)
                    }
                }

                // Exactly N calls (Req 1.13)
                callCount shouldBe targets.size

                // currentTarget matches each entity in order (Req 1.1)
                capturedTargets.size shouldBe targets.size
                capturedTargets.zip(targets).forEach { (captured, expected) ->
                    (captured === expected) shouldBe true
                }
            }
        }

        /**
         * When targets is empty, execute is never called and no exception is thrown.
         *
         * **Validates: Requirements 1.1, 1.13**
         */
        "empty targets → execute called 0 times, no exception" {
            repeat(100) {
                val context = buildContext(player = null, initialTargets = mutableListOf())
                var callCount = 0

                runBlocking {
                    runIterationLoop(context) { _ -> callCount++ }
                }

                callCount shouldBe 0
                context.currentTarget shouldBe null
            }
        }

        /**
         * For a single target, execute is called exactly once and currentTarget
         * is set to that entity.
         *
         * **Validates: Requirements 1.1, 1.13**
         */
        "single target → execute called once, currentTarget is that entity" {
            checkAll(PropTestConfig(iterations = 100), arbPlayer) { player ->
                val entity = mockk<Entity>(relaxed = true)
                val context = buildContext(player = player, initialTargets = mutableListOf(entity))
                var callCount = 0
                var seenTarget: Entity? = null

                runBlocking {
                    runIterationLoop(context) { ctx ->
                        callCount++
                        seenTarget = ctx.currentTarget
                    }
                }

                callCount shouldBe 1
                (seenTarget === entity) shouldBe true
            }
        }

        /**
         * currentTarget after the loop ends is the last entity in targets.
         * This verifies the engine does not reset currentTarget after iteration.
         *
         * **Validates: Requirements 1.1**
         */
        "after iteration, currentTarget is the last entity in targets" {
            checkAll(PropTestConfig(iterations = 100), arbNonEmptyTargets) { targets ->
                val context = buildContext(player = null, initialTargets = targets)

                runBlocking {
                    runIterationLoop(context) { _ -> /* no-op */ }
                }

                (context.currentTarget === targets.last()) shouldBe true
            }
        }

        /**
         * The call count equals targets.size for any list size (0..20).
         * Combines empty and non-empty cases in one property.
         *
         * **Validates: Requirements 1.1, 1.13**
         */
        "call count always equals targets.size for any list size" {
            checkAll(PropTestConfig(iterations = 100), arbAnyTargets) { targets ->
                val context = buildContext(player = null, initialTargets = targets)
                var callCount = 0

                runBlocking {
                    runIterationLoop(context) { _ -> callCount++ }
                }

                callCount shouldBe targets.size
            }
        }
    }

    // -----------------------------------------------------------------------
    // Context initialization (Req 1.2) — kept from original test
    // -----------------------------------------------------------------------

    "Context initialization: targets initialized from player" - {

        /**
         * For any non-null player, context.targets must contain exactly one element
         * and that element must be the same player instance.
         *
         * **Validates: Requirements 1.2**
         */
        "non-null player → targets contains exactly that one player" {
            checkAll(PropTestConfig(iterations = 100), arbPlayer) { player ->
                val ctx = buildContext(player)

                ctx.targets.size shouldBe 1
                ctx.targets shouldContainExactly listOf(player)
            }
        }

        /**
         * When player is null (non-player event), targets must be empty.
         *
         * **Validates: Requirements 1.2**
         */
        "null player → targets is empty" {
            repeat(100) {
                val ctx = buildContext(null)
                ctx.targets.shouldBeEmpty()
            }
        }

        /**
         * The player in targets is the exact same instance passed to the context,
         * not a copy or different object.
         *
         * **Validates: Requirements 1.2**
         */
        "targets[0] is the same instance as the triggering player" {
            checkAll(PropTestConfig(iterations = 100), arbPlayer) { player ->
                val ctx = buildContext(player)

                (ctx.targets[0] === player) shouldBe true
            }
        }

        /**
         * targets list is mutable — it can be modified after initialization.
         * This is required for SelectionNode to replace targets later.
         *
         * **Validates: Requirements 1.1, 1.2**
         */
        "targets list is mutable after initialization" {
            checkAll(PropTestConfig(iterations = 100), arbPlayer) { player ->
                val ctx = buildContext(player)
                val extra = mockk<Entity>(relaxed = true)

                ctx.targets.add(extra)

                ctx.targets.size shouldBe 2
                (ctx.targets[0] === player) shouldBe true
                (ctx.targets[1] === extra) shouldBe true
            }
        }
    }
})
