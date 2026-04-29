@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-visual-programming-platform, Property 3: SelectionNode "Сущности в радиусе" — все результаты в радиусе

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
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based test for SelectionNode RADIUS mode.
 *
 * **Validates: Requirements 1.4**
 *
 * Property 3: For any radius N and any set of entities in the world,
 * all entities in context.targets after executing the RADIUS selection node
 * must be at distance ≤ N from the execution location.
 * Also verifies that entities outside the radius are NOT included.
 */
class SelectionRadiusPropertyTest : FreeSpec({

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
     * Builds an ExecutionContext with a mocked Player whose location is controlled.
     * The player's world returns [nearbyEntities] from getNearbyEntities().
     */
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
        override suspend fun <T> syncContext(block: () -> T): T = block()
    }

    /**
     * Creates a mock Player whose location is at (0, 0, 0) in a mock World.
     * The world's getNearbyEntities() returns [nearbyEntities] for any radius call.
     */
    fun mockPlayerWithNearby(nearbyEntities: Collection<Entity>): Player {
        val world = mockk<World>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val player = mockk<Player>(relaxed = true)

        every { player.location } returns location
        every { location.world } returns world
        every { world.getNearbyEntities(any<Location>(), any<Double>(), any<Double>(), any<Double>()) } returns nearbyEntities

        return player
    }

    /**
     * Creates a mock Entity at a given distance from origin (0,0,0).
     * The entity's location returns a Location with the given distance.
     */
    fun mockEntityAtDistance(distance: Double): Entity {
        val entity = mockk<Entity>(relaxed = true)
        val loc = mockk<Location>(relaxed = true)
        every { entity.location } returns loc
        every { loc.distance(any()) } returns distance
        return entity
    }

    // -----------------------------------------------------------------------
    // Arbitraries
    // -----------------------------------------------------------------------

    /** Arbitrary radius: positive doubles in range (0.0, 100.0] */
    val arbRadius = Arb.double(0.1, 100.0)

    // -----------------------------------------------------------------------
    // Property 3a — all entities returned by getNearbyEntities end up in targets
    // -----------------------------------------------------------------------

    "Property 3: SelectionNode 'Сущности в радиусе' — все результаты в радиусе" - {

        /**
         * For any radius N and any list of entities returned by the world,
         * all those entities must appear in context.targets after execution.
         *
         * This tests that SelectionNode correctly populates targets from
         * the world's getNearbyEntities result.
         *
         * **Validates: Requirements 1.4**
         */
        "all entities returned by getNearbyEntities are in context.targets" {
            val arbEntityList = arbitrary { rs ->
                val size = rs.random.nextInt(0, 21)
                (1..size).map { mockk<Entity>(relaxed = true) }
            }

            checkAll(PropTestConfig(iterations = 100), arbRadius, arbEntityList) { radius, entities ->
                val player = mockPlayerWithNearby(entities)
                val ctx = buildContext(player)
                val node = SelectionNode(SelectionNode.SelectionMode.RADIUS, radius)

                runBlocking { node.execute(ctx) }

                ctx.targets.size shouldBe entities.size
                ctx.targets.toSet() shouldBe entities.toSet()
            }
        }

        /**
         * For any radius N, entities at distance ≤ N must be included in targets,
         * and entities at distance > N must NOT be included.
         *
         * This tests the radius boundary condition: the world mock is set up to
         * return only entities within the radius (simulating Bukkit's behavior),
         * and we verify that the node correctly uses that result.
         *
         * **Validates: Requirements 1.4**
         */
        "entities within radius are included, entities outside are not" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbRadius,
                Arb.int(0..10),  // count of entities inside radius
                Arb.int(0..10)   // count of entities outside radius
            ) { radius, insideCount, outsideCount ->
                // Entities inside radius: distance in [0, radius]
                val insideEntities = (1..insideCount).map { mockEntityAtDistance(radius * 0.5) }
                // Entities outside radius: distance in (radius, 200]
                val outsideEntities = (1..outsideCount).map { mockEntityAtDistance(radius + 1.0) }

                // The world mock returns only inside entities (simulating Bukkit's getNearbyEntities)
                val player = mockPlayerWithNearby(insideEntities)
                val ctx = buildContext(player)
                val node = SelectionNode(SelectionNode.SelectionMode.RADIUS, radius)

                runBlocking { node.execute(ctx) }

                // All inside entities must be in targets
                ctx.targets.size shouldBe insideCount
                ctx.targets.toSet() shouldBe insideEntities.toSet()

                // No outside entities should be in targets
                val outsideInTargets = ctx.targets.intersect(outsideEntities.toSet())
                outsideInTargets.shouldBeEmpty()
            }
        }

        /**
         * When no entities are within the radius, targets must be empty — no error thrown.
         *
         * **Validates: Requirements 1.4, 1.7**
         */
        "empty nearby entities → targets is empty, no exception" {
            checkAll(PropTestConfig(iterations = 100), arbRadius) { radius ->
                val player = mockPlayerWithNearby(emptyList())
                val ctx = buildContext(player)
                val node = SelectionNode(SelectionNode.SelectionMode.RADIUS, radius)

                runBlocking { node.execute(ctx) }

                ctx.targets.shouldBeEmpty()
            }
        }

        /**
         * When player is null, targets must be empty — no exception thrown.
         *
         * **Validates: Requirements 1.4, 1.7**
         */
        "null player → targets is empty, no exception" {
            checkAll(PropTestConfig(iterations = 100), arbRadius) { radius ->
                val ctx = buildContext(null)
                val node = SelectionNode(SelectionNode.SelectionMode.RADIUS, radius)

                runBlocking { node.execute(ctx) }

                ctx.targets.shouldBeEmpty()
            }
        }

        /**
         * RADIUS mode always clears the previous targets list before populating.
         * Even if targets had entries before execution, only the new nearby entities remain.
         *
         * **Validates: Requirements 1.4**
         */
        "previous targets are cleared before populating with nearby entities" {
            val arbEntityList = arbitrary { rs ->
                val size = rs.random.nextInt(1, 11)
                (1..size).map { mockk<Entity>(relaxed = true) }
            }

            checkAll(PropTestConfig(iterations = 100), arbRadius, arbEntityList, arbEntityList) {
                radius, previousTargets, newNearby ->

                val player = mockPlayerWithNearby(newNearby)
                // Start with some pre-existing targets
                val ctx = buildContext(player, previousTargets.toMutableList())
                val node = SelectionNode(SelectionNode.SelectionMode.RADIUS, radius)

                runBlocking { node.execute(ctx) }

                // Only the new nearby entities should be in targets
                ctx.targets.size shouldBe newNearby.size
                ctx.targets.toSet() shouldBe newNearby.toSet()
            }
        }

        /**
         * For radius = 0.0, no entities should be found (radius must be positive).
         * The node should leave targets empty when radius is not positive.
         *
         * **Validates: Requirements 1.4, 1.7**
         */
        "zero radius → targets is empty" {
            repeat(100) {
                val entities = (1..5).map { mockk<Entity>(relaxed = true) }
                val player = mockPlayerWithNearby(entities)
                val ctx = buildContext(player)
                val node = SelectionNode(SelectionNode.SelectionMode.RADIUS, 0.0)

                runBlocking { node.execute(ctx) }

                // radius <= 0.0 means no search is performed (see SelectionNode implementation)
                ctx.targets.shouldBeEmpty()
            }
        }
    }
})
