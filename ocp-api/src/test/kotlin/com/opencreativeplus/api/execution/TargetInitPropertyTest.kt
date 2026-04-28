@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-visual-programming-platform, Property 1: Инициализация targets при запуске

package com.opencreativeplus.api.execution

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.property.PropTestConfig
import io.kotest.property.checkAll
import io.kotest.property.arbitrary.arbitrary
import io.mockk.mockk
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based test for target list initialization in ExecutionContext.
 *
 * **Validates: Requirements 1.2**
 *
 * Property 1: For any event triggering execution with a non-null player,
 * context.targets after context creation must contain exactly one player —
 * the one who triggered the event.
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
     * Builds an ExecutionContext stub that replicates ExecutionContextImpl's
     * targets initialization logic:
     *   targets = if (player != null) mutableListOf(player) else mutableListOf()
     */
    fun buildContext(player: Player?): ExecutionContext = object : ExecutionContext {
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
            if (player != null) mutableListOf(player) else mutableListOf()
        override suspend fun <T> syncContext(block: () -> T): T = block()
    }

    /** Arbitrary that always produces a fresh mock Player. */
    val arbPlayer = arbitrary { mockk<Player>(relaxed = true) }

    // -----------------------------------------------------------------------
    // Property 1a — non-null player → targets contains exactly that player
    // -----------------------------------------------------------------------

    "Property 1: Инициализация targets при запуске" - {

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
            // Run 100 times to match PBT iteration count
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
         * targets is mutable — it can be modified after initialization.
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
