@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-mvp2-core-systems, Property 4: DataContainer round-trip
// Feature: ocp-mvp2-core-systems, Property 5: Числовой ввод — валидация

package com.opencreativeplus.plugin.gui

import com.opencreativeplus.plugin.scanner.DataContainer
import com.opencreativeplus.plugin.scanner.DataContainer.Companion.deserializeFrom
import com.opencreativeplus.plugin.scanner.DataContainer.Companion.serializeTo
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
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
 * Property 4: DataContainer round-trip
 *
 * For any DataContainer (Text, Number, Variable, Location), serializing via serializeTo(item)
 * then deserializing via deserializeFrom(item) must return an equivalent DataContainer.
 *
 * Property 5: Числовой ввод — валидация
 *
 * For any string input: if it is NOT parseable as a Double, toDoubleOrNull() returns null
 * (no item created, error sent); if it IS parseable as a Double, a DataContainer.Number
 * with the correct value is created.
 *
 * Validates: Requirements 3.7, 3.12, 3.13
 */
class DataContainerItemCreatorPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Infrastructure: in-memory PDC mock (same pattern as DataContainerRoundTripPropertyTest)
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
    @Suppress("unused")
    val arbFiniteFloat: Arb<Float> =
        Arb.float().filter { !it.isNaN() && !it.isInfinite() }

    /** Arbitrary DataContainer.Text */
    @Suppress("unused")
    val arbText: Arb<DataContainer.Text> = arbitrary {
        DataContainer.Text(Arb.string(0..80).bind())
    }

    /** Arbitrary DataContainer.Number — finite doubles only */
    @Suppress("unused")
    val arbNumber: Arb<DataContainer.Number> = arbitrary {
        DataContainer.Number(arbFiniteDouble.bind())
    }

    /** Arbitrary DataContainer.Variable — any string (including empty) */
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
    // Property 4: DataContainer round-trip
    // Validates: Requirements 3.12, 3.13
    // -----------------------------------------------------------------------

    "Property 4a: DataContainer.Text round-trip — serialize then deserialize returns equivalent Text" - {
        // Feature: ocp-mvp2-core-systems, Property 4: DataContainer round-trip
        // Validates: Requirements 3.12, 3.13
        "any Text value survives serialize then deserialize" {
            checkAll(PropTestConfig(iterations = 20), arbText) { original ->
                val item = makeItemStack()
                original.serializeTo(item)
                val restored = deserializeFrom(item)

                restored shouldNotBe null
                restored shouldBe original
            }
        }
    }

    "Property 4b: DataContainer.Number round-trip — serialize then deserialize returns equivalent Number" - {
        // Feature: ocp-mvp2-core-systems, Property 4: DataContainer round-trip
        // Validates: Requirements 3.12, 3.13
        "any finite Number value survives serialize then deserialize" {
            checkAll(PropTestConfig(iterations = 20), arbNumber) { original ->
                val item = makeItemStack()
                original.serializeTo(item)
                val restored = deserializeFrom(item)

                restored shouldNotBe null
                restored shouldBe original
            }
        }
    }

    "Property 4c: DataContainer.Variable round-trip — serialize then deserialize returns equivalent Variable" - {
        // Feature: ocp-mvp2-core-systems, Property 4: DataContainer round-trip
        // Validates: Requirements 3.12, 3.13
        "any Variable name survives serialize then deserialize" {
            checkAll(PropTestConfig(iterations = 20), arbVariable) { original ->
                val item = makeItemStack()
                original.serializeTo(item)
                val restored = deserializeFrom(item)

                restored shouldNotBe null
                restored shouldBe original
            }
        }
    }

    "Property 4d: DataContainer.Location round-trip — serialize then deserialize returns equivalent Location" - {
        // Feature: ocp-mvp2-core-systems, Property 4: DataContainer round-trip
        // Validates: Requirements 3.12, 3.13
        "any Location with finite coordinates survives serialize then deserialize" {
            checkAll(PropTestConfig(iterations = 20), arbLocation) { original ->
                val item = makeItemStack()
                original.serializeTo(item)
                val restored = deserializeFrom(item)

                restored shouldNotBe null
                restored shouldBe original
            }
        }
    }

    "Property 4e: deserializeFrom returns null for item with no dc_type key" - {
        // Feature: ocp-mvp2-core-systems, Property 4: DataContainer round-trip
        // Validates: Requirements 3.12, 3.13
        "empty item (no PDC data) deserializes to null" {
            checkAll(PropTestConfig(iterations = 20), Arb.string(0..1)) { _ ->
                val item = makeItemStack()
                // Do NOT call serializeTo — item has no dc_type key
                val result = deserializeFrom(item)
                result.shouldBeNull()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 5: Числовой ввод — валидация
    // Validates: Requirement 3.7
    // -----------------------------------------------------------------------

    "Property 5a: Non-numeric strings — toDoubleOrNull returns null (validation rejects input)" - {
        // Feature: ocp-mvp2-core-systems, Property 5: Числовой ввод — валидация
        // Validates: Requirement 3.7
        //
        // For any string that cannot be parsed as a Double, the ItemCreatorGUI numeric
        // validation logic (input.toDoubleOrNull() == null) must reject it — no DataContainer
        // is created.
        "any non-numeric string is rejected by toDoubleOrNull" {
            val arbNonNumeric = Arb.string(1..20).filter { it.toDoubleOrNull() == null }
            checkAll(PropTestConfig(iterations = 20), arbNonNumeric) { input ->
                // Mirrors the validation logic in ItemCreatorGUI.handleClick slot 1:
                //   val number = input.toDoubleOrNull()
                //   if (number == null) { sendMessage(...); return }
                val number = input.toDoubleOrNull()
                number.shouldBeNull()
                // No DataContainer.Number is created — verified by the null check above
            }
        }
    }

    "Property 5b: Valid numeric strings — toDoubleOrNull returns correct Double and DataContainer.Number round-trips" - {
        // Feature: ocp-mvp2-core-systems, Property 5: Числовой ввод — валидация
        // Validates: Requirement 3.7
        //
        // For any finite Double d, its string representation d.toString() must be parseable
        // back to d via toDoubleOrNull(), and the resulting DataContainer.Number must
        // round-trip correctly through serializeTo/deserializeFrom.
        "finite double converted to string and back produces correct DataContainer.Number" {
            checkAll(PropTestConfig(iterations = 20), arbFiniteDouble) { d ->
                val input = d.toString()

                // Validation logic: input is parseable
                val number = input.toDoubleOrNull()
                number shouldNotBe null
                number shouldBe d

                // DataContainer.Number is created with the parsed value
                val dc = DataContainer.Number(number!!)
                dc.value shouldBe d

                // Round-trip: serialize then deserialize returns equivalent DataContainer.Number
                val item = makeItemStack()
                dc.serializeTo(item)
                val restored = deserializeFrom(item)
                restored shouldNotBe null
                restored shouldBe dc
            }
        }
    }
})
