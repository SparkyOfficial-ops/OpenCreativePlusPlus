@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-mvp2-core-systems, Property 3: DEFAULT mode сбрасывает targets к player

package com.opencreativeplus.plugin.node

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.plugin.node.selection.SelectionNode
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based test for SelectionNode DEFAULT mode.
 *
 * **Validates: Requirements 2.2, 2.5**
 *
 * Property 3: DEFAULT mode сбрасывает targets к player.
 * For any ExecutionContext with an arbitrary pre-existing targets list and a non-null
 * context.player, executing SelectionNode(DEFAULT) must result in targets containing
 * exactly one element — context.player.
 */
class SelectionDefaultPropertyTest : FreeSpec({

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

    fun buildContext(
        player: Player?,
        initialTargets: MutableList<Entity> = mutableListOf()
    ): ExecutionContext = object : ExecutionContext {
        override val plotId: UUID = UUID.randomUUID()
        override val player: Player? = player
        override val eventData: Map<String, Any> = emptyMap()
        override val localScope: VariableScope = mapScope()
        override val plotScope: VariableScope = mapScope()
        override val savedScope: VariableScope = mapScope()
        override val operationCount: AtomicInteger = AtomicInteger(0)
        override val callStackSize: AtomicInteger = AtomicInteger(0)
        override val targets: MutableList<Entity> = initialTargets
        override var currentTarget: Entity? = null
        override suspend fun <T> syncContext(block: () -> T): T = block()
    }

    // -----------------------------------------------------------------------
    // Arbitraries
    // -----------------------------------------------------------------------

    /** Arbitrary list of 0..20 mock entities representing pre-existing targets. */
    val arbTargetList = Arb.list(
        arbitrary { mockk<Entity>(relaxed = true) },
        0..20
    )

    // -----------------------------------------------------------------------
    // Property 3a — non-null player: targets becomes [player]
    // -----------------------------------------------------------------------

    "Property 3: DEFAULT mode сбрасывает targets к player" - {

        /**
         * For any non-null Player and any arbitrary list of pre-existing targets (0..20 entities),
         * after DEFAULT mode executes: targets.size == 1 and targets[0] == context.player.
         *
         * **Validates: Requirements 2.2, 2.5**
         */
        "non-null player: targets contains exactly [player] after DEFAULT" {
            checkAll(PropTestConfig(iterations = 100), arbTargetList) { previousTargets ->
                val player = mockk<Player>(relaxed = true)
                val ctx = buildContext(player, previousTargets.toMutableList())
                val node = SelectionNode(SelectionNode.SelectionMode.DEFAULT)

                runBlocking { node.execute(ctx) }

                ctx.targets.size shouldBe 1
                ctx.targets[0] shouldBe player
            }
        }

        /**
         * For any null player and any arbitrary list of pre-existing targets,
         * after DEFAULT mode executes: targets is empty, no exception thrown.
         *
         * **Validates: Requirements 2.3**
         */
        "null player: targets is empty after DEFAULT, no exception" {
            checkAll(PropTestConfig(iterations = 100), arbTargetList) { previousTargets ->
                val ctx = buildContext(null, previousTargets.toMutableList())
                val node = SelectionNode(SelectionNode.SelectionMode.DEFAULT)

                runBlocking { node.execute(ctx) }

                ctx.targets.shouldBeEmpty()
            }
        }

        /**
         * Idempotency: running DEFAULT mode twice in a row produces the same result
         * as running it once — targets == [player].
         *
         * **Validates: Requirements 2.2, 2.5**
         */
        "idempotency: running DEFAULT twice gives same result as once" {
            checkAll(PropTestConfig(iterations = 100), arbTargetList) { previousTargets ->
                val player = mockk<Player>(relaxed = true)
                val ctx = buildContext(player, previousTargets.toMutableList())
                val node = SelectionNode(SelectionNode.SelectionMode.DEFAULT)

                // Run twice
                runBlocking {
                    node.execute(ctx)
                    node.execute(ctx)
                }

                ctx.targets.size shouldBe 1
                ctx.targets[0] shouldBe player
            }
        }
    }
})
