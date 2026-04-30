package com.opencreativeplus.plugin.node

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.plugin.node.selection.SelectionNode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private class FakeScope : VariableScope {
    private val map = mutableMapOf<String, Any>()
    override fun get(name: String) = map[name]
    override fun set(name: String, value: Any) { map[name] = value }
    override fun has(name: String) = map.containsKey(name)
    override fun clear() = map.clear()
}

private fun buildContext(
    player: Player? = null,
    eventData: Map<String, Any> = emptyMap(),
    initialTargets: MutableList<Entity> = mutableListOf()
): ExecutionContext = object : ExecutionContext {
    override val plotId: UUID = UUID.randomUUID()
    override val player: Player? = player
    override val eventData: Map<String, Any> = eventData
    override val localScope: VariableScope = FakeScope()
    override val plotScope: VariableScope = FakeScope()
    override val savedScope: VariableScope = FakeScope()
    override val operationCount: AtomicInteger = AtomicInteger(0)
    override val callStackSize: AtomicInteger = AtomicInteger(0)
    override val targets: MutableList<Entity> = initialTargets
    override var currentTarget: Entity? = null
    override suspend fun <T> syncContext(block: () -> T): T = block()
}

// ---------------------------------------------------------------------------
// SelectionNodeTest — unit tests for KILLER, VICTIM, and empty RADIUS
// ---------------------------------------------------------------------------

/**
 * Unit tests for [SelectionNode] covering:
 * - KILLER mode: extracts killer entity from eventData (Req 1.5)
 * - VICTIM mode: extracts victim entity from eventData (Req 1.6)
 * - RADIUS mode with no nearby entities: targets stays empty, no exception (Req 1.7)
 */
class SelectionNodeTest {

    // -----------------------------------------------------------------------
    // KILLER mode — Requirements 1.5
    // -----------------------------------------------------------------------

    @Test
    fun `KILLER mode sets killer entity as sole target`() {
        val killer = mockk<Entity>(relaxed = true)
        val ctx = buildContext(eventData = mapOf("killer" to killer))
        val node = SelectionNode(SelectionNode.SelectionMode.KILLER)

        runBlocking { node.execute(ctx) }

        assertEquals(1, ctx.targets.size, "targets should contain exactly one entity")
        assertEquals(killer, ctx.targets[0], "the target should be the killer entity")
    }

    @Test
    fun `KILLER mode clears previous targets before setting killer`() {
        val previousTarget = mockk<Entity>(relaxed = true)
        val killer = mockk<Entity>(relaxed = true)
        val ctx = buildContext(
            eventData = mapOf("killer" to killer),
            initialTargets = mutableListOf(previousTarget)
        )
        val node = SelectionNode(SelectionNode.SelectionMode.KILLER)

        runBlocking { node.execute(ctx) }

        assertEquals(1, ctx.targets.size, "previous targets should be cleared")
        assertEquals(killer, ctx.targets[0])
    }

    @Test
    fun `KILLER mode leaves targets empty when killer is absent from eventData`() {
        val ctx = buildContext(eventData = emptyMap())
        val node = SelectionNode(SelectionNode.SelectionMode.KILLER)

        runBlocking { node.execute(ctx) }

        assertTrue(ctx.targets.isEmpty(), "targets should be empty when no killer in eventData")
    }

    @Test
    fun `KILLER mode leaves targets empty when killer value is not an Entity`() {
        val ctx = buildContext(eventData = mapOf("killer" to "not-an-entity"))
        val node = SelectionNode(SelectionNode.SelectionMode.KILLER)

        runBlocking { node.execute(ctx) }

        assertTrue(ctx.targets.isEmpty(), "targets should be empty when killer is wrong type")
    }

    // -----------------------------------------------------------------------
    // VICTIM mode — Requirements 1.6
    // -----------------------------------------------------------------------

