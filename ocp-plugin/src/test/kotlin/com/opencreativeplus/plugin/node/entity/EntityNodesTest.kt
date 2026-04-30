package com.opencreativeplus.plugin.node.entity

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Fake helpers
// ---------------------------------------------------------------------------

private class FakeVariableScope : VariableScope {
    private val map = mutableMapOf<String, Any>()
    override fun get(name: String) = map[name]
    override fun set(name: String, value: Any) { map[name] = value }
    override fun has(name: String) = map.containsKey(name)
    override fun clear() = map.clear()
}

private class TrackingSyncContext(override val player: Player? = null) : ExecutionContext {
    override val plotId: UUID = UUID.randomUUID()
    override val eventData: Map<String, Any> = emptyMap()
    override val localScope: VariableScope = FakeVariableScope()
    override val plotScope: VariableScope = FakeVariableScope()
    override val savedScope: VariableScope = FakeVariableScope()
    override val operationCount: AtomicInteger = AtomicInteger(0)
    override val callStackSize: AtomicInteger = AtomicInteger(0)
    override val targets: MutableList<Entity> = mutableListOf()
    override var currentTarget: Entity? = null

    var syncContextCalled = false

    override suspend fun <T> syncContext(block: () -> T): T {
        syncContextCalled = true
        return block()
    }
}

// ---------------------------------------------------------------------------
// SpawnEntityNode tests (s: 5.7)
// ---------------------------------------------------------------------------

class SpawnEntityNodeTest {

    @Test
    fun `nodeId is spawn_entity and displayName is Spawn Entity`() {
        val node = SpawnEntityNode(mapOf("type" to "ZOMBIE", "location" to "loc"))
        assertEquals("spawn_entity", node.nodeId)
        assertEquals("Spawn Entity", node.displayName)
    }

    @Test
    fun `execute calls syncContext to spawn entity on main thread`() = runTest {
        val world = mockk<World>(relaxed = true)
        val loc = mockk<Location>(relaxed = true)
        every { loc.world } returns world

        val ctx = TrackingSyncContext()
        ctx.localScope.set("loc", loc)

        val node = SpawnEntityNode(mapOf("type" to "ZOMBIE", "location" to "loc"))
        node.execute(ctx)

        assertTrue(ctx.syncContextCalled, "syncContext must be called to spawn entity on main thread")
        verify { world.spawnEntity(loc, EntityType.ZOMBIE) }
    }

    @Test
    fun `execute does nothing when location variable is missing`() = runTest {
        val ctx = TrackingSyncContext()
        // no "loc" set in scope
        val node = SpawnEntityNode(mapOf("type" to "ZOMBIE", "location" to "loc"))
        node.execute(ctx) // should not throw
    }

    @Test
    fun `execute does nothing when entity type is unknown`() = runTest {
        val world = mockk<World>(relaxed = true)
        val loc = mockk<Location>(relaxed = true)
        every { loc.world } returns world

        val ctx = TrackingSyncContext()
        ctx.localScope.set("loc", loc)

        val node = SpawnEntityNode(mapOf("type" to "NOT_A_REAL_ENTITY", "location" to "loc"))
        node.execute(ctx) // should not throw, logs warning and returns
    }

    @Test
    fun `execute defaults to ZOMBIE when type param is missing`() = runTest {
        val world = mockk<World>(relaxed = true)
        val loc = mockk<Location>(relaxed = true)
        every { loc.world } returns world

        val ctx = TrackingSyncContext()
        ctx.localScope.set("loc", loc)

        val node = SpawnEntityNode(mapOf("location" to "loc"))
        node.execute(ctx)

        verify { world.spawnEntity(loc, EntityType.ZOMBIE) }
    }
}

// ---------------------------------------------------------------------------
// SetEntityAINode tests (s: 5.8)
// ---------------------------------------------------------------------------

class SetEntityAINodeTest {

    @Test
    fun `nodeId is set_entity_ai and displayName is Set Entity AI`() {
        val node = SetEntityAINode(mapOf("entity" to "mob", "ai" to false))
        assertEquals("set_entity_ai", node.nodeId)
        assertEquals("Set Entity AI", node.displayName)
    }

    @Test
    fun `execute with AI false calls setAI(false) via syncContext`() = runTest {
        val entity = mockk<LivingEntity>(relaxed = true)

        val ctx = TrackingSyncContext()
        ctx.localScope.set("mob", entity)

        val node = SetEntityAINode(mapOf("entity" to "mob", "ai" to false))
        node.execute(ctx)

        assertTrue(ctx.syncContextCalled, "syncContext must be called for SetEntityAI")
        verify { entity.setAI(false) }
    }

    @Test
    fun `execute with AI true calls setAI(true)`() = runTest {
        val entity = mockk<LivingEntity>(relaxed = true)

        val ctx = TrackingSyncContext()
        ctx.localScope.set("mob", entity)

        val node = SetEntityAINode(mapOf("entity" to "mob", "ai" to true))
        node.execute(ctx)

        verify { entity.setAI(true) }
    }

    @Test
    fun `execute defaults to AI enabled when ai param is missing`() = runTest {
        val entity = mockk<LivingEntity>(relaxed = true)

        val ctx = TrackingSyncContext()
        ctx.localScope.set("mob", entity)

        val node = SetEntityAINode(mapOf("entity" to "mob"))
        node.execute(ctx)

        verify { entity.setAI(true) }
    }

    @Test
    fun `execute does nothing when entity variable is missing`() = runTest {
        val ctx = TrackingSyncContext()
        val node = SetEntityAINode(mapOf("entity" to "mob", "ai" to false))
        node.execute(ctx) // should not throw
    }

    @Test
    fun `execute does nothing when entity is not a LivingEntity`() = runTest {
        val entity = mockk<Entity>(relaxed = true) // not LivingEntity

        val ctx = TrackingSyncContext()
        ctx.localScope.set("mob", entity)

        val node = SetEntityAINode(mapOf("entity" to "mob", "ai" to false))
        node.execute(ctx) // should not throw, cast fails silently
    }
}
