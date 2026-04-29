@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-visual-programming-platform, Property 7: Round-trip DataContainer

package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.plugin.scanner.DataContainer.Companion.deserializeFrom
import com.opencreativeplus.plugin.scanner.DataContainer.Companion.serializeTo
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

/**
 * Property 7: Round-trip DataContainer
 *
 * For any valid DataContainer (Text, Number, Variable, Location), serializing to an ItemStack
 * via [DataContainer.serializeTo] and then deserializing via [DataContainer.deserializeFrom]
 * SHALL produce an equivalent DataContainer.
 *
 * Feature: ocp-visual-programming-platform, Property 7: Round-trip DataContainer
 * Validates: Requirements 3.8
 */
class DataContainerRoundTripPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Infrastructure: in-memory PDC mock
    // -----------------------------------------------------------------------

    /**
     * Creates a fresh ItemStack backed by an in-memory PDC store.
     * Supports STRING and DOUBLE types (the only types used by DataContainer serialization).
     */
    fun makeItemStack(): ItemStack {
        val store = mutableMapOf<NamespacedKey, Any>()

        val pdc = mockk<PersistentDataContainer>(relaxed = true)

        // set — store by key for the two types DataContainer uses: STRING and DOUBLE
        every { pdc.set(any<NamespacedKey>(), PersistentDataType.STRING, any<String>()) } answers {
            store[firstArg<NamespacedKey>()] = thirdArg<String>()
        }
        every { pdc.set(any<NamespacedKey>(), PersistentDataType.DOUBLE, any<Double>()) } answers {
            store[firstArg<NamespacedKey>()] = thirdArg<Double>()
        }

        // get STRING
        every { pdc.get(any(), PersistentDataType.STRING) } answers {
            store[firstArg<NamespacedKey>()] as? String
        }

        // get DOUBLE
        every { pdc.get(any(), PersistentDataType.DOUBLE) } answers {
            store[firstArg<NamespacedKey>()] as? Double
        }

        val meta = mockk<ItemMeta>(relaxed = true)
        every { meta.persistentDataContainer } returns pdc

        val item = mockk<ItemStack>(relaxed = true)
        every { item.itemMeta } returns meta
        // setItemMeta returns boolean in Bukkit's Java API; return true to avoid ClassCastException
        every { item.setItemMeta(any()) } returns true

        return item
    }

    // -----------------------------------------------------------------------
    // Arbitraries
    // -----------------------------------------------------------------------

    /** Finite doubles only — NaN/Infinity cannot round-trip through Double.toString(). */
    val arbFiniteDouble: Arb<Double> =
        Arb.double().filter { !it.isNaN() && !it.isInfinite() }

    /** Finite floats only. */
    val arbFiniteFloat: Arb<Float> =
        Arb.float().filter { !it.isNaN() && !it.isInfinite() }

    /** Arbitrary DataContainer.Text */
    val arbText: Arb<DataContainer.Text> = arbitrary {
        DataContainer.Text(Arb.string(0..80).bind())
    }

    /** Arbitrary DataContainer.Number — finite doubles only */
    val arbNumber: Arb<DataContainer.Number> = arbitrary {
        DataContainer.Number(arbFiniteDouble.bind())
    }

    /** Arbitrary DataContainer.Variable — non-empty variable names */
    val arbVariable: Arb<DataContainer.Variable> = arbitrary {
        DataContainer.Variable(Arb.string(1..40).bind())
    }

    /** Arbitrary DataContainer.Location — finite coordinates */
    val arbLocation: Arb<DataContainer.Location> = arbitrary {
        DataContainer.Location(
            x     = arbFiniteDouble.bind(),
            y     = arbFiniteDouble.bind(),
            z     = arbFiniteDouble.bind(),
            world = Arb.string(1..30).bind(),
            yaw   = arbFiniteFloat.bind(),
            pitch = arbFiniteFloat.bind()
        )
    }

    // -----------------------------------------------------------------------
    // Property 7a: Text round-trip
    // -----------------------------------------------------------------------

    "Property 7a: DataContainer.Text round-trip — serialize then deserialize returns equivalent Text" - {
        /**
         * Validates: Requirements 3.8
         *
         * For any Text value, serializeTo followed by deserializeFrom must return
         * a DataContainer.Text with the same string value.
         */
        "any Text value survives serialize then deserialize" {
            checkAll(PropTestConfig(iterations = 100), arbText) { original ->
                val item = makeItemStack()
                original.serializeTo(item)
                val restored = deserializeFrom(item)

                restored shouldNotBe null
                restored shouldBe original
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 7b: Number round-trip
    // -----------------------------------------------------------------------

    "Property 7b: DataContainer.Number round-trip — serialize then deserialize returns equivalent Number" - {
        /**
         * Validates: Requirements 3.8
         *
         * For any finite Double, serializeTo followed by deserializeFrom must return
         * a DataContainer.Number with the same numeric value.
         */
        "any finite Number value survives serialize then deserialize" {
            checkAll(PropTestConfig(iterations = 100), arbNumber) { original ->
                val item = makeItemStack()
                original.serializeTo(item)
                val restored = deserializeFrom(item)

                restored shouldNotBe null
                restored shouldBe original
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 7c: Variable round-trip
    // -----------------------------------------------------------------------

    "Property 7c: DataContainer.Variable round-trip — serialize then deserialize returns equivalent Variable" - {
        /**
         * Validates: Requirements 3.8
         *
         * For any variable name string, serializeTo followed by deserializeFrom must return
         * a DataContainer.Variable with the same name.
         */
        "any Variable name survives serialize then deserialize" {
            checkAll(PropTestConfig(iterations = 100), arbVariable) { original ->
                val item = makeItemStack()
                original.serializeTo(item)
                val restored = deserializeFrom(item)

                restored shouldNotBe null
                restored shouldBe original
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 7d: Location round-trip
    // -----------------------------------------------------------------------

    "Property 7d: DataContainer.Location round-trip — serialize then deserialize returns equivalent Location" - {
        /**
         * Validates: Requirements 3.8
         *
         * For any Location with finite coordinates, serializeTo followed by deserializeFrom
         * must return a DataContainer.Location with the same x, y, z, world, yaw, and pitch.
         */
        "any Location with finite coordinates survives serialize then deserialize" {
            checkAll(PropTestConfig(iterations = 100), arbLocation) { original ->
                val item = makeItemStack()
                original.serializeTo(item)
                val restored = deserializeFrom(item)

                restored shouldNotBe null
                restored shouldBe original
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 7e: deserializeFrom returns null for an item with no dc_type
    // -----------------------------------------------------------------------

    "Property 7e: deserializeFrom returns null when item has no dc_type key" - {
        /**
         * Validates: Requirements 3.8
         *
         * An item that has never had a DataContainer serialized into it must return null
         * from deserializeFrom, ensuring no false positives.
         */
        "empty item (no PDC data) deserializes to null" {
            checkAll(PropTestConfig(iterations = 20), Arb.string(0..1)) { _ ->
                val item = makeItemStack()
                // Do NOT call serializeTo — item has no dc_type key
                val result = deserializeFrom(item)
                result shouldBe null
            }
        }
    }
})