    @Test
    fun `VICTIM mode sets victim entity as sole target`() {
        val victim = mockk<Entity>(relaxed = true)
        val ctx = buildContext(eventData = mapOf("victim" to victim))
        val node = SelectionNode(SelectionNode.SelectionMode.VICTIM)

        runBlocking { node.execute(ctx) }

        assertEquals(1, ctx.targets.size, "targets should contain exactly one entity")
        assertEquals(victim, ctx.targets[0], "the target should be the victim entity")
    }

    @Test
    fun `VICTIM mode clears previous targets before setting victim`() {
        val previousTarget = mockk<Entity>(relaxed = true)
        val victim = mockk<Entity>(relaxed = true)
        val ctx = buildContext(
            eventData = mapOf("victim" to victim),
            initialTargets = mutableListOf(previousTarget)
        )
        val node = SelectionNode(SelectionNode.SelectionMode.VICTIM)

        runBlocking { node.execute(ctx) }

        assertEquals(1, ctx.targets.size, "previous targets should be cleared")
        assertEquals(victim, ctx.targets[0])
    }

    @Test
    fun `VICTIM mode leaves targets empty when victim is absent from eventData`() {
        val ctx = buildContext(eventData = emptyMap())
        val node = SelectionNode(SelectionNode.SelectionMode.VICTIM)

        runBlocking { node.execute(ctx) }

        assertTrue(ctx.targets.isEmpty(), "targets should be empty when no victim in eventData")
    }

    @Test
    fun `VICTIM mode leaves targets empty when victim value is not an Entity`() {
        val ctx = buildContext(eventData = mapOf("victim" to 42))
        val node = SelectionNode(SelectionNode.SelectionMode.VICTIM)

        runBlocking { node.execute(ctx) }

        assertTrue(ctx.targets.isEmpty(), "targets should be empty when victim is wrong type")
    }

    // -----------------------------------------------------------------------
    // RADIUS mode with empty result — Requirements 1.7
    // -----------------------------------------------------------------------

    @Test
    fun `RADIUS mode with no nearby entities leaves targets empty without throwing`() {
        val world = mockk<World>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val player = mockk<Player>(relaxed = true)

        every { player.location } returns location
        every { location.world } returns world
        every { world.getNearbyEntities(any<Location>(), any<Double>(), any<Double>(), any<Double>()) } returns emptyList()

        val ctx = buildContext(player = player)
        val node = SelectionNode(SelectionNode.SelectionMode.RADIUS, radius = 10.0)

        runBlocking { node.execute(ctx) }

        assertTrue(ctx.targets.isEmpty(), "targets should be empty when no entities are nearby")
    }

    @Test
    fun `RADIUS mode with null player leaves targets empty without throwing`() {
        val ctx = buildContext(player = null)
        val node = SelectionNode(SelectionNode.SelectionMode.RADIUS, radius = 10.0)

        runBlocking { node.execute(ctx) }

        assertTrue(ctx.targets.isEmpty(), "targets should be empty when player is null")
    }

    @Test
    fun `RADIUS mode with zero radius leaves targets empty without throwing`() {
        val player = mockk<Player>(relaxed = true)
        val ctx = buildContext(player = player)
        val node = SelectionNode(SelectionNode.SelectionMode.RADIUS, radius = 0.0)

        runBlocking { node.execute(ctx) }

        assertTrue(ctx.targets.isEmpty(), "targets should be empty when radius is zero")
    }

    @Test
    fun `RADIUS mode clears previous targets when no entities are nearby`() {
        val world = mockk<World>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val previousTarget = mockk<Entity>(relaxed = true)

        every { player.location } returns location
        every { location.world } returns world
        every { world.getNearbyEntities(any<Location>(), any<Double>(), any<Double>(), any<Double>()) } returns emptyList()

        val ctx = buildContext(player = player, initialTargets = mutableListOf(previousTarget))
        val node = SelectionNode(SelectionNode.SelectionMode.RADIUS, radius = 10.0)

        runBlocking { node.execute(ctx) }

        assertTrue(ctx.targets.isEmpty(), "previous targets should be cleared even when no entities are nearby")
    }
}
