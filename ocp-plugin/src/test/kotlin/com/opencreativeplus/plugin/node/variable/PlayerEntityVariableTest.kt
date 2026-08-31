// Feature: gameready-enhancements, Property 2: Player/Entity оборачиваются в UUID при записи
// Feature: gameready-enhancements, Property 3: Round-trip разрешения PlayerVariable
package com.opencreativeplus.plugin.node.variable

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.model.EntityVariable
import com.opencreativeplus.api.model.PlayerVariable
import com.opencreativeplus.core.execution.ExecutionContextImpl
import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.core.execution.VariableScopeImpl
import com.opencreativeplus.core.serialization.EntityVariableCodec
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based tests for UUID-safe entity storage.
 *
 * **Property 2: Player/Entity оборачиваются в UUID при записи** — for any Player/Entity
 * written through [SetVariableNode] into any scope, the raw stored value must be a
 * [PlayerVariable]/[EntityVariable], never the live object.
 *
 * **Property 3: Round-trip разрешения PlayerVariable** — for any online player,
 * `resolveValue(PlayerVariable(player.uniqueId))` must return that player
 * (UUID-equivalent); offline/despawned lookups return null without exceptions.
 *
 * **Validates: Requirements 2.1, 2.2, 2.3, 2.5, 2.7, 2.8**
 */
