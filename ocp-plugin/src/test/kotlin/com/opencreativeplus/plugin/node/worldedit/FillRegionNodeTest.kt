package com.opencreativeplus.plugin.node.worldedit

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.util.BoundingBox
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for FillRegionNode.
 *
 * Verifies:
 * - syncContext is called for every batch of block changes (Req 9.4)
 * - Watchdog receives the total block count via operationCount before execution (Req 9.2)
 *
 * Requirements: 9.1, 9.2, 9.3, 9.4, 9.5
 */
class FillRegionNodeTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private class FakeVariableScope : VariableScope {
        private val map = mutableMapOf<String, Any>()
        override fun get(name: String) = map[name]
        override fun set(name: String, value: Any) { map[name] = value }
        override fun has(name: String) = map.containsKey(name)
        override fun clear() = map.clear()
    }

    /**
     * A fake ExecutionContext that records how many times syncContext was called
     * and executes the block immediately (simulating main-thread dispatch).
     */
    private open class TrackingSyncContext : ExecutionContext {
        override val plotId: UUID = UUID.randomUUID()
        override val player = null
        override val eventData: Map<String, Any> = emptyMap()
        override val localScope: VariableScope = FakeVariableScope()
        override val plotScope: VariableScope = FakeVariableScope()
        override val savedScope: VariableScope = FakeVariableScope()
        override val operationCount: AtomicInteger = AtomicInteger(0)
        override val callStackSize: AtomicInteger = AtomicInteger(0)

        var syncContextCallCount = 0

        override suspend fun <T> syncContext(block: () -> T): T {
            syncContextCallCount++
            return block()
        }
    }

    /** Creates a mock World where getBlockAt returns a relaxed mock Block. */
    private fun mockWorld(): World {
        val world = mockk<World>(relaxed = true)
        val block = mockk<Block>(relaxed = true)
        io.mockk.every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns block
        return world
    }

    /** Builds a Location backed by the given world at the specified coordinates. */
    private fun loc(world: World, x: Int, y: Int, z: Int) =
        Location(world, x.toDouble(), y.toDouble(), z.toDouble())

    // =========================================================================
    // syncContext is called (Req 9.4)
    // =========================================================================

    @Test
    fun `syncContext is called when executing a small region`() = runTest {
        // Given: a 2x2x2 region (8 blocks — fits in a single batch)
        val world = mockWorld()
        val ctx = TrackingSyncContext()
        ctx.localScope.set("c1", loc(world, 0, 0, 0))
        ctx.localScope.set("c2", loc(world, 1, 1, 1))

        val node = FillRegionNode(
            mapOf("corner1" to "c1", "corner2" to "c2", "material" to "STONE")
        )

        // When
        node.execute(ctx)

        // Then: syncContext was called at least once (one batch)
        assertTrue(ctx.syncContextCallCount >= 1, "syncContext should be called for block changes")
    }

    @Test
    fun `syncContext is called once per batch for large regions`() = runTest {
        // Given: a region with exactly 10000 blocks → 2 batches of 5000
        // Use a 10x10x100 region = 10000 blocks
        val world = mockWorld()
        val ctx = TrackingSyncContext()
        ctx.localScope.set("c1", loc(world, 0, 0, 0))
        ctx.localScope.set("c2", loc(world, 9, 9, 99))  // 10*10*100 = 10000

        val node = FillRegionNode(
            mapOf("corner1" to "c1", "corner2" to "c2", "material" to "STONE")
        )

        // When
        node.execute(ctx)

        // Then: syncContext called exactly 2 times (one per batch)
        assertEquals(2, ctx.syncContextCallCount, "syncContext should be called once per batch")
    }

    @Test
    fun `syncContext is called once for a region of exactly 5000 blocks`() = runTest {
        // Given: 5000 blocks exactly → 1 batch
        // 10x10x50 = 5000
        val world = mockWorld()
        val ctx = TrackingSyncContext()
        ctx.localScope.set("c1", loc(world, 0, 0, 0))
        ctx.localScope.set("c2", loc(world, 9, 9, 49))

        val node = FillRegionNode(
            mapOf("corner1" to "c1", "corner2" to "c2", "material" to "STONE")
        )

        node.execute(ctx)

        assertEquals(1, ctx.syncContextCallCount, "Exactly 5000 blocks should use a single batch")
    }

    // =========================================================================
    // Watchdog receives block count (Req 9.2)
    // =========================================================================

    @Test
    fun `operationCount is incremented by the total block count before execution`() = runTest {
        // Given: a 3x3x3 region = 27 blocks
        val world = mockWorld()
        val ctx = TrackingSyncContext()
        ctx.localScope.set("c1", loc(world, 0, 0, 0))
        ctx.localScope.set("c2", loc(world, 2, 2, 2))

        val node = FillRegionNode(
            mapOf("corner1" to "c1", "corner2" to "c2", "material" to "STONE")
        )

        // When
        node.execute(ctx)

        // Then: operationCount reflects the 27 blocks
        assertEquals(27, ctx.operationCount.get(), "Watchdog should receive the total block count")
    }

    @Test
    fun `operationCount is incremented by correct count for a large region`() = runTest {
        // Given: 10x10x100 = 10000 blocks
        val world = mockWorld()
        val ctx = TrackingSyncContext()
        ctx.localScope.set("c1", loc(world, 0, 0, 0))
        ctx.localScope.set("c2", loc(world, 9, 9, 99))

        val node = FillRegionNode(
            mapOf("corner1" to "c1", "corner2" to "c2", "material" to "STONE")
        )

        node.execute(ctx)

        assertEquals(10_000, ctx.operationCount.get(), "Watchdog should receive 10000 as block count")
    }

    @Test
    fun `operationCount is incremented before any syncContext call`() = runTest {
        // Given: a region where we can observe the order of operationCount increment vs syncContext
        val world = mockWorld()
        val operationCountAtFirstSync = AtomicInteger(-1)

        val ctx = object : TrackingSyncContext() {
            override suspend fun <T> syncContext(block: () -> T): T {
                // Capture operationCount value on the first syncContext call
                if (syncContextCallCount == 0) {
                    operationCountAtFirstSync.set(operationCount.get())
                }
                return super.syncContext(block)
            }
        }
        ctx.localScope.set("c1", loc(world, 0, 0, 0))
        ctx.localScope.set("c2", loc(world, 2, 2, 2))  // 27 blocks

        val node = FillRegionNode(
            mapOf("corner1" to "c1", "corner2" to "c2", "material" to "STONE")
        )

        node.execute(ctx)

        // Then: operationCount was already set to 27 before the first syncContext call
        assertEquals(27, operationCountAtFirstSync.get(),
            "operationCount must be incremented before syncContext is called")
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun `execute does nothing when corner1 variable is missing`() = runTest {
        val ctx = TrackingSyncContext()
        ctx.localScope.set("c2", loc(mockWorld(), 2, 2, 2))

        val node = FillRegionNode(
            mapOf("corner1" to "c1", "corner2" to "c2", "material" to "STONE")
        )

        node.execute(ctx)

        assertEquals(0, ctx.syncContextCallCount, "No syncContext calls when corner1 is missing")
        assertEquals(0, ctx.operationCount.get(), "No operationCount increment when corner1 is missing")
    }

    @Test
    fun `execute does nothing when material is invalid`() = runTest {
        val world = mockWorld()
        val ctx = TrackingSyncContext()
        ctx.localScope.set("c1", loc(world, 0, 0, 0))
        ctx.localScope.set("c2", loc(world, 2, 2, 2))

        val node = FillRegionNode(
            mapOf("corner1" to "c1", "corner2" to "c2", "material" to "NOT_A_REAL_MATERIAL")
        )

        node.execute(ctx)

        assertEquals(0, ctx.syncContextCallCount, "No syncContext calls for invalid material")
    }

    @Test
    fun `clamped region that becomes empty does not call syncContext`() = runTest {
        // Given: region entirely outside plot bounds → clamped to empty
        val world = mockWorld()
        val ctx = TrackingSyncContext()
        ctx.localScope.set("c1", loc(world, 100, 0, 100))
        ctx.localScope.set("c2", loc(world, 110, 10, 110))

        // Plot bounds that don't overlap with the region at all
        val plotBounds = BoundingBox(0.0, 0.0, 0.0, 50.0, 50.0, 50.0)

        val node = FillRegionNode(
            mapOf("corner1" to "c1", "corner2" to "c2", "material" to "STONE", "plotBounds" to plotBounds)
        )

        node.execute(ctx)

        assertEquals(0, ctx.syncContextCallCount, "No syncContext calls when clamped region is empty")
        assertEquals(0, ctx.operationCount.get(), "No operationCount increment for empty clamped region")
    }
}
