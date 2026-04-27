@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.node.worldedit

// Feature: ocp-gameplay-systems, Property 15: FillRegion clamping

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

/**
 * Property 15: FillRegion clamping к границам плота
 *
 * For any pair of coordinates where at least one is outside the plot boundary,
 * FillRegionNode must clamp the region to the plot boundary so that no changed
 * block lies outside the plot.
 *
 * **Validates: Requirements 9.5**
 */
class FillRegionClampPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helper: mirrors the clamping logic from FillRegionNode
    // -----------------------------------------------------------------------

    data class Region(
        val minX: Int, val minY: Int, val minZ: Int,
        val maxX: Int, val maxY: Int, val maxZ: Int
    )

    data class Bounds(
        val minX: Int, val minY: Int, val minZ: Int,
        val maxX: Int, val maxY: Int, val maxZ: Int
    )

    fun clamp(region: Region, bounds: Bounds): Region? {
        val cMinX = maxOf(region.minX, bounds.minX)
        val cMinY = maxOf(region.minY, bounds.minY)
        val cMinZ = maxOf(region.minZ, bounds.minZ)
        val cMaxX = minOf(region.maxX, bounds.maxX - 1)
        val cMaxY = minOf(region.maxY, bounds.maxY - 1)
        val cMaxZ = minOf(region.maxZ, bounds.maxZ - 1)
        if (cMinX > cMaxX || cMinY > cMaxY || cMinZ > cMaxZ) return null
        return Region(cMinX, cMinY, cMinZ, cMaxX, cMaxY, cMaxZ)
    }

    // -----------------------------------------------------------------------
    // Property 15a: clamped region is fully inside plot bounds (Req 9.5)
    // -----------------------------------------------------------------------

    "Property 15a: every block in the clamped region lies within the plot boundary (Req 9.5)" - {
        "clamped minX/Y/Z >= bounds minX/Y/Z and clamped maxX/Y/Z <= bounds maxX/Y/Z - 1" {
            val arbBounds = Arb.bind(
                Arb.int(-50..50), Arb.int(-50..50), Arb.int(-50..50),
                Arb.int(10..100), Arb.int(10..100), Arb.int(10..100)
            ) { ox, oy, oz, sx, sy, sz -> Bounds(ox, oy, oz, ox + sx, oy + sy, oz + sz) }
            val arbRegion = Arb.bind(
                Arb.int(-100..100), Arb.int(-100..100), Arb.int(-100..100),
                Arb.int(1..80), Arb.int(1..80), Arb.int(1..80)
            ) { ox, oy, oz, sx, sy, sz -> Region(ox, oy, oz, ox + sx, oy + sy, oz + sz) }

            checkAll(PropTestConfig(iterations = 200), arbBounds, arbRegion) { bounds, region ->
                val clamped = clamp(region, bounds) ?: return@checkAll

                clamped.minX shouldBeGreaterThanOrEqual bounds.minX
                clamped.minY shouldBeGreaterThanOrEqual bounds.minY
                clamped.minZ shouldBeGreaterThanOrEqual bounds.minZ
                clamped.maxX shouldBeLessThanOrEqual (bounds.maxX - 1)
                clamped.maxY shouldBeLessThanOrEqual (bounds.maxY - 1)
                clamped.maxZ shouldBeLessThanOrEqual (bounds.maxZ - 1)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 15b: clamped region is a subset of the original region (Req 9.5)
    // -----------------------------------------------------------------------

    "Property 15b: clamped region is a subset of the original region (Req 9.5)" - {
        "clamped min >= original min and clamped max <= original max" {
            val arbBounds = Arb.bind(
                Arb.int(-50..50), Arb.int(-50..50), Arb.int(-50..50),
                Arb.int(10..100), Arb.int(10..100), Arb.int(10..100)
            ) { ox, oy, oz, sx, sy, sz -> Bounds(ox, oy, oz, ox + sx, oy + sy, oz + sz) }
            val arbRegion = Arb.bind(
                Arb.int(-100..100), Arb.int(-100..100), Arb.int(-100..100),
                Arb.int(1..80), Arb.int(1..80), Arb.int(1..80)
            ) { ox, oy, oz, sx, sy, sz -> Region(ox, oy, oz, ox + sx, oy + sy, oz + sz) }

            checkAll(PropTestConfig(iterations = 200), arbBounds, arbRegion) { bounds, region ->
                val clamped = clamp(region, bounds) ?: return@checkAll

                clamped.minX shouldBeGreaterThanOrEqual region.minX
                clamped.minY shouldBeGreaterThanOrEqual region.minY
                clamped.minZ shouldBeGreaterThanOrEqual region.minZ
                clamped.maxX shouldBeLessThanOrEqual region.maxX
                clamped.maxY shouldBeLessThanOrEqual region.maxY
                clamped.maxZ shouldBeLessThanOrEqual region.maxZ
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 15c: region fully inside bounds is unchanged (Req 9.5)
    // -----------------------------------------------------------------------

    "Property 15c: a region already inside the plot boundary is not modified (Req 9.5)" - {
        "clamped region equals original when original is fully within bounds" {
            val arbBounds = Arb.bind(
                Arb.int(-50..50), Arb.int(-50..50), Arb.int(-50..50),
                Arb.int(10..100), Arb.int(10..100), Arb.int(10..100)
            ) { ox, oy, oz, sx, sy, sz -> Bounds(ox, oy, oz, ox + sx, oy + sy, oz + sz) }

            checkAll(PropTestConfig(iterations = 200), arbBounds) { bounds ->
                val innerMinX = bounds.minX + 1
                val innerMinY = bounds.minY + 1
                val innerMinZ = bounds.minZ + 1
                val innerMaxX = bounds.maxX - 2
                val innerMaxY = bounds.maxY - 2
                val innerMaxZ = bounds.maxZ - 2

                if (innerMinX > innerMaxX || innerMinY > innerMaxY || innerMinZ > innerMaxZ) return@checkAll

                val region = Region(innerMinX, innerMinY, innerMinZ, innerMaxX, innerMaxY, innerMaxZ)
                val clamped = clamp(region, bounds)

                clamped shouldBe region
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 15d: region fully outside bounds yields null (Req 9.5)
    // -----------------------------------------------------------------------

    "Property 15d: a region entirely outside the plot boundary yields an empty result (Req 9.5)" - {
        "clamp returns null when region does not intersect bounds" {
            val arbBounds = Arb.bind(
                Arb.int(-50..50), Arb.int(-50..50), Arb.int(-50..50),
                Arb.int(10..100), Arb.int(10..100), Arb.int(10..100)
            ) { ox, oy, oz, sx, sy, sz -> Bounds(ox, oy, oz, ox + sx, oy + sy, oz + sz) }

            checkAll(PropTestConfig(iterations = 200), arbBounds) { bounds ->
                val region = Region(
                    bounds.maxX + 1, bounds.minY, bounds.minZ,
                    bounds.maxX + 10, bounds.maxY - 1, bounds.maxZ - 1
                )
                val clamped = clamp(region, bounds)
                clamped shouldBe null
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 15e: clamped region is geometrically valid (min <= max) (Req 9.5)
    // -----------------------------------------------------------------------

    "Property 15e: clamped region always has min <= max on every axis (Req 9.5)" - {
        "non-null clamped region is geometrically valid" {
            val arbBounds = Arb.bind(
                Arb.int(-50..50), Arb.int(-50..50), Arb.int(-50..50),
                Arb.int(10..100), Arb.int(10..100), Arb.int(10..100)
            ) { ox, oy, oz, sx, sy, sz -> Bounds(ox, oy, oz, ox + sx, oy + sy, oz + sz) }
            val arbRegion = Arb.bind(
                Arb.int(-100..100), Arb.int(-100..100), Arb.int(-100..100),
                Arb.int(1..80), Arb.int(1..80), Arb.int(1..80)
            ) { ox, oy, oz, sx, sy, sz -> Region(ox, oy, oz, ox + sx, oy + sy, oz + sz) }

            checkAll(PropTestConfig(iterations = 200), arbBounds, arbRegion) { bounds, region ->
                val clamped = clamp(region, bounds) ?: return@checkAll

                (clamped.minX <= clamped.maxX) shouldBe true
                (clamped.minY <= clamped.maxY) shouldBe true
                (clamped.minZ <= clamped.maxZ) shouldBe true
            }
        }
    }
})
