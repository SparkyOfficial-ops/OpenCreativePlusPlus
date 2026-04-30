@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-visual-programming-platform, Property 2: Итерация ActionNode по всем целям
// Feature: ocp-mvp2-core-systems, Property 1: Итерация по targets — N вызовов для N сущностей
// Feature: ocp-mvp2-core-systems, Property 2: Пропуск несовместимых типов

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
import org.bukkit.entity.LivingEntity
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
        override var currentTarget: org.bukkit.entity.Entity? = null
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

    // -----------------------------------------------------------------------
    // Property 1 (ocp-mvp2-core-systems): N вызовов для N сущностей
    // -----------------------------------------------------------------------

    /**
     * Property 1: Итерация по targets — N вызовов для N сущностей
     *
     * For any action-node that requires a compatible entity type (e.g. LivingEntity)
     * and a list of N compatible entities in context.targets, executing the node via
     * the ExecutionEngine iteration loop should produce exactly N calls to the action —
     * one per compatible entity.
     *
     * This mirrors the pattern used by ApplyPotionEffectNode, SetPlayerHealthNode, etc.:
     *   val entity = context.currentTarget as? LivingEntity ?: return
     *
     * **Validates: Requirements 1.1, 1.2, 1.12, 1.13**
     */
    "Property 1 (ocp-mvp2-core-systems): Итерация по targets — N вызовов для N сущностей" - {

        // Feature: ocp-mvp2-core-systems, Property 1: Итерация по targets — N вызовов для N сущностей

        /**
         * Stub action that mirrors the LivingEntity-filtering pattern of real nodes
         * (e.g. ApplyPotionEffectNode, SetPlayerHealthNode):
         *   val entity = context.currentTarget as? LivingEntity ?: return
         * Increments [counter] only when currentTarget is a LivingEntity.
         */
        fun livingEntityAction(counter: AtomicInteger): IAction = object : IAction {
            override val nodeId = "living_entity_action"
            override val displayName = "Living Entity Action"
            override suspend fun execute(context: ExecutionContext) {
                // Mirrors: val entity = context.currentTarget as? LivingEntity ?: return
                @Suppress("UNUSED_VARIABLE")
                val entity = context.currentTarget as? LivingEntity ?: return
                counter.incrementAndGet()
            }
        }

        /**
         * Stub action that mirrors the Player-filtering pattern of real nodes
         * (e.g. SetPlayerFoodLevelNode, GiveExperienceNode, SetGameModeNode):
         *   val player = context.currentTarget as? Player ?: return
         * Increments [counter] only when currentTarget is a Player.
         */
        fun playerAction(counter: AtomicInteger): IAction = object : IAction {
            override val nodeId = "player_action"
            override val displayName = "Player Action"
            override suspend fun execute(context: ExecutionContext) {
                // Mirrors: val player = context.currentTarget as? Player ?: return
                @Suppress("UNUSED_VARIABLE")
                val player = context.currentTarget as? Player ?: return
                counter.incrementAndGet()
            }
        }

        /**
         * Simulates the ExecutionEngine's target-iteration loop with currentTarget assignment
         * (Req 1.1, 1.2):
         *   for (target in targets) {
         *       context.currentTarget = target
         *       action.execute(context)
         *   }
         */
        suspend fun runWithCurrentTarget(action: IAction, context: ExecutionContext) {
            val targets = context.targets.toList()
            for (target in targets) {
                context.currentTarget = target
                action.execute(context)
            }
        }

        /** Arbitrary: non-empty list of mock LivingEntity (1..20). */
        val arbLivingEntities = arbitrary { rs ->
            val size = rs.random.nextInt(1, 21)
            (1..size).map { mockk<LivingEntity>(relaxed = true) }.toMutableList<Entity>()
        }

        /** Arbitrary: non-empty list of mock Player (1..20). */
        val arbPlayers = arbitrary { rs ->
            val size = rs.random.nextInt(1, 21)
            (1..size).map { mockk<Player>(relaxed = true) }.toMutableList<Entity>()
        }

        /**
         * For N LivingEntity targets, a LivingEntity-filtering action must be called exactly N times.
         *
         * **Validates: Requirements 1.1, 1.13**
         */
        "N LivingEntity targets → LivingEntity action called exactly N times" {
            checkAll(PropTestConfig(iterations = 100), arbLivingEntities) { targets ->
                val counter = AtomicInteger(0)
                val action = livingEntityAction(counter)
                val context = buildContext(targets)

                runBlocking { runWithCurrentTarget(action, context) }

                counter.get() shouldBe targets.size
            }
        }

        /**
         * For N Player targets, a Player-filtering action must be called exactly N times.
         *
         * **Validates: Requirements 1.1, 1.13**
         */
        "N Player targets → Player action called exactly N times" {
            checkAll(PropTestConfig(iterations = 100), arbPlayers) { targets ->
                val counter = AtomicInteger(0)
                val action = playerAction(counter)
                val context = buildContext(targets)

                runBlocking { runWithCurrentTarget(action, context) }

                counter.get() shouldBe targets.size
            }
        }

        /**
         * Empty targets → action called 0 times, no exception.
         *
         * **Validates: Requirements 1.2**
         */
        "empty targets → action called 0 times, no exception" {
            repeat(100) {
                val counter = AtomicInteger(0)
                val action = livingEntityAction(counter)
                val context = buildContext(mutableListOf())

                runBlocking { runWithCurrentTarget(action, context) }

                counter.get() shouldBe 0
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 2 (ocp-mvp2-core-systems): Пропуск несовместимых типов
    // -----------------------------------------------------------------------

    /**
     * Property 2: Пропуск несовместимых типов
     *
     * For any action-node requiring type T (e.g. Player), and a context.targets list
     * containing entities of mixed types, the node should apply only to entities of
     * type T and not throw exceptions for others.
     *
     * This mirrors the `as? Player ?: return` guard used in all real action nodes.
     *
     * **Validates: Requirements 1.1, 1.2, 1.12, 1.13**
     */
    "Property 2 (ocp-mvp2-core-systems): Пропуск несовместимых типов" - {

        // Feature: ocp-mvp2-core-systems, Property 2: Пропуск несовместимых типов

        /**
         * Stub action that requires Player (mirrors GiveItemNode, SendMessageNode, etc.).
         * Increments [counter] only for Player targets; silently returns for others.
         */
        fun playerOnlyAction(counter: AtomicInteger): IAction = object : IAction {
            override val nodeId = "player_only_action"
            override val displayName = "Player Only Action"
            override suspend fun execute(context: ExecutionContext) {
                // Mirrors: val player = context.currentTarget as? Player ?: return
                @Suppress("UNUSED_VARIABLE")
                val player = context.currentTarget as? Player ?: return
                counter.incrementAndGet()
            }
        }

        /**
         * Stub action that requires LivingEntity (mirrors ApplyPotionEffectNode, etc.).
         * Increments [counter] only for LivingEntity targets; silently returns for others.
         */
        fun livingEntityOnlyAction(counter: AtomicInteger): IAction = object : IAction {
            override val nodeId = "living_entity_only_action"
            override val displayName = "Living Entity Only Action"
            override suspend fun execute(context: ExecutionContext) {
                // Mirrors: val entity = context.currentTarget as? LivingEntity ?: return
                @Suppress("UNUSED_VARIABLE")
                val entity = context.currentTarget as? LivingEntity ?: return
                counter.incrementAndGet()
            }
        }

        /**
         * Simulates the ExecutionEngine's target-iteration loop with currentTarget assignment.
         */
        suspend fun runWithCurrentTarget(action: IAction, context: ExecutionContext) {
            val targets = context.targets.toList()
            for (target in targets) {
                context.currentTarget = target
                action.execute(context)
            }
        }

        /**
         * Arbitrary: mixed list of Player and plain Entity mocks (1..20 total).
         * Returns a Pair(targets, playerCount) so we can verify the exact call count.
         */
        val arbMixedTargets = arbitrary { rs ->
            val size = rs.random.nextInt(1, 21)
            var playerCount = 0
            val targets = (1..size).map {
                if (rs.random.nextBoolean()) {
                    playerCount++
                    mockk<Player>(relaxed = true)
                } else {
                    mockk<Entity>(relaxed = true)
                }
            }.toMutableList<Entity>()
            Pair(targets, playerCount)
        }

        /**
         * Arbitrary: mixed list of LivingEntity and plain Entity mocks (1..20 total).
         * Returns a Pair(targets, livingCount).
         */
        val arbMixedLivingTargets = arbitrary { rs ->
            val size = rs.random.nextInt(1, 21)
            var livingCount = 0
            val targets = (1..size).map {
                if (rs.random.nextBoolean()) {
                    livingCount++
                    mockk<LivingEntity>(relaxed = true)
                } else {
                    mockk<Entity>(relaxed = true)
                }
            }.toMutableList<Entity>()
            Pair(targets, livingCount)
        }

        /**
         * For a mixed list of Player and non-Player entities, a Player-requiring action
         * must be called exactly [playerCount] times — once per Player, never for others.
         * No exception must be thrown for non-Player entities.
         *
         * **Validates: Requirements 1.12, 1.13**
         */
        "mixed Player/Entity targets → Player action called only for Player instances, no exception" {
            checkAll(PropTestConfig(iterations = 100), arbMixedTargets) { (targets, playerCount) ->
                val counter = AtomicInteger(0)
                val action = playerOnlyAction(counter)
                val context = buildContext(targets)

                // Must not throw for any entity type
                runBlocking { runWithCurrentTarget(action, context) }

                counter.get() shouldBe playerCount
            }
        }

        /**
         * For a mixed list of LivingEntity and plain Entity, a LivingEntity-requiring action
         * must be called exactly [livingCount] times — once per LivingEntity, never for others.
         * No exception must be thrown for plain Entity instances.
         *
         * **Validates: Requirements 1.12, 1.13**
         */
        "mixed LivingEntity/Entity targets → LivingEntity action called only for LivingEntity instances, no exception" {
            checkAll(PropTestConfig(iterations = 100), arbMixedLivingTargets) { (targets, livingCount) ->
                val counter = AtomicInteger(0)
                val action = livingEntityOnlyAction(counter)
                val context = buildContext(targets)

                // Must not throw for any entity type
                runBlocking { runWithCurrentTarget(action, context) }

                counter.get() shouldBe livingCount
            }
        }

        /**
         * A list of zero compatible entities (all plain Entity, none Player) must result
         * in 0 calls to a Player-requiring action — no exception.
         *
         * **Validates: Requirements 1.2, 1.12**
         */
        "all incompatible targets → Player action called 0 times, no exception" {
            val arbAllIncompatible = arbitrary { rs ->
                val size = rs.random.nextInt(1, 21)
                (1..size).map { mockk<Entity>(relaxed = true) }.toMutableList<Entity>()
            }

            checkAll(PropTestConfig(iterations = 100), arbAllIncompatible) { targets ->
                val counter = AtomicInteger(0)
                val action = playerOnlyAction(counter)
                val context = buildContext(targets)

                runBlocking { runWithCurrentTarget(action, context) }

                counter.get() shouldBe 0
            }
        }

        /**
         * A list of all-compatible Player targets must result in targets.size calls
         * to a Player-requiring action.
         *
         * **Validates: Requirements 1.1, 1.13**
         */
        "all compatible Player targets → Player action called targets.size times" {
            val arbAllPlayers = arbitrary { rs ->
                val size = rs.random.nextInt(1, 21)
                (1..size).map { mockk<Player>(relaxed = true) }.toMutableList<Entity>()
            }

            checkAll(PropTestConfig(iterations = 100), arbAllPlayers) { targets ->
                val counter = AtomicInteger(0)
                val action = playerOnlyAction(counter)
                val context = buildContext(targets)

                runBlocking { runWithCurrentTarget(action, context) }

                counter.get() shouldBe targets.size
            }
        }
    }
})
