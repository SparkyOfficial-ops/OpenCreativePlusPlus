// Feature: ocp-visual-programming-platform, Integration Test: SelectionToActionIntegrationTest

package com.opencreativeplus.plugin.node

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.execution.CoroutineConfiguration
import com.opencreativeplus.core.execution.ExecutionEngine
import com.opencreativeplus.core.execution.FunctionRegistry
import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.core.watchdog.Watchdog
import com.opencreativeplus.plugin.node.selection.SelectionNode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * Integration tests for the SelectionNode → ActionNode pipeline.
 *
 * Exercises the full execution path: [SelectionNode] modifies [ExecutionContext.targets],
 * then the [ExecutionEngine] iterates over those targets and calls the action once per target.
 *
 * Verifies:
 * - ALL_PLAYERS mode: action executes once per player in the world (Req 1.3, 1.8)
 * - KILLER mode: action executes once for the killer entity (Req 1.5, 1.8)
 * - VICTIM mode: action executes once for the victim entity (Req 1.6, 1.8)
 * - RADIUS mode: action executes once per nearby entity (Req 1.4, 1.8)
 * - Empty targets after selection: action is skipped entirely (Req 1.9)
 * - Targets replaced between two SelectionNodes: final count reflects last selection (Req 1.3, 1.8)
 *
 * Requirements: 1.3, 1.8
 */
class SelectionToActionIntegrationTest {

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

    private lateinit var engine: ExecutionEngine
    private lateinit var coroutineConfig: CoroutineConfiguration

    @BeforeEach
    fun setup() {
        val tpsMonitor = mockk<com.opencreativeplus.core.watchdog.TPSMonitor>()
        every { tpsMonitor.getCurrentTPS() } returns 20.0
        val watchdog = Watchdog(tpsMonitor)

        val db = mockk<com.mongodb.kotlin.client.coroutine.MongoDatabase>(relaxed = true)
        val variableManager = VariableManager(db)

        coroutineConfig = CoroutineConfiguration(syncRunner = { it() })

        engine = ExecutionEngine(
            watchdog = watchdog,
            variableManager = variableManager,
            coroutineConfig = coroutineConfig,
            functionRegistry = FunctionRegistry()
        )
    }

