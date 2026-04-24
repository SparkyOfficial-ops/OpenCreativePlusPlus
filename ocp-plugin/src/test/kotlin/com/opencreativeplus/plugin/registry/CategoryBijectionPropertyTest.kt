// Feature: ocp-manifest-roadmap, Property 4: Биекция Material ↔ NodeCategory
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.registry

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.of
import io.kotest.property.forAll

/**
 * Property-based test for Property 4: Биекция Material ↔ NodeCategory.
 *
 * Validates that the mapping from NodeCategory to Material is a bijection —
 * each Material maps to exactly one category and each category maps to exactly one Material.
 *
 * Feature: ocp-manifest-roadmap, Property 4: Биекция Material ↔ NodeCategory
 * Validates: Requirements 3.1–3.12
 */
class CategoryBijectionPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Property 4: Биекция Material ↔ NodeCategory
    // -----------------------------------------------------------------------

    "Property 4: Биекция Material ↔ NodeCategory" - {

        "all 11 NodeCategory entries are present" {
            // Feature: ocp-manifest-roadmap, Property 4: Биекция Material ↔ NodeCategory
            NodeCategory.entries.size shouldBe 11
        }

        "all NodeCategory entries have distinct materials (injective)" {
            // Feature: ocp-manifest-roadmap, Property 4: Биекция Material ↔ NodeCategory
            val materials = NodeCategory.entries.map { it.material }
            val distinctMaterials = materials.toSet()
            distinctMaterials.size shouldBe NodeCategory.entries.size
        }

        "for any two distinct categories their materials differ (property-based)" {
            // Feature: ocp-manifest-roadmap, Property 4: Биекция Material ↔ NodeCategory
            val entries = NodeCategory.entries
            forAll(Arb.of(entries), Arb.of(entries)) { a, b ->
                if (a != b) a.material != b.material else true
            }
        }

        "getCategoryForMaterial returns the correct category for each entry" {
            // Feature: ocp-manifest-roadmap, Property 4: Биекция Material ↔ NodeCategory
            val registry = CategoryRegistry()
            forAll(Arb.of(NodeCategory.entries)) { category ->
                registry.getCategoryForMaterial(category.material) == category
            }
        }

        "allCategoryMaterials size equals number of NodeCategory entries" {
            // Feature: ocp-manifest-roadmap, Property 4: Биекция Material ↔ NodeCategory
            val registry = CategoryRegistry()
            registry.allCategoryMaterials().size shouldBe NodeCategory.entries.size
        }

        "each category material is recognised by isCategoryMaterial" {
            // Feature: ocp-manifest-roadmap, Property 4: Биекция Material ↔ NodeCategory
            val registry = CategoryRegistry()
            forAll(Arb.of(NodeCategory.entries)) { category ->
                registry.isCategoryMaterial(category.material)
            }
        }

        "no category material conflicts with built-in value node materials" {
            // Feature: ocp-manifest-roadmap, Property 4: Биекция Material ↔ NodeCategory
            // Value nodes use: GOLD_BLOCK, COPPER_BLOCK, AMETHYST_BLOCK, QUARTZ_BLOCK,
            //                  REDSTONE_BLOCK, COAL_BLOCK, NETHERITE_BLOCK
            val valueNodeMaterials = setOf(
                org.bukkit.Material.GOLD_BLOCK,
                org.bukkit.Material.COPPER_BLOCK,
                org.bukkit.Material.AMETHYST_BLOCK,
                org.bukkit.Material.QUARTZ_BLOCK,
                org.bukkit.Material.REDSTONE_BLOCK,
                org.bukkit.Material.COAL_BLOCK,
                org.bukkit.Material.NETHERITE_BLOCK
            )
            val categoryMaterials = NodeCategory.entries.map { it.material }
            for (mat in categoryMaterials) {
                valueNodeMaterials shouldNotContain mat
            }
        }

        "Mineland-style mapping: spot-check all 11 required entries" {
            // Feature: ocp-manifest-roadmap, Property 4: Биекция Material ↔ NodeCategory
            NodeCategory.PLAYER_EVENT.material  shouldBe org.bukkit.Material.DIAMOND_BLOCK
            NodeCategory.IF_PLAYER.material     shouldBe org.bukkit.Material.OAK_PLANKS
            NodeCategory.PLAYER_ACTION.material shouldBe org.bukkit.Material.COBBLESTONE
            NodeCategory.GAME_ACTION.material   shouldBe org.bukkit.Material.NETHER_BRICKS
            NodeCategory.IF_VARIABLE.material   shouldBe org.bukkit.Material.OBSIDIAN
            NodeCategory.SET_VARIABLE.material  shouldBe org.bukkit.Material.IRON_BLOCK
            NodeCategory.SELECT_OBJECT.material shouldBe org.bukkit.Material.PURPUR_BLOCK
            NodeCategory.IF_ENTITY.material     shouldBe org.bukkit.Material.BRICK
            NodeCategory.ARRAY_OP.material      shouldBe org.bukkit.Material.BOOKSHELF
            NodeCategory.LOOP.material          shouldBe org.bukkit.Material.EMERALD_BLOCK
            NodeCategory.FUNCTION.material      shouldBe org.bukkit.Material.LAPIS_BLOCK
        }
    }
})
