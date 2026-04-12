@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.core.serialization

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * Property-based tests for [ParamSerializer] unified round-trip across ALL supported types.
 *
 * Property 24: Serialization round-trip for all supported types
 *  20.4, 20.5
 *
 * Uses a sealed class [SupportedParam] to represent all 7 supported types and a unified
 * arbitrary that picks one at random, verifying the round-trip invariant across all types
 * in a single property check.
 */

/** Sealed hierarchy representing every supported parameter type. */
sealed class SupportedParam {
    data class StringParam(val value: String) : SupportedParam()
    data class IntParam(val value: Int) : SupportedParam()
    data class DoubleParam(val value: Double) : SupportedParam()
    data class BooleanParam(val value: Boolean) : SupportedParam()
    data class UuidParam(val value: UUID) : SupportedParam()
    data class StringListParam(val value: List<String>) : SupportedParam()
    data class LocationParam(val value: Location, val worldName: String) : SupportedParam()
}

class SerializationRoundTripPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Infrastructure helpers (same pattern as ParamSerializerPropertyTest)
    // -----------------------------------------------------------------------

    fun makePdcEnv(): Pair<ParamSerializer, Block> {
        val store = mutableMapOf<NamespacedKey, Any>()
        val keyCache = mutableMapOf<String, NamespacedKey>()

        fun keyFor(name: String): NamespacedKey =
            keyCache.getOrPut(name) { mockk<NamespacedKey>() }

        val pdc = mockk<PersistentDataContainer>()

        every { pdc.set(any(), any<PersistentDataType<Any, Any>>(), any()) } answers {
            store[firstArg<NamespacedKey>()] = thirdArg()
        }
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

    // -----------------------------------------------------------------------
    // Arbitraries for each supported type
    // -----------------------------------------------------------------------

    val arbFiniteDouble: Arb<Double> =
        Arb.double().filter { !it.isNaN() && !it.isInfinite() }

    /** Strings without "|" (the list delimiter). */
    val arbSafeString: Arb<String> =
        Arb.string(1..30).filter { '|' !in it }

    val arbSafeStringList: Arb<List<String>> =
        Arb.list(arbSafeString, 1..10)

    val arbLocation: Arb<SupportedParam.LocationParam> = arbitrary {
        val worldName = "testworld"
        val world = mockk<World>()
        every { world.name } returns worldName
        val x = Arb.double(-1000.0, 1000.0).filter { !it.isNaN() && !it.isInfinite() }.bind()
        val y = Arb.double(-64.0, 320.0).filter { !it.isNaN() && !it.isInfinite() }.bind()
        val z = Arb.double(-1000.0, 1000.0).filter { !it.isNaN() && !it.isInfinite() }.bind()
        val yaw = Arb.double(-180.0, 180.0).filter { !it.isNaN() && !it.isInfinite() }.bind().toFloat()
        val pitch = Arb.double(-90.0, 90.0).filter { !it.isNaN() && !it.isInfinite() }.bind().toFloat()
        SupportedParam.LocationParam(Location(world, x, y, z, yaw, pitch), worldName)
    }

    /** Unified arbitrary that generates any one of the 7 supported param types. */
    val arbSupportedParam: Arb<SupportedParam> = arbitrary { rs ->
        when (rs.random.nextInt(7)) {
            0 -> SupportedParam.StringParam(Arb.string(0..50).bind())
            1 -> SupportedParam.IntParam(Arb.int().bind())
            2 -> SupportedParam.DoubleParam(arbFiniteDouble.bind())
            3 -> SupportedParam.BooleanParam(Arb.boolean().bind())
            4 -> SupportedParam.UuidParam(Arb.uuid().bind())
            5 -> SupportedParam.StringListParam(arbSafeStringList.bind())
            else -> arbLocation.bind()
        }
    }

    // -----------------------------------------------------------------------
    // Property 24: Unified round-trip across all supported types
    // -----------------------------------------------------------------------

    "Property 24: Serialization round-trip for all supported types" - {
        "for any supported param type, save then load produces a value equal to the original" {
            //  20.4, 20.5
            checkAll(PropTestConfig(iterations = 20), arbSupportedParam) { param ->
                val (serializer, block) = makePdcEnv()

                when (param) {
                    is SupportedParam.StringParam -> {
                        serializer.save(block, "p", param.value)
                        val loaded = serializer.load(block, "p")
                        loaded shouldBe param.value
                    }

                    is SupportedParam.IntParam -> {
                        serializer.save(block, "p", param.value)
                        val loaded = serializer.load(block, "p")
                        loaded shouldBe param.value
                    }

                    is SupportedParam.DoubleParam -> {
                        serializer.save(block, "p", param.value)
                        val loaded = serializer.load(block, "p")
                        loaded shouldBe param.value
                    }

                    is SupportedParam.BooleanParam -> {
                        serializer.save(block, "p", param.value)
                        val loaded = serializer.load(block, "p")
                        loaded shouldBe param.value
                    }

                    is SupportedParam.UuidParam -> {
                        serializer.save(block, "p", param.value)
                        val loaded = serializer.load(block, "p") as? String
                        UUID.fromString(loaded) shouldBe param.value
                    }

                    is SupportedParam.StringListParam -> {
                        serializer.save(block, "p", param.value)
                        val loaded = serializer.load(block, "p") as? String ?: ""
                        val reparsed = if (loaded.isEmpty()) emptyList() else loaded.split("|")
                        reparsed shouldBe param.value
                    }

                    is SupportedParam.LocationParam -> {
                        val loc = param.value
                        serializer.save(block, "p", loc)
                        val loaded = serializer.load(block, "p") as? String
                        val parts = loaded!!.split(",")
                        parts[0] shouldBe param.worldName
                        parts[1].toDouble() shouldBe loc.x
                        parts[2].toDouble() shouldBe loc.y
                        parts[3].toDouble() shouldBe loc.z
                        parts[4].toFloat() shouldBe loc.yaw
                        parts[5].toFloat() shouldBe loc.pitch
                    }
                }
            }
        }
    }
})
