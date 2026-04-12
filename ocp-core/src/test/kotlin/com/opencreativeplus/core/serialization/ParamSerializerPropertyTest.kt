@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.core.serialization

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.TileState
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * Property-based tests for [ParamSerializer] save/load round-trip.
 *
 * Property 3: Parameter save round-trip
 *  1.9, 20.1, 20.4, 20.5
 *
 * For every supported parameter type, serializing a value via [ParamSerializer.save] and then
 * reading it back via [ParamSerializer.load] must produce a value equal to the original
 * (or a deterministically re-parseable representation for complex types).
 */
class ParamSerializerPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Infrastructure helpers
    // -----------------------------------------------------------------------

    /**
     * Creates a fresh in-memory PDC environment for each test case.
     * Returns (serializer, block) ready to use.
     */
    fun makePdcEnv(): Pair<ParamSerializer, Block> {
        val store = mutableMapOf<NamespacedKey, Any>()
        val keyCache = mutableMapOf<String, NamespacedKey>()

        fun keyFor(name: String): NamespacedKey =
            keyCache.getOrPut(name) { mockk<NamespacedKey>() }

        val pdc = mockk<PersistentDataContainer>()

        // set — store by key
        every { pdc.set(any(), any<PersistentDataType<Any, Any>>(), any()) } answers {
            store[firstArg<NamespacedKey>()] = thirdArg()
        }

        // get — return typed value or null
        every { pdc.get(any(), PersistentDataType.STRING) } answers {
            store[firstArg<NamespacedKey>()] as? String
        }
        every { pdc.get(any(), PersistentDataType.INTEGER) } answers {
            store[firstArg<NamespacedKey>()] as? Int
        }
        every { pdc.get(any(), PersistentDataType.DOUBLE) } answers {
            store[firstArg<NamespacedKey>()] as? Double
        }
        every { pdc.get(any(), PersistentDataType.BYTE) } answers {
            store[firstArg<NamespacedKey>()] as? Byte
        }

        val tileState = mockk<TileState>()
        every { tileState.persistentDataContainer } returns pdc
        every { tileState.update() } returns true
        every { tileState.update(any(), any()) } returns true

        val block = mockk<Block>()
        every { block.state } returns tileState

        val serializer = ParamSerializer(::keyFor)
        return serializer to block
    }

    /** Non-TileState block — state returns a plain BlockState mock. */
    fun makeNonTileStateBlock(): Block {
        val state = mockk<BlockState>()
        val block = mockk<Block>()
        every { block.state } returns state
        return block
    }

    // -----------------------------------------------------------------------
    // Arbitraries
    // -----------------------------------------------------------------------

    val arbFiniteDouble: Arb<Double> =
        Arb.double().filter { !it.isNaN() && !it.isInfinite() }

    /** List<String> elements must not contain "|" (the delimiter). */
    val arbSafeString: Arb<String> =
        Arb.string(1..30).filter { '|' !in it }

    /** Non-empty list of non-empty strings without "|" — avoids empty-string ambiguity in serialization. */
    val arbSafeStringList: Arb<List<String>> =
        Arb.list(arbSafeString, 1..10)

    val arbLocation: Arb<Location> = arbitrary {
        val world = mockk<World>()
        every { world.name } returns "testworld"
        val x = Arb.double(-1000.0, 1000.0).filter { !it.isNaN() && !it.isInfinite() }.bind()
        val y = Arb.double(-64.0, 320.0).filter { !it.isNaN() && !it.isInfinite() }.bind()
        val z = Arb.double(-1000.0, 1000.0).filter { !it.isNaN() && !it.isInfinite() }.bind()
        val yaw = Arb.double(-180.0, 180.0).filter { !it.isNaN() && !it.isInfinite() }.bind().toFloat()
        val pitch = Arb.double(-90.0, 90.0).filter { !it.isNaN() && !it.isInfinite() }.bind().toFloat()
        Location(world, x, y, z, yaw, pitch)
    }

    // -----------------------------------------------------------------------
    // Property 3a: String round-trip
    // -----------------------------------------------------------------------

    "Property 3a: String round-trip — save then load returns the same String" - {
        "any String value survives save/load" {
            //  20.4, 20.5
            checkAll(PropTestConfig(iterations = 20), Arb.string(0..100)) { value ->
                val (serializer, block) = makePdcEnv()
                serializer.save(block, "param", value)
                val loaded = serializer.load(block, "param")
                loaded shouldBe value
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 3b: Int round-trip
    // -----------------------------------------------------------------------

    "Property 3b: Int round-trip — save then load returns the same Int" - {
        "any Int value survives save/load" {
            //  20.4, 20.5
            checkAll(PropTestConfig(iterations = 20), Arb.int()) { value ->
                val (serializer, block) = makePdcEnv()
                serializer.save(block, "param", value)
                val loaded = serializer.load(block, "param")
                loaded shouldBe value
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 3c: Double round-trip
    // -----------------------------------------------------------------------

    "Property 3c: Double round-trip — save then load returns the same Double" - {
        "any finite Double value survives save/load" {
            //  20.4, 20.5
            checkAll(PropTestConfig(iterations = 20), arbFiniteDouble) { value ->
                val (serializer, block) = makePdcEnv()
                serializer.save(block, "param", value)
                val loaded = serializer.load(block, "param")
                loaded shouldBe value
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 3d: Boolean round-trip
    // -----------------------------------------------------------------------

    "Property 3d: Boolean round-trip — save then load returns the same Boolean" - {
        "true and false both survive save/load" {
            //  20.4, 20.5
            checkAll(PropTestConfig(iterations = 20), Arb.boolean()) { value ->
                val (serializer, block) = makePdcEnv()
                serializer.save(block, "param", value)
                val loaded = serializer.load(block, "param")
                loaded shouldBe value
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 3e: UUID round-trip
    // -----------------------------------------------------------------------

    "Property 3e: UUID round-trip — load returns String, UUID.fromString equals original" - {
        "any UUID serializes to String and back" {
            //  20.4, 20.5
            checkAll(PropTestConfig(iterations = 20), Arb.uuid()) { uuid ->
                val (serializer, block) = makePdcEnv()
                serializer.save(block, "param", uuid)
                val loaded = serializer.load(block, "param") as? String
                UUID.fromString(loaded) shouldBe uuid
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 3f: List<String> round-trip
    // -----------------------------------------------------------------------

    "Property 3f: List<String> round-trip — load returns String, split by '|' equals original" - {
        "any List<String> (no '|' in elements) serializes and re-parses correctly" {
            //  20.4, 20.5
            checkAll(PropTestConfig(iterations = 20), arbSafeStringList) { list ->
                val (serializer, block) = makePdcEnv()
                serializer.save(block, "param", list)
                val loaded = serializer.load(block, "param") as? String ?: ""
                val reparsed = if (loaded.isEmpty()) emptyList() else loaded.split("|")
                reparsed shouldBe list.map { it.toString() }            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 3g: Location round-trip
    // -----------------------------------------------------------------------

    "Property 3g: Location round-trip — load returns String, parse back equals original coordinates" - {
        "any Location serializes to CSV and re-parses to same coordinates" {
            //  20.4, 20.5
            checkAll(PropTestConfig(iterations = 20), arbLocation) { loc ->
                val (serializer, block) = makePdcEnv()
                serializer.save(block, "param", loc)
                val loaded = serializer.load(block, "param") as? String
                val parts = loaded!!.split(",")
                parts[0] shouldBe loc.world!!.name
                parts[1].toDouble() shouldBe loc.x
                parts[2].toDouble() shouldBe loc.y
                parts[3].toDouble() shouldBe loc.z
                parts[4].toFloat() shouldBe loc.yaw
                parts[5].toFloat() shouldBe loc.pitch
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 3h: non-TileState block returns null on load
    // -----------------------------------------------------------------------

    "Property 3h: non-TileState block returns null on load" - {
        "load on a non-TileState block always returns null" {
            //  20.1
            checkAll(PropTestConfig(iterations = 20), Arb.string(1..20)) { paramName ->
                val keyCache = mutableMapOf<String, NamespacedKey>()
                fun keyFor(name: String) = keyCache.getOrPut(name) { mockk<NamespacedKey>() }
                val serializer = ParamSerializer(::keyFor)
                val block = makeNonTileStateBlock()
                val result = serializer.load(block, paramName)
                result.shouldBeNull()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 3i: multiple params don't interfere
    // -----------------------------------------------------------------------

    "Property 3i: multiple params don't interfere — saving 'a' and 'b' loads independently" - {
        "two different param names store and retrieve independently" {
            //  20.1, 20.4
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.string(1..20),
                Arb.string(1..20)
            ) { valueA, valueB ->
                val (serializer, block) = makePdcEnv()
                serializer.save(block, "paramA", valueA)
                serializer.save(block, "paramB", valueB)
                serializer.load(block, "paramA") shouldBe valueA
                serializer.load(block, "paramB") shouldBe valueB
            }
        }
    }
})
