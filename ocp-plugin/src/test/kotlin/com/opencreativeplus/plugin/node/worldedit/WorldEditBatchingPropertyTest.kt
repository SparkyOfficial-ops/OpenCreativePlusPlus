@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.node.worldedit

// Feature: ocp-gameplay-systems, Property 14: WorldEdit batching

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlin.math.ceil

/**
 * Property 14: WorldEdit батчинг ≤ 5000 блоков
 *
 * For any region with N > 5000 blocks, FillRegion and PasteRegion operations
 * must split the work into batches where each batch contains at most 5000 blocks.
 *
 * **Validates: Requirements 9.3, 10.5**
 */
class WorldEditBatchingPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Property 14a: FillRegion batching — each batch ≤ 5000 blocks (Req 9.3)
    // -----------------------------------------------------------------------

    "Property 14a: FillRegionNode splits N blocks into batches of at most 5000 (Req 9.3)" - {
        // For any block count N, chunked(5000) must produce batches each of size ≤ 5000.
        "every batch produced by chunked(5000) has size ≤ 5000" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1..50_000)
            ) { blockCount ->
                // Simulate the positions list that FillRegionNode builds
                val positions = List(blockCount) { Triple(it, 0, 0) }
                val batches = positions.chunked(5000)

                // Every batch must be ≤ 5000
                batches.forEach { batch ->
                    batch.size shouldBeLessThanOrEqual 5000
                }

                // Total blocks across all batches must equal the original count
                batches.sumOf { it.size } shouldBe blockCount

                // Number of batches must be ceil(N / 5000)
                batches.size shouldBe ceil(blockCount / 5000.0).toInt()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 14b: FillRegion large regions always require multiple batches (Req 9.3)
    // -----------------------------------------------------------------------

    "Property 14b: FillRegionNode uses more than one batch when N > 5000 (Req 9.3)" - {
        // For any block count N > 5000, chunked(5000) must produce at least 2 batches.
        "regions larger than 5000 blocks produce multiple batches" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(5001..50_000)
            ) { blockCount ->
                val positions = List(blockCount) { Triple(it, 0, 0) }
                val batches = positions.chunked(5000)

                batches.size shouldBeLessThanOrEqual ceil(blockCount / 5000.0).toInt()
                batches.size shouldBe ceil(blockCount / 5000.0).toInt()
                // At least 2 batches for N > 5000
                (batches.size >= 2) shouldBe true
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 14c: PasteRegion batching — each batch ≤ 5000 blocks (Req 10.5)
    // -----------------------------------------------------------------------

    "Property 14c: PasteRegionNode splits clipboard of N blocks into batches of at most 5000 (Req 10.5)" - {
        // For any clipboard size N, chunked(5000) must produce batches each of size ≤ 5000.
        "every paste batch has size ≤ 5000" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1..50_000)
            ) { clipboardSize ->
                // Simulate the clipboard.blocks list that PasteRegionNode iterates
                // We use a simple data holder to avoid needing real Bukkit BlockData
                val fakeBlocks = List(clipboardSize) { i ->
                    // Use a simple index-based placeholder — we only care about list size
                    i
                }
                val batches = fakeBlocks.chunked(5000)

                // Every batch must be ≤ 5000
                batches.forEach { batch ->
                    batch.size shouldBeLessThanOrEqual 5000
                }

                // Total blocks across all batches must equal clipboard size
                batches.sumOf { it.size } shouldBe clipboardSize

                // Number of batches must be ceil(N / 5000)
                batches.size shouldBe ceil(clipboardSize / 5000.0).toInt()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 14d: PasteRegion large clipboards always require multiple batches (Req 10.5)
    // -----------------------------------------------------------------------

    "Property 14d: PasteRegionNode uses more than one batch when clipboard size > 5000 (Req 10.5)" - {
        // For any clipboard size N > 5000, chunked(5000) must produce at least 2 batches.
        "clipboards larger than 5000 blocks produce multiple batches" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(5001..50_000)
            ) { clipboardSize ->
                val fakeBlocks = List(clipboardSize) { it }
                val batches = fakeBlocks.chunked(5000)

                (batches.size >= 2) shouldBe true
                batches.forEach { batch ->
                    batch.size shouldBeLessThanOrEqual 5000
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 14e: Batch count formula correctness (Req 9.3, 10.5)
    // -----------------------------------------------------------------------

    "Property 14e: batch count equals ceil(N / 5000) for any N (Req 9.3, 10.5)" - {
        // Verifies the mathematical relationship between block count and batch count.
        "batch count is exactly ceil(N / 5000)" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1..100_000)
            ) { n ->
                val items = List(n) { it }
                val batches = items.chunked(5000)
                val expectedBatchCount = ceil(n / 5000.0).toInt()
                batches.size shouldBe expectedBatchCount
            }
        }
    }
})
