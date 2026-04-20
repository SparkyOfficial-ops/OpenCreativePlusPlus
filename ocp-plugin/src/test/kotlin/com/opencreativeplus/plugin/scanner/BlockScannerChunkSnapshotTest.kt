package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.registry.NodeRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Chunk
import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bug 5 fix-check and preservation tests for BlockScanner.scanArea.
 *
 * Fix check  (5.4): scanning a 32×32 area uses ChunkSnapshot instead of per-block getBlockAt.
 * Preservation (5.5): scanning a single-chunk area returns correct block data.
 */
class BlockScannerChunkSnapshotTest {

    private val world = mockk<World>(relaxed = true)
    private val nodeRegistry = mockk<NodeRegistry>(relaxed = true)
    private val scanner = BlockScanner(world, nodeRegistry)

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Build a mock ChunkSnapshot that returns [material] for every coordinate query.
     */
    private fun uniformSnapshot(material: Material): ChunkSnapshot {
        val snapshot = mockk<ChunkSnapshot>(relaxed = true)
        every { snapshot.getBlockType(any(), any(), any()) } returns material
        return snapshot
    }

    /**
     * Wire [world.getChunkAt(cx, cz)] to return a chunk whose snapshot is [snapshot].
     */
    private fun stubChunk(cx: Int, cz: Int, snapshot: ChunkSnapshot) {
        val chunk = mockk<Chunk>(relaxed = true)
        every { world.getChunkAt(cx, cz) } returns chunk
        every { chunk.getChunkSnapshot() } returns snapshot
    }

    // -----------------------------------------------------------------------
    // 5.4 — Fix check: 32×32 area uses ChunkSnapshot, not per-block getBlockAt
    // -----------------------------------------------------------------------

    /**
     * A 32×32 area spans exactly 4 chunks (2×2 in chunk space: cx=0,1 × cz=0,1).
     * After scanArea the implementation must have called getChunkAt for each chunk
     * and must NOT have called world.getBlockAt for individual blocks.
     */
    @Test
    fun `scanArea over 32x32 region uses ChunkSnapshot and not per-block getBlockAt (fix check)`() {
        // Chunks (0,0), (1,0), (0,1), (1,1) cover x=0..31, z=0..31
        val snapshot00 = uniformSnapshot(Material.STONE)
        val snapshot10 = uniformSnapshot(Material.DIRT)
        val snapshot01 = uniformSnapshot(Material.GRASS_BLOCK)
        val snapshot11 = uniformSnapshot(Material.SAND)

        stubChunk(0, 0, snapshot00)
        stubChunk(1, 0, snapshot10)
        stubChunk(0, 1, snapshot01)
        stubChunk(1, 1, snapshot11)

        val result = scanner.scanArea(0..31, 64..64, 0..31)

        // Result must contain all 32*32 = 1024 entries
        assertEquals(1024, result.size, "Expected one entry per block coordinate")

        // Verify getChunkAt was called exactly once per chunk (4 chunks total)
        verify(exactly = 1) { world.getChunkAt(0, 0) }
        verify(exactly = 1) { world.getChunkAt(1, 0) }
        verify(exactly = 1) { world.getChunkAt(0, 1) }
        verify(exactly = 1) { world.getChunkAt(1, 1) }

        // world.getBlockAt(Int, Int, Int) must NOT have been called at all
        verify(exactly = 0) { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
    }

    /**
     * Verify that the material returned for each coordinate matches the snapshot
     * of the chunk that owns that coordinate.
     */
    @Test
    fun `scanArea returns correct material per chunk region (fix check)`() {
        val snapshot00 = uniformSnapshot(Material.STONE)   // chunk (0,0): x=0..15, z=0..15
        val snapshot10 = uniformSnapshot(Material.DIRT)    // chunk (1,0): x=16..31, z=0..15

        stubChunk(0, 0, snapshot00)
        stubChunk(1, 0, snapshot10)

        val result = scanner.scanArea(0..31, 64..64, 0..15)

        // Blocks in chunk (0,0) → STONE
        assertEquals(Material.STONE, result[Triple(0, 64, 0)])
        assertEquals(Material.STONE, result[Triple(15, 64, 15)])

        // Blocks in chunk (1,0) → DIRT
        assertEquals(Material.DIRT, result[Triple(16, 64, 0)])
        assertEquals(Material.DIRT, result[Triple(31, 64, 15)])
    }

    // -----------------------------------------------------------------------
    // 5.5 — Preservation: single-chunk scan returns correct data
    // -----------------------------------------------------------------------

    /**
     * Scanning a 1×1 area (single block) inside one chunk must return the correct
     * material from the snapshot without touching any other chunk.
     */
    @Test
    fun `scanArea over single block returns correct material (preservation)`() {
        val snapshot = mockk<ChunkSnapshot>(relaxed = true)
        // Register catch-all first, then specific stub (mockk matches last-registered first)
        every { snapshot.getBlockType(any(), any(), any()) } returns Material.AIR
        every { snapshot.getBlockType(5, 64, 3) } returns Material.GOLD_BLOCK

        stubChunk(0, 0, snapshot)

        val result = scanner.scanArea(5..5, 64..64, 3..3)

        assertEquals(1, result.size)
        assertEquals(Material.GOLD_BLOCK, result[Triple(5, 64, 3)])
        // Only one chunk accessed
        verify(exactly = 1) { world.getChunkAt(0, 0) }
        verify(exactly = 0) { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
    }

    /**
     * Scanning a 16×16 area that fits entirely within one chunk must issue
     * exactly one getChunkAt call and return all 256 entries.
     */
    @Test
    fun `scanArea over single chunk returns all 256 entries with one snapshot (preservation)`() {
        val snapshot = uniformSnapshot(Material.COBBLESTONE)
        stubChunk(0, 0, snapshot)

        val result = scanner.scanArea(0..15, 70..70, 0..15)

        assertEquals(256, result.size, "Expected 16*16 = 256 entries")
        assertTrue(result.values.all { it == Material.COBBLESTONE })
        verify(exactly = 1) { world.getChunkAt(0, 0) }
        verify(exactly = 0) { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
    }

    /**
     * Local coordinate mapping: block at world (x=17, z=18) is in chunk (1,1),
     * local coords (1, 2). Verify the snapshot is queried with the correct local coords.
     */
    @Test
    fun `scanArea maps world coordinates to correct local chunk coordinates (preservation)`() {
        val snapshot = mockk<ChunkSnapshot>(relaxed = true)
        every { snapshot.getBlockType(any(), any(), any()) } returns Material.AIR
        every { snapshot.getBlockType(1, 65, 2) } returns Material.EMERALD_BLOCK

        stubChunk(1, 1, snapshot)

        val result = scanner.scanArea(17..17, 65..65, 18..18)

        assertEquals(Material.EMERALD_BLOCK, result[Triple(17, 65, 18)])
        verify(exactly = 1) { snapshot.getBlockType(1, 65, 2) }
    }
}
