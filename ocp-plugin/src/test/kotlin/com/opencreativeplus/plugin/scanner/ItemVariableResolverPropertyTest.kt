@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.model.ItemVariableRef
import com.opencreativeplus.api.model.ItemVariableType
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.logging.Logger

/**
 * Property 10: Item Variable type resolution
 * Validates: Requirements 4.4, 4.5
 *
 * At execution time, ItemVariableResolver must correctly resolve each ItemVariableType
 * to its corresponding Bukkit runtime object (or null with warning if not found/invalid).
 */
class ItemVariableResolverPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Arbitraries
    // -----------------------------------------------------------------------

    val arbVarName = Arb.string(1..30).filter { it.isNotBlank() }
    val arbDouble = Arb.double().filter { !it.isNaN() && !it.isInfinite() }
    val arbNonNumericString = Arb.string(1..20).filter { it.toDoubleOrNull() == null && it.isNotBlank() }
    val arbType = Arb.element(ItemVariableType.values().toList())

    // -----------------------------------------------------------------------
    // Helper: build a resolver with controlled scope values
    // -----------------------------------------------------------------------

    fun makeResolver(varName: String, localValue: Any?, plotValue: Any? = null): ItemVariableResolver {
        val localScope = mockk<VariableScope>()
        val plotScope = mockk<VariableScope>()
        val ctx = mockk<ExecutionContext>()
        val logger = mockk<Logger>(relaxed = true)

        every { ctx.localScope } returns localScope
        every { ctx.plotScope } returns plotScope
        every { localScope.get(varName) } returns localValue
        every { plotScope.get(varName) } returns plotValue

        return ItemVariableResolver(ctx, logger = logger)
    }

    // -----------------------------------------------------------------------
    // Property 10a: TEXT_REFERENCE always resolves to string representation
    // -----------------------------------------------------------------------

    "Property 10a: TEXT_REFERENCE always resolves to string representation" - {
        "for any variable name and stored value (String, Int, Double), resolve returns value.toString()" {
            // Validates: Requirements 4.4, 4.5
            checkAll(PropTestConfig(iterations = 100), arbVarName, Arb.int()) { name, intVal ->
                val resolver = makeResolver(name, intVal)
                val ref = ItemVariableRef(name, ItemVariableType.TEXT_REFERENCE)
                val result = resolver.resolve(ref)
                result shouldBe intVal.toString()
            }

            checkAll(PropTestConfig(iterations = 100), arbVarName, arbDouble) { name, dblVal ->
                val resolver = makeResolver(name, dblVal)
                val ref = ItemVariableRef(name, ItemVariableType.TEXT_REFERENCE)
                val result = resolver.resolve(ref)
                result shouldBe dblVal.toString()
            }

            checkAll(PropTestConfig(iterations = 100), arbVarName, arbVarName) { name, strVal ->
                val resolver = makeResolver(name, strVal)
                val ref = ItemVariableRef(name, ItemVariableType.TEXT_REFERENCE)
                val result = resolver.resolve(ref)
                result shouldBe strVal
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 10b: NUMBER_REFERENCE resolves numeric strings correctly
    // -----------------------------------------------------------------------

    "Property 10b: NUMBER_REFERENCE resolves numeric strings correctly" - {
        "for any Double value stored as a string, resolve returns a Number equal to that double" {
            // Validates: Requirements 4.4, 4.5
            checkAll(PropTestConfig(iterations = 100), arbVarName, arbDouble) { name, dblVal ->
                val resolver = makeResolver(name, dblVal.toString())
                val ref = ItemVariableRef(name, ItemVariableType.NUMBER_REFERENCE)
                val result = resolver.resolve(ref)
                result.shouldBeInstanceOf<Number>()
                result.toDouble() shouldBe dblVal
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 10c: NUMBER_REFERENCE returns null for non-numeric strings
    // -----------------------------------------------------------------------

    "Property 10c: NUMBER_REFERENCE returns null for non-numeric strings" - {
        "for any non-numeric string stored as the variable value, resolve returns null" {
            // Validates: Requirements 4.4, 4.5
            checkAll(PropTestConfig(iterations = 100), arbVarName, arbNonNumericString) { name, nonNumeric ->
                val resolver = makeResolver(name, nonNumeric)
                val ref = ItemVariableRef(name, ItemVariableType.NUMBER_REFERENCE)
                val result = resolver.resolve(ref)
                result.shouldBeNull()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 10d: LOCATION_REFERENCE resolves Location objects directly
    // -----------------------------------------------------------------------

    "Property 10d: LOCATION_REFERENCE resolves Location objects directly" - {
        "when a Location object is stored in scope, resolve returns that same Location instance" {
            // Validates: Requirements 4.5
            checkAll(PropTestConfig(iterations = 100), arbVarName) { name ->
                val location = mockk<Location>(relaxed = true)
                val resolver = makeResolver(name, location)
                val ref = ItemVariableRef(name, ItemVariableType.LOCATION_REFERENCE)
                val result = resolver.resolve(ref)
                result shouldBe location
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 10e: PLAYER_REFERENCE resolves Player objects directly
    // -----------------------------------------------------------------------

    "Property 10e: PLAYER_REFERENCE resolves Player objects directly" - {
        "when a Player object is stored in scope, resolve returns that same Player instance" {
            // Validates: Requirements 4.4
            checkAll(PropTestConfig(iterations = 100), arbVarName) { name ->
                val player = mockk<Player>(relaxed = true)
                val resolver = makeResolver(name, player)
                val ref = ItemVariableRef(name, ItemVariableType.PLAYER_REFERENCE)
                val result = resolver.resolve(ref)
                result shouldBe player
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 10f: missing variable returns null (null-safe fallback)
    // -----------------------------------------------------------------------

    "Property 10f: missing variable returns null (null-safe fallback)" - {
        "for any ItemVariableType, when the variable is not in any scope, resolve returns null" {
            // Validates: Requirements 4.4, 4.5
            checkAll(PropTestConfig(iterations = 100), arbVarName, arbType) { name, type ->
                val resolver = makeResolver(name, localValue = null, plotValue = null)
                val ref = ItemVariableRef(name, type)
                val result = resolver.resolve(ref)
                result.shouldBeNull()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 10g: localScope takes priority over plotScope
    // -----------------------------------------------------------------------

    "Property 10g: localScope takes priority over plotScope" - {
        "when a variable exists in both localScope and plotScope, the localScope value is returned" {
            // Validates: Requirements 4.4, 4.5
            checkAll(PropTestConfig(iterations = 100), arbVarName, arbVarName, arbVarName) { name, localVal, plotVal ->
                // Use TEXT_REFERENCE so any string value resolves cleanly without Bukkit calls
                val resolver = makeResolver(name, localValue = localVal, plotValue = plotVal)
                val ref = ItemVariableRef(name, ItemVariableType.TEXT_REFERENCE)
                val result = resolver.resolve(ref)
                result shouldBe localVal
            }
        }
    }
})