    @AfterEach
    fun teardown() {
        coroutineConfig.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun mockEvent(): IEvent {
        val e = mockk<IEvent>()
        every { e.nodeId } returns "test_event"
        every { e.displayName } returns "Test Event"
        every { e.eventType } returns "test"
        return e
    }

    private fun script(vararg actions: IAction): CompiledScript =
        CompiledScript(event = mockEvent(), actions = actions.toList(), sourceLocation = "test@0,0,0")

    /** Creates a counting action that increments [counter] each time it is executed. */
    private fun countingAction(counter: AtomicInteger): IAction = object : IAction {
        override val nodeId = "counting_action"
        override val displayName = "Counting Action"
        override suspend fun execute(context: ExecutionContext) {
            counter.incrementAndGet()
        }
    }

    private fun mockPlayer(): Player {
        val p = mockk<Player>(relaxed = true)
        every { p.uniqueId } returns UUID.randomUUID()
        return p
    }

    // =========================================================================
    // 1. ALL_PLAYERS mode → action executes once per player (Req 1.3, 1.8)
    // =========================================================================

    /**
     * Verifies that when SelectionNode(ALL_PLAYERS) runs, it replaces targets with all
     * players in the world, and the subsequent action is called exactly once per player.
     *
     * Setup: world with 3 players; initial context has 1 player (the trigger).
     * SelectionNode runs once (for the 1 initial target), sets targets to 3 world players.
     * countingAction then runs 3 times.
     *
     * Requirements: 1.3, 1.8
     */
    @Test
    fun `ALL_PLAYERS mode - action executes once per player in world (Req 1_3, 1_8)`() = runBlocking {
        // Given: a world with 3 players
        val world = mockk<World>(relaxed = true)
        val p1 = mockPlayer()
        val p2 = mockPlayer()
        val p3 = mockPlayer()
        val worldPlayers = listOf(p1, p2, p3)

        val triggerPlayer = mockPlayer()
        every { triggerPlayer.world } returns world
        every { world.players } returns worldPlayers

        val counter = AtomicInteger(0)
        val testScript = script(
            SelectionNode(SelectionNode.SelectionMode.ALL_PLAYERS),
            countingAction(counter)
        )

        // When
        engine.executeScript(testScript, UUID.randomUUID(), triggerPlayer, emptyMap())
        delay(200)

        // Then: countingAction was called exactly 3 times (once per world player)
        assertEquals(3, counter.get(), "Action should execute once per player in world. Got: ${counter.get()}")
    }

    // =========================================================================
    // 2. KILLER mode → action executes once for the killer (Req 1.5, 1.8)
    // =========================================================================

    /**
     * Verifies that when SelectionNode(KILLER) runs with a killer in eventData,
     * targets becomes [killer] and the action is called exactly once.
     *
     * Requirements: 1.5, 1.8
     */
    @Test
    fun `KILLER mode - action executes once for the killer entity (Req 1_5, 1_8)`() = runBlocking {
        // Given: a killer entity in eventData
        val killer = mockk<Entity>(relaxed = true)
        val triggerPlayer = mockPlayer()

        val counter = AtomicInteger(0)
        val testScript = script(
            SelectionNode(SelectionNode.SelectionMode.KILLER),
            countingAction(counter)
        )

        // When
        engine.executeScript(testScript, UUID.randomUUID(), triggerPlayer, mapOf("killer" to killer))
        delay(200)

        // Then: countingAction was called exactly 1 time
        assertEquals(1, counter.get(), "Action should execute once for the killer. Got: ${counter.get()}")
    }

    // =========================================================================
    // 3. VICTIM mode → action executes once for the victim (Req 1.6, 1.8)
    // =========================================================================

    /**
     * Verifies that when SelectionNode(VICTIM) runs with a victim in eventData,
     * targets becomes [victim] and the action is called exactly once.
     *
     * Requirements: 1.6, 1.8
     */
    @Test
    fun `VICTIM mode - action executes once for the victim entity (Req 1_6, 1_8)`() = runBlocking {
        // Given: a victim entity in eventData
        val victim = mockk<Entity>(relaxed = true)
        val triggerPlayer = mockPlayer()

        val counter = AtomicInteger(0)
        val testScript = script(
            SelectionNode(SelectionNode.SelectionMode.VICTIM),
            countingAction(counter)
        )

        // When
        engine.executeScript(testScript, UUID.randomUUID(), triggerPlayer, mapOf("victim" to victim))
        delay(200)

        // Then: countingAction was called exactly 1 time
        assertEquals(1, counter.get(), "Action should execute once for the victim. Got: ${counter.get()}")
    }

    // =========================================================================
    // 4. RADIUS mode with multiple nearby entities → action executes once per entity (Req 1.4, 1.8)
    // =========================================================================

    /**
     * Verifies that when SelectionNode(RADIUS) runs and the world returns 4 nearby entities,
     * targets becomes those 4 entities and the action is called exactly 4 times.
     *
     * Requirements: 1.4, 1.8
     */
    @Test
    fun `RADIUS mode - action executes once per nearby entity (Req 1_4, 1_8)`() = runBlocking {
        // Given: a world with 4 nearby entities within radius 10
        val world = mockk<World>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val triggerPlayer = mockPlayer()

        val nearbyEntities = listOf(
            mockk<Entity>(relaxed = true),
            mockk<Entity>(relaxed = true),
            mockk<Entity>(relaxed = true),
            mockk<Entity>(relaxed = true)
        )

        every { triggerPlayer.location } returns location
        every { location.world } returns world
        every { world.getNearbyEntities(any<Location>(), any<Double>(), any<Double>(), any<Double>()) } returns nearbyEntities

        val counter = AtomicInteger(0)
        val testScript = script(
            SelectionNode(SelectionNode.SelectionMode.RADIUS, radius = 10.0),
            countingAction(counter)
        )

        // When
        engine.executeScript(testScript, UUID.randomUUID(), triggerPlayer, emptyMap())
        delay(200)

        // Then: countingAction was called exactly 4 times (once per nearby entity)
        assertEquals(4, counter.get(), "Action should execute once per nearby entity. Got: ${counter.get()}")
    }

    // =========================================================================
    // 5. Empty targets after selection → action is skipped (Req 1.9)
    // =========================================================================

    /**
     * Verifies that when SelectionNode(KILLER) runs but there is no killer in eventData,
     * targets becomes empty and the action is never called (silent skip, no exception).
     *
     * Requirements: 1.9
     */
    @Test
    fun `empty targets after KILLER selection - action is skipped (Req 1_9)`() = runBlocking {
        // Given: no killer in eventData → targets will be empty after SelectionNode
        val triggerPlayer = mockPlayer()

        val counter = AtomicInteger(0)
        val testScript = script(
            SelectionNode(SelectionNode.SelectionMode.KILLER),
            countingAction(counter)
        )

        // When: execute with no killer in eventData
        engine.executeScript(testScript, UUID.randomUUID(), triggerPlayer, emptyMap())
        delay(200)

        // Then: countingAction was never called (targets was empty → silent skip)
        assertEquals(0, counter.get(), "Action should be skipped when targets is empty. Got: ${counter.get()}")
    }

    // =========================================================================
    // 6. Targets replaced between two SelectionNodes (Req 1.3, 1.8)
    // =========================================================================

    /**
     * Verifies that when two SelectionNodes appear in sequence, the second one
     * replaces the targets set by the first, and the final action count reflects
     * the targets from the second SelectionNode.
     *
     * Execution flow:
     * - Initial targets: [triggerPlayer] (1 player)
     * - SelectionNode(KILLER) runs once → targets = [killer] (1 entity)
     * - SelectionNode(ALL_PLAYERS) runs once → targets = [p1, p2, p3] (3 players)
     * - countingAction runs 3 times
     *
     * Requirements: 1.3, 1.8
     */
    @Test
    fun `targets replaced between two SelectionNodes - final count reflects last selection (Req 1_3, 1_8)`() = runBlocking {
        // Given: a killer in eventData AND a world with 3 players
        val world = mockk<World>(relaxed = true)
        val killer = mockk<Entity>(relaxed = true)
        val p1 = mockPlayer()
        val p2 = mockPlayer()
        val p3 = mockPlayer()
        val worldPlayers = listOf(p1, p2, p3)

        val triggerPlayer = mockPlayer()
        every { triggerPlayer.world } returns world
        every { world.players } returns worldPlayers

        val counter = AtomicInteger(0)
        val testScript = script(
            SelectionNode(SelectionNode.SelectionMode.KILLER),
            SelectionNode(SelectionNode.SelectionMode.ALL_PLAYERS),
            countingAction(counter)
        )

        // When
        engine.executeScript(testScript, UUID.randomUUID(), triggerPlayer, mapOf("killer" to killer))
        delay(200)

        // Then: countingAction was called 3 times (targets from ALL_PLAYERS, not KILLER)
        assertEquals(
            3,
            counter.get(),
            "Action should execute 3 times based on ALL_PLAYERS selection, not KILLER. Got: ${counter.get()}"
        )
    }
}
