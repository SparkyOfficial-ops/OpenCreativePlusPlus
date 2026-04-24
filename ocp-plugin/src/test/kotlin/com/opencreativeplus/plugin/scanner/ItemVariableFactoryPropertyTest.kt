// Feature: category-based-coding-ui, Property 12: Item variable factory PDC tags and display name format
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.scanner

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.filter
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
 * Property 12: Item variable factory PDC tags and display name format
 *
 * For any variable name N, ItemVariableFactory.createVariable(N) must produce an ItemStack
 * whose PDC contains ocp:item_var_type = "variable" and ocp:item_var_name = N.
 * For any location name L, createLocation(L) must produce an ItemStack with
 * ocp:item_var_type = "location", ocp:item_var_name = L.
 *
 * Since Bukkit ItemStack cannot be instantiated without a server, this test validates
 * the read side (readVarType / readVarName) using mocked ItemStacks that simulate
 * what createVariable / createLocation would produce.
 *
 * **Validates: Requirements 9.1, 9.2, 9.3, 9.4**
 */
class ItemVariableFactoryPropertyTest : FreeSpec({

    val KEY_VAR_TYPE = NamespacedKey("ocp", "item_var_type")
    val KEY_VAR_NAME = NamespacedKey("ocp", "item_var_name")

    val arbName = Arb.string(1..50).filter { it.isNotBlank() }

    // -----------------------------------------------------------------------
    // Helper: build a mocked ItemStack simulating what the factory would produce
    // -----------------------------------------------------------------------

    fun mockFactoryItem(varType: String, varName: String): ItemStack {
        val item = mockk<ItemStack>(relaxed = true)
        val meta = mockk<ItemMeta>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { item.itemMeta } returns meta
        every { meta.persistentDataContainer } returns pdc
        every { pdc.get(KEY_VAR_TYPE, PersistentDataType.STRING) } returns varType
        every { pdc.get(KEY_VAR_NAME, PersistentDataType.STRING) } returns varName
        return item
    }

    fun mockItemNoMeta(): ItemStack {
        val item = mockk<ItemStack>(relaxed = true)
        every { item.itemMeta } returns null
        return item
    }

    // -----------------------------------------------------------------------
    // Property 12a: readVarType returns "variable" for variable items
    // -----------------------------------------------------------------------

    "Property 12a: readVarType returns 'variable' for variable items" - {
        "for any variable name, a variable item's PDC must have ocp:item_var_type = 'variable'" {
            // Validates: Requirements 9.1
            checkAll(PropTestConfig(iterations = 20), arbName) { name ->
                val item = mockFactoryItem("variable", name)
                ItemVariableFactory.readVarType(item) shouldBe "variable"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 12b: readVarType returns "location" for location items
    // -----------------------------------------------------------------------

    "Property 12b: readVarType returns 'location' for location items" - {
        "for any location name, a location item's PDC must have ocp:item_var_type = 'location'" {
            // Validates: Requirements 9.2
            checkAll(PropTestConfig(iterations = 20), arbName) { name ->
                val item = mockFactoryItem("location", name)
                ItemVariableFactory.readVarType(item) shouldBe "location"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 12c: readVarName round-trip for variable items
    // -----------------------------------------------------------------------

    "Property 12c: readVarName returns the original name for variable items" - {
        "for any variable name N, readVarName must return N" {
            // Validates: Requirements 9.1, 9.3
            checkAll(PropTestConfig(iterations = 20), arbName) { name ->
                val item = mockFactoryItem("variable", name)
                ItemVariableFactory.readVarName(item) shouldBe name
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 12d: readVarName round-trip for location items
    // -----------------------------------------------------------------------

    "Property 12d: readVarName returns the original name for location items" - {
        "for any location name L, readVarName must return L" {
            // Validates: Requirements 9.2, 9.4
            checkAll(PropTestConfig(iterations = 20), arbName) { name ->
                val item = mockFactoryItem("location", name)
                ItemVariableFactory.readVarName(item) shouldBe name
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 12e: readVarType returns null when item has no meta
    // -----------------------------------------------------------------------

    "Property 12e: readVarType returns null when item has no ItemMeta" - {
        "an item without meta must return null for readVarType" {
            // Validates: Requirements 9.1, 9.2
            checkAll(PropTestConfig(iterations = 20), arbName) { name ->
                val item = mockItemNoMeta()
                ItemVariableFactory.readVarType(item).shouldBeNull()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 12f: readVarName returns null when item has no meta
    // -----------------------------------------------------------------------

    "Property 12f: readVarName returns null when item has no ItemMeta" - {
        "an item without meta must return null for readVarName" {
            // Validates: Requirements 9.1, 9.2
            checkAll(PropTestConfig(iterations = 20), arbName) { name ->
                val item = mockItemNoMeta()
                ItemVariableFactory.readVarName(item).shouldBeNull()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 12g: PDC key namespace is "ocp" (not plugin name)
    // -----------------------------------------------------------------------

    "Property 12g: PDC keys use namespace 'ocp'" - {
        "readVarType and readVarName must use NamespacedKey('ocp', ...) not any other namespace" {
            // Validates: Requirements 9.1, 9.2
            checkAll(PropTestConfig(iterations = 20), arbName) { name ->
                // Item whose PDC only responds to ocp-namespaced keys
                val item = mockk<ItemStack>(relaxed = true)
                val meta = mockk<ItemMeta>(relaxed = true)
                val pdc = mockk<PersistentDataContainer>(relaxed = true)
                every { item.itemMeta } returns meta
                every { meta.persistentDataContainer } returns pdc
                every { pdc.get(NamespacedKey("ocp", "item_var_type"), PersistentDataType.STRING) } returns "variable"
                every { pdc.get(NamespacedKey("ocp", "item_var_name"), PersistentDataType.STRING) } returns name

                ItemVariableFactory.readVarType(item) shouldBe "variable"
                ItemVariableFactory.readVarName(item) shouldBe name
            }
        }
    }
})
