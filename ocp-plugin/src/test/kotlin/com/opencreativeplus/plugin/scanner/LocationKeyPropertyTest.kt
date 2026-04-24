// Feature: ocp-manifest-roadmap, Property 1: Location equality по значению
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.scanner

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World

/**
 * Property 1: Location equality по значению
 *
 * For any two Location objects sharing the same world name and coordinates (x, y, z),
 * LocationKey.of() must return equal keys, and the BlockScanner must treat them
 * as the same visited node.
 *
 * Feature: ocp-manifest-roadmap, Property 1: Location equality по значению
 * Validates: Requirements 1.2, 1.4
 */
class LocationKeyPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Property 1a: Same world name + coords → equal LocationKeys
    // -----------------------------------------------------------------------

    "Property 1a: LocationKey.of(worldName, x, y, z) equals LocationKey.of(loc) for matching coords" - {
        "for any world name and integer coordinates, both factory methods produce equal keys" {
            // Feature: ocp-manifest-roadmap, Property 1: Location equality по значению
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.string(minSize = 1, maxSize = 20),
                Arb.int(-512..512),
                Arb.int(0..255),
                Arb.int(-512..512)
            ) { worldName, x, y, z ->
                val world = mockk<World>(relaxed = true).also {
                    every { it.name } returns worldName
                    every { it.hashCode() } returns worldName.hashCode()
                }
                val loc = Location(world, x.toDouble(), y.toDouble(), z.toDouble())

                val keyFromCoords = LocationKey.of(worldName, x, y, z)
                val keyFromLocation = LocationKey.of(loc)

                keyFromCoords shouldBe keyFromLocation
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 1b: Two Location objects with same world name + coords → equal keys
    // -----------------------------------------------------------------------

    "Property 1b: two distinct Location objects with same world name and coords produce equal LocationKeys" - {
        "for any coordinates, two separate Location instances yield the same key" {
            // Feature: ocp-manifest-roadmap, Property 1: Location equality по значению
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.string(minSize = 1, maxSize = 20),
                Arb.int(-512..512),
                Arb.int(0..255),
                Arb.int(-512..512)
            ) { worldName, x, y, z ->
                val world1 = mockk<World>(relaxed = true).also {
                    every { it.name } returns worldName
                    every { it.hashCode() } returns 1
                }
                val world2 = mockk<World>(relaxed = true).also {
                    every { it.name } returns worldName
                    every { it.hashCode() } returns 2
                }

                val loc1 = Location(world1, x.toDouble(), y.toDouble(), z.toDouble())
                val loc2 = Location(world2, x.toDouble(), y.toDouble(), z.toDouble())

                // Even though world1 and world2 are different objects with different hashCodes,
                // LocationKey equality is based on world name + coordinates
                LocationKey.of(loc1) shouldBe LocationKey.of(loc2)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 1c: Different coordinates → different LocationKeys
    // -----------------------------------------------------------------------

    "Property 1c: LocationKeys with different coordinates are not equal" - {
        "for any two distinct coordinate sets, LocationKey.of() returns different keys" {
            // Feature: ocp-manifest-roadmap, Property 1: Location equality по значению
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.string(minSize = 1, maxSize = 20),
                Arb.int(-512..511),
                Arb.int(0..254),
                Arb.int(-512..511)
            ) { worldName, x, y, z ->
                val key1 = LocationKey.of(worldName, x, y, z)
                val key2 = LocationKey.of(worldName, x + 1, y, z)

                key1 shouldNotBe key2
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 1d: LocationKey can be used in a Set for visited tracking
    // -----------------------------------------------------------------------

    "Property 1d: LocationKey works correctly in a mutableSetOf for visited tracking" - {
        "adding a key and checking membership works by value, not identity" {
            // Feature: ocp-manifest-roadmap, Property 1: Location equality по значению
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.string(minSize = 1, maxSize = 20),
                Arb.int(-512..512),
                Arb.int(0..255),
                Arb.int(-512..512)
            ) { worldName, x, y, z ->
                val visited = mutableSetOf<LocationKey>()

                val world = mockk<World>(relaxed = true).also {
                    every { it.name } returns worldName
                    every { it.hashCode() } returns 99
                }
                val loc = Location(world, x.toDouble(), y.toDouble(), z.toDouble())

                visited.add(LocationKey.of(loc))

                // A second Location object with same coords should be found in the set
                val world2 = mockk<World>(relaxed = true).also {
                    every { it.name } returns worldName
                    every { it.hashCode() } returns 100 // different object identity
                }
                val loc2 = Location(world2, x.toDouble(), y.toDouble(), z.toDouble())

                (LocationKey.of(loc2) in visited) shouldBe true
            }
        }
    }
})
