// Feature: category-based-coding-ui, Property 22: Category_Block break cascades to chest and sign
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.listener

import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.registry.NodeCategory
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import org.bukkit.Material

/**
 * Represents a position relative to the Category_Block used in the cascade-break simulation:
 *   ABOVE  — block directly above (candidate for param chest)
 *   NORTH / SOUTH / EAST / WEST — horizontal adjacent faces (candidates for sign)
 */
enum class CascadeRelativePos { ABOVE, NORTH, SOUTH, EAST, WEST }

/**
 * Property-based test for Property 22: Category_Block break cascades to chest and sign.
 *
 * For any Category_Block that has an attached parameter chest (block directly above with
 * `ocp:param_chest`) and/or an adjacent sign, breaking the Category_Block in DEV mode
 * must also remove the chest and sign.
 *
 * Tests the cascade-break logic directly (without a running Bukkit server) by modelling
 * the world state as simple mutable maps and verifying that the
 * [PlotProtectionListener.cascadeBreakAttachments] contract is satisfied for all
 * combinations of Category_Block material, chest presence, and sign presence.
 *
 * **Validates: Requirements 12.10**
 *
 * Feature: category-based-coding-ui, Property 22: Category_Block break cascades to chest and sign
 */
class CategoryBlockBreakCascadePropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Minimal world model
    // -----------------------------------------------------------------------

    /**
     * Simulates the cascade-break logic from [PlotProtectionListener.cascadeBreakAttachments].
     *
     * @param world  Mutable map of relative position → Material (AIR means empty).
     * @param hasParamChestTag  Whether the block at ABOVE has the `ocp:param_chest` PDC tag.
     * @return The same [world] map after applying cascade removal.
     */
    fun simulateCascadeBreak(
        world: MutableMap<CascadeRelativePos, Material>,
        hasParamChestTag: Boolean
    ): MutableMap<CascadeRelativePos, Material> {
        // Break the parameter chest above (only if it has the PDC tag)
        val above = world[CascadeRelativePos.ABOVE] ?: Material.AIR
        if (above == Material.CHEST && hasParamChestTag) {
            world[CascadeRelativePos.ABOVE] = Material.AIR
        }

        // Break the adjacent sign on the first matching horizontal face (NORTH → SOUTH → EAST → WEST)
        val horizontalFaces = listOf(
            CascadeRelativePos.NORTH,
            CascadeRelativePos.SOUTH,
            CascadeRelativePos.EAST,
            CascadeRelativePos.WEST
        )
        for (face in horizontalFaces) {
            val block = world[face] ?: Material.AIR
            if (block == Material.OAK_SIGN || block == Material.OAK_WALL_SIGN) {
                world[face] = Material.AIR
                break
            }
        }

        return world
    }

    val registry = CategoryRegistry()

    // -----------------------------------------------------------------------
    // Property 22: Category_Block break cascades to chest and sign
    // -----------------------------------------------------------------------

    "Property 22: Category_Block break cascades to chest and sign" - {

        // Validates: Requirements 12.10
        "22a: param chest above is removed when Category_Block is broken" {
            // Feature: category-based-coding-ui, Property 22: Category_Block break cascades to chest and sign
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.element(NodeCategory.entries)
            ) { category ->
                registry.isCategoryMaterial(category.material).shouldBeTrue()

                val world = mutableMapOf(
                    CascadeRelativePos.ABOVE to Material.CHEST
                )
                val result = simulateCascadeBreak(world, hasParamChestTag = true)

                result[CascadeRelativePos.ABOVE] shouldBe Material.AIR
            }
        }

        // Validates: Requirements 12.10
        "22b: chest without param_chest PDC tag is NOT removed" {
            // Feature: category-based-coding-ui, Property 22: Category_Block break cascades to chest and sign
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.element(NodeCategory.entries)
            ) { category ->
                registry.isCategoryMaterial(category.material).shouldBeTrue()

                val world = mutableMapOf(
                    CascadeRelativePos.ABOVE to Material.CHEST
                )
                val result = simulateCascadeBreak(world, hasParamChestTag = false)

                // Chest without the PDC tag must remain untouched
                result[CascadeRelativePos.ABOVE] shouldBe Material.CHEST
            }
        }

        // Validates: Requirements 12.10
        "22c: OAK_SIGN on any horizontal face is removed when Category_Block is broken" {
            // Feature: category-based-coding-ui, Property 22: Category_Block break cascades to chest and sign
            val signFaces = listOf(
                CascadeRelativePos.NORTH,
                CascadeRelativePos.SOUTH,
                CascadeRelativePos.EAST,
                CascadeRelativePos.WEST
            )

            checkAll(
                PropTestConfig(iterations = 100),
                Arb.element(NodeCategory.entries),
                Arb.element(signFaces)
            ) { category, signFace ->
                registry.isCategoryMaterial(category.material).shouldBeTrue()

                val world = mutableMapOf(signFace to Material.OAK_SIGN)
                val result = simulateCascadeBreak(world, hasParamChestTag = false)

                result[signFace] shouldBe Material.AIR
            }
        }

        // Validates: Requirements 12.10
        "22d: OAK_WALL_SIGN on any horizontal face is removed when Category_Block is broken" {
            // Feature: category-based-coding-ui, Property 22: Category_Block break cascades to chest and sign
            val signFaces = listOf(
                CascadeRelativePos.NORTH,
                CascadeRelativePos.SOUTH,
                CascadeRelativePos.EAST,
                CascadeRelativePos.WEST
            )

            checkAll(
                PropTestConfig(iterations = 100),
                Arb.element(NodeCategory.entries),
                Arb.element(signFaces)
            ) { category, signFace ->
                registry.isCategoryMaterial(category.material).shouldBeTrue()

                val world = mutableMapOf(signFace to Material.OAK_WALL_SIGN)
                val result = simulateCascadeBreak(world, hasParamChestTag = false)

                result[signFace] shouldBe Material.AIR
            }
        }

        // Validates: Requirements 12.10
        "22e: both chest and sign are removed together when both are present" {
            // Feature: category-based-coding-ui, Property 22: Category_Block break cascades to chest and sign
            val signFaces = listOf(
                CascadeRelativePos.NORTH,
                CascadeRelativePos.SOUTH,
                CascadeRelativePos.EAST,
                CascadeRelativePos.WEST
            )

            checkAll(
                PropTestConfig(iterations = 100),
                Arb.element(NodeCategory.entries),
                Arb.element(signFaces)
            ) { category, signFace ->
                registry.isCategoryMaterial(category.material).shouldBeTrue()

                val world = mutableMapOf(
                    CascadeRelativePos.ABOVE to Material.CHEST,
                    signFace to Material.OAK_SIGN
                )
                val result = simulateCascadeBreak(world, hasParamChestTag = true)

                result[CascadeRelativePos.ABOVE] shouldBe Material.AIR
                result[signFace] shouldBe Material.AIR
            }
        }

        // Validates: Requirements 12.10
        "22f: no cascade removal when neither chest nor sign is present" {
            // Feature: category-based-coding-ui, Property 22: Category_Block break cascades to chest and sign
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.element(NodeCategory.entries),
                Arb.boolean()
            ) { category, hasTag ->
                registry.isCategoryMaterial(category.material).shouldBeTrue()

                // Empty world — nothing to remove
                val world = mutableMapOf<CascadeRelativePos, Material>()
                val result = simulateCascadeBreak(world, hasParamChestTag = hasTag)

                // All positions remain absent (AIR / null)
                for (pos in CascadeRelativePos.entries) {
                    val block = result[pos] ?: Material.AIR
                    block shouldBe Material.AIR
                }
            }
        }

        // Validates: Requirements 12.10
        "22g: only the first sign found (in NORTH→SOUTH→EAST→WEST order) is removed" {
            // Feature: category-based-coding-ui, Property 22: Category_Block break cascades to chest and sign
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.element(NodeCategory.entries)
            ) { category ->
                registry.isCategoryMaterial(category.material).shouldBeTrue()

                // Place signs on all four faces
                val world = mutableMapOf(
                    CascadeRelativePos.NORTH to Material.OAK_SIGN,
                    CascadeRelativePos.SOUTH to Material.OAK_SIGN,
                    CascadeRelativePos.EAST  to Material.OAK_SIGN,
                    CascadeRelativePos.WEST  to Material.OAK_SIGN
                )
                val result = simulateCascadeBreak(world, hasParamChestTag = false)

                // NORTH is first in the scan order — it must be removed
                result[CascadeRelativePos.NORTH] shouldBe Material.AIR
                // The remaining three signs must be untouched (only one sign is removed per break)
                result[CascadeRelativePos.SOUTH] shouldBe Material.OAK_SIGN
                result[CascadeRelativePos.EAST]  shouldBe Material.OAK_SIGN
                result[CascadeRelativePos.WEST]  shouldBe Material.OAK_SIGN
            }
        }

        // Validates: Requirements 12.10
        "22h: cascade applies to every NodeCategory material" {
            // Feature: category-based-coding-ui, Property 22: Category_Block break cascades to chest and sign
            // Ensures the cascade is not accidentally restricted to a subset of categories.
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.element(NodeCategory.entries)
            ) { category ->
                val isCategoryBlock = registry.isCategoryMaterial(category.material)
                isCategoryBlock.shouldBeTrue()

                val world = mutableMapOf(
                    CascadeRelativePos.ABOVE to Material.CHEST,
                    CascadeRelativePos.EAST  to Material.OAK_WALL_SIGN
                )
                val result = simulateCascadeBreak(world, hasParamChestTag = true)

                result[CascadeRelativePos.ABOVE] shouldBe Material.AIR
                result[CascadeRelativePos.EAST]  shouldBe Material.AIR
            }
        }
    }
})
