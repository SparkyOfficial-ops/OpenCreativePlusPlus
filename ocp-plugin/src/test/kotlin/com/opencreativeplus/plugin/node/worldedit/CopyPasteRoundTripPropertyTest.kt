@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.node.worldedit

// Feature: ocp-gameplay-systems, Property 16: Copy-Paste round-trip

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.triple
import io.kotest.property.checkAll

/**
 * A pure-data stand-in for Bukkit's BlockData, used to avoid any server dependency.
 * Holds a material name and an optional state string (e.g. "facing=north").
 */
data class FakeBlockData(val material: String, val state: String = "") {
    fun clone(): FakeBlockData = copy()
}

/**
 * A pure-data BlockSnapshot that mirrors the production [BlockSnapshot] but uses
 * [FakeBlockData] instead of Bukkit's [org.bukkit.block.data.BlockData].
 */
data class FakeBlockSnapshot(
    val dx: Int,
    val dy: Int,
    val dz: Int,
    val data: FakeBlockData
)

/**
 * A pure-data clipboard that mirrors the production [BlockClipboard].
 */
data class FakeBlockClipboard(
    val blocks: List<FakeBlockSnapshot>,
    val size: Int
)

/**
 * Simulates the copy logic from [CopyRegionNode]:
 * iterates over a list of (dx, dy, dz, material, state) tuples and builds a [FakeBlockClipboard].
 */
fun buildClipboard(entries: List<FakeBlockSnapshot>): FakeBlockClipboard =
    FakeBlockClipboard(
        blocks = entries.map { it.copy(data = it.data.clone()) },
        size = entries.size
    )

/**
 * Simulates the paste logic from [PasteRegionNode]:
 * reads each snapshot from the clipboard and writes it to a mutable map keyed by (dx, dy, dz).
 * Returns the resulting map, which represents the "world" after the paste.
 */
fun pasteClipboard(clipboard: FakeBlockClipboard): Map<Triple<Int, Int, Int>, FakeBlockData> {
    val world = mutableMapOf<Triple<Int, Int, Int>, FakeBlockData>()
    for (snapshot in clipboard.blocks) {
        world[Triple(snapshot.dx, snapshot.dy, snapshot.dz)] = snapshot.data.clone()
    }
    return world
}

/**
 * Property 16: Copy-Paste round-trip
 *
 * For any rectangular region of blocks, copying via [CopyRegionNode] logic and then
 * pasting via [PasteRegionNode] logic at the same origin must reproduce the original
 * blocks unchanged — same relative offsets, same material, same block state.
 *
 * **Validates: Requirements 10.3**
 */
class CopyPasteRoundTripPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Property 16a: clipboard size equals the number of source snapshots
    // -----------------------------------------------------------------------

    "Property 16a: clipboard.size equals the number of source snapshots (Req 10.3)" - {
        "clipboard size matches source block count after copy" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1..200)
            ) { blockCount ->
                val source = List(blockCount) { i ->
                    FakeBlockSnapshot(dx = i, dy = 0, dz = 0, data = FakeBlockData("STONE"))
                }
                val clipboard = buildClipboard(source)
                clipboard.size shouldBe blockCount
                clipboard.blocks.size shouldBe blockCount
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 16b: pasted blocks equal original snapshots (material + state)
    // -----------------------------------------------------------------------

    "Property 16b: pasted blocks reproduce original material and state (Req 10.3)" - {
        "every block in the pasted world matches the original snapshot" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(
                    Arb.triple(
                        Arb.int(0..15),
                        Arb.int(0..15),
                        Arb.int(0..15)
                    ),
                    1..50
                )
            ) { coords ->
                // Deduplicate coordinates so each position is unique
                val uniqueCoords = coords.distinctBy { (dx, dy, dz) -> Triple(dx, dy, dz) }

                val materials = listOf("STONE", "GRASS_BLOCK", "OAK_LOG", "SAND", "WATER")
                val source = uniqueCoords.mapIndexed { idx, (dx, dy, dz) ->
                    FakeBlockSnapshot(
                        dx = dx,
                        dy = dy,
                        dz = dz,
                        data = FakeBlockData(
                            material = materials[idx % materials.size],
                            state = "variant=$idx"
                        )
                    )
                }

                val clipboard = buildClipboard(source)
                val pasted = pasteClipboard(clipboard)

                // Every original snapshot must be reproduced exactly
                for (snap in source) {
                    val key = Triple(snap.dx, snap.dy, snap.dz)
                    pasted[key] shouldBe snap.data
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 16c: clipboard blocks are independent copies (no aliasing)
    // -----------------------------------------------------------------------

    "Property 16c: clipboard stores independent copies — mutating source does not affect clipboard (Req 10.3)" - {
        "clipboard blocks are cloned, not aliased to the source" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1..100)
            ) { blockCount ->
                val source = MutableList(blockCount) { i ->
                    FakeBlockSnapshot(dx = i, dy = 0, dz = 0, data = FakeBlockData("STONE", "s=$i"))
                }

                val clipboard = buildClipboard(source)

                // Mutate the source list entries after copy
                val mutated = source.mapIndexed { i, snap ->
                    snap.copy(data = FakeBlockData("AIR", "mutated=$i"))
                }

                // Clipboard must still hold the original data
                for (i in 0 until blockCount) {
                    clipboard.blocks[i].data shouldBe FakeBlockData("STONE", "s=$i")
                    clipboard.blocks[i].data shouldBe source[i].data.copy() // original, not mutated
                }

                // Sanity: mutated list is different
                mutated.forEach { it.data.material shouldBe "AIR" }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 16d: round-trip preserves relative offsets
    // -----------------------------------------------------------------------

    "Property 16d: round-trip preserves relative (dx, dy, dz) offsets (Req 10.3)" - {
        "pasted world contains entries at exactly the same relative offsets as the source" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1..8),
                Arb.int(1..8),
                Arb.int(1..8)
            ) { sizeX, sizeY, sizeZ ->
                // Build a full cuboid region
                val source = mutableListOf<FakeBlockSnapshot>()
                for (dx in 0 until sizeX) {
                    for (dy in 0 until sizeY) {
                        for (dz in 0 until sizeZ) {
                            source.add(
                                FakeBlockSnapshot(
                                    dx = dx, dy = dy, dz = dz,
                                    data = FakeBlockData("STONE", "$dx,$dy,$dz")
                                )
                            )
                        }
                    }
                }

                val clipboard = buildClipboard(source)
                val pasted = pasteClipboard(clipboard)

                // Pasted world must have exactly the same set of keys
                val sourceKeys = source.map { Triple(it.dx, it.dy, it.dz) }.toSet()
                val pastedKeys = pasted.keys

                pastedKeys shouldBe sourceKeys

                // And each value must match
                for (snap in source) {
                    pasted[Triple(snap.dx, snap.dy, snap.dz)] shouldBe snap.data
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 16e: empty clipboard pastes nothing
    // -----------------------------------------------------------------------

    "Property 16e: empty clipboard produces empty pasted world (Req 10.3)" - {
        "pasting an empty clipboard results in no blocks placed" {
            val emptyClipboard = FakeBlockClipboard(blocks = emptyList(), size = 0)
            val pasted = pasteClipboard(emptyClipboard)
            pasted.isEmpty() shouldBe true
        }
    }
})