class PlayerEntityVariableTest : FreeSpec({

    /** VariableManager stub: identity key resolution, no DB involved. */
    fun stubVariableManager(): VariableManager {
        val vm = mockk<VariableManager>(relaxed = true)
        every { vm.resolveVariableKey(any(), any()) } answers { firstArg() }
        return vm
    }

    /** ExecutionContext stub backed by real [VariableScopeImpl] scopes. */
    fun stubContext(
        local: VariableScopeImpl,
        plot: VariableScopeImpl,
        saved: VariableScopeImpl
    ): ExecutionContext {
        val ctx = mockk<ExecutionContext>(relaxed = true)
        every { ctx.player } returns null
        every { ctx.localScope } returns local
        every { ctx.plotScope } returns plot
        every { ctx.savedScope } returns saved
        return ctx
    }

    /** Real [ExecutionContextImpl] for exercising [ExecutionContextImpl.resolveValue]. */
    fun realContext(): ExecutionContextImpl = ExecutionContextImpl(
        plotId = UUID.randomUUID(),
        player = null,
        eventData = emptyMap(),
        localScope = VariableScopeImpl(),
        plotScope = VariableScopeImpl(),
        savedScope = VariableScopeImpl(),
        operationCount = AtomicInteger(0),
        syncDispatcher = Dispatchers.Unconfined
    )

    fun scopeFor(name: String, local: VariableScopeImpl, plot: VariableScopeImpl, saved: VariableScopeImpl) =
        when (name) {
            "local" -> local
            "saved" -> saved
            else -> plot
        }

    "Property 2: Player values are stored as PlayerVariable in any scope" {
        checkAll(Arb.uuid(), Arb.of("local", "plot", "saved")) { uuid, scopeName ->
            val player = mockk<Player> { every { uniqueId } returns uuid }
            val local = VariableScopeImpl()
            val plot = VariableScopeImpl()
            val saved = VariableScopeImpl()

            SetVariableNode(
                mapOf("name" to "v", "value" to player, "scope" to scopeName),
                stubVariableManager()
            ).execute(stubContext(local, plot, saved))

            // Raw Player must never be stored — only its UUID wrapper
            scopeFor(scopeName, local, plot, saved).get("v") shouldBe PlayerVariable(uuid)
        }
    }

    "Property 2: Entity values are stored as EntityVariable in any scope" {
        checkAll(Arb.uuid(), Arb.of("local", "plot", "saved")) { uuid, scopeName ->
            val entity = mockk<Entity> { every { uniqueId } returns uuid }
            val local = VariableScopeImpl()
            val plot = VariableScopeImpl()
            val saved = VariableScopeImpl()

            SetVariableNode(
                mapOf("name" to "v", "value" to entity, "scope" to scopeName),
                stubVariableManager()
            ).execute(stubContext(local, plot, saved))

            scopeFor(scopeName, local, plot, saved).get("v") shouldBe EntityVariable(uuid)
        }
    }

    "Property 2: non-entity values are stored unchanged" {
        checkAll(Arb.string(), Arb.int()) { str, num ->
            val local = VariableScopeImpl()
            val plot = VariableScopeImpl()
            val saved = VariableScopeImpl()
            val ctx = stubContext(local, plot, saved)
            val vm = stubVariableManager()

            SetVariableNode(mapOf("name" to "s", "value" to str, "scope" to "plot"), vm).execute(ctx)
            SetVariableNode(mapOf("name" to "n", "value" to num, "scope" to "plot"), vm).execute(ctx)

            plot.get("s") shouldBe str
            plot.get("n") shouldBe num
        }
    }

    "Property 3: PlayerVariable round-trip resolves to the same player" {
        mockkStatic(Bukkit::class)
        try {
            checkAll(Arb.uuid()) { uuid ->
                val player = mockk<Player> { every { uniqueId } returns uuid }
                every { Bukkit.getPlayer(uuid) } returns player

                realContext().resolveValue(PlayerVariable(uuid)) shouldBe player
            }
        } finally {
            unmockkStatic(Bukkit::class)
        }
    }

    "Property 3: offline PlayerVariable resolves to null without exception (Req 2.5)" {
        mockkStatic(Bukkit::class)
        try {
            checkAll(Arb.uuid()) { uuid ->
                every { Bukkit.getPlayer(uuid) } returns null

                realContext().resolveValue(PlayerVariable(uuid)).shouldBeNull()
            }
        } finally {
            unmockkStatic(Bukkit::class)
        }
    }

    "Property 3: EntityVariable round-trip resolves via Bukkit.getEntity (Req 2.4, 2.6)" {
        mockkStatic(Bukkit::class)
        try {
            checkAll(Arb.uuid()) { uuid ->
                val entity = mockk<Entity> { every { uniqueId } returns uuid }
                every { Bukkit.getEntity(uuid) } returnsMany listOf(entity, null)

                val ctx = realContext()
                // online entity → resolved
                ctx.resolveValue(EntityVariable(uuid)) shouldBe entity
                // despawned entity → null, no exception
                ctx.resolveValue(EntityVariable(uuid)).shouldBeNull()
            }
        } finally {
            unmockkStatic(Bukkit::class)
        }
    }

    "SavedScope codec round-trip preserves PlayerVariable/EntityVariable (Req 2.1, 2.2)" {
        checkAll(Arb.uuid()) { uuid ->
            EntityVariableCodec.decode(EntityVariableCodec.encode(PlayerVariable(uuid))) shouldBe PlayerVariable(uuid)
            EntityVariableCodec.decode(EntityVariableCodec.encode(EntityVariable(uuid))) shouldBe EntityVariable(uuid)
            // plain values pass through untouched
            EntityVariableCodec.decode(EntityVariableCodec.encode("text")) shouldBe "text"
            EntityVariableCodec.decode(EntityVariableCodec.encode(42)) shouldBe 42
        }
    }

    "GetVariableNode resolves stored PlayerVariable back to Player (Req 2.3)" {
        mockkStatic(Bukkit::class)
        try {
            checkAll(Arb.uuid()) { uuid ->
                val player = mockk<Player> { every { uniqueId } returns uuid }
                every { Bukkit.getPlayer(uuid) } returns player

                val plot = VariableScopeImpl()
                plot.set("hero", PlayerVariable(uuid))
                val ctx = mockk<ExecutionContext>(relaxed = true)
                every { ctx.player } returns null
                every { ctx.localScope } returns VariableScopeImpl()
                every { ctx.plotScope } returns plot
                every { ctx.savedScope } returns VariableScopeImpl()
                every { ctx.resolveValue(any()) } answers {
                    val raw = firstArg<Any?>()
                    if (raw is PlayerVariable) Bukkit.getPlayer(raw.uuid) else raw
                }

                runBlocking {
                    GetVariableNode(mapOf("name" to "hero"), stubVariableManager()).compute(ctx) shouldBe player
                }
            }
        } finally {
            unmockkStatic(Bukkit::class)
        }
    }
})
