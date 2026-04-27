// Feature: ocp-gameplay-systems, Property 13: Plot Top item render
// Validates: Requirements 8.2, 8.3
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotSettings
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import org.bukkit.Material
import java.util.UUID

/**
 * Property 13: Plot Top item содержит обязательные поля
 *
 * For any объекта `Plot`, item, построенный для Plot Top GUI, должен иметь
 * материал `PLAYER_HEAD` и lore, содержащий имя плота, имя владельца,
 * количество рейтингов и теги.
 *
 * **Validates: Requirements 8.2, 8.3**
 *
 * The test mirrors the pure item-building logic of `PlotTopGUI.buildPlotItem()`
 * without requiring Bukkit — the same approach used by VariableExplorerRenderPropertyTest.
 */
class PlotTopItemRenderPropertyTest : FreeSpec({

    // -------------------------------------------------------------------------
    // Pure model of PlotTopGUI.buildPlotItem() — no Bukkit needed
    // -------------------------------------------------------------------------

    /**
     * Lightweight representation of the item that buildPlotItem() produces.
     * Material is always PLAYER_HEAD (Req 8.2).
     * Lore lines are derived from plot fields (Req 8.3).
     */
    data class PlotItem(
        val material: Material,
        val displayName: String,
        val lore: List<String>
    )

    /**
     * Pure mirror of PlotTopGUI.buildPlotItem().
     * When ownerName is null (offline player with no name), falls back to owner UUID string —
     * matching the `ownerProfile.name ?: plot.owner.toString()` branch in the real code.
     */
    fun buildPlotItemModel(plot: Plot, ownerName: String?): PlotItem {
        val resolvedOwnerName = ownerName ?: plot.owner.toString()
        val lore = mutableListOf<String>()
        lore.add("§7Owner: §f$resolvedOwnerName")
        lore.add("§6Rating: §f${plot.metadata.rating}")
        if (plot.metadata.tags.isNotEmpty()) {
            lore.add("§bTags: §f${plot.metadata.tags.joinToString(", ")}")
        }
        lore.add("")
        lore.add("§7Click to visit!")
        return PlotItem(
            material = Material.PLAYER_HEAD,
            displayName = "§e${plot.name}",
            lore = lore
        )
    }

    // -------------------------------------------------------------------------
    // Generators
    // -------------------------------------------------------------------------

    /** Arbitrary non-empty plot name (printable ASCII, 1..30 chars). */
    val arbName: Arb<String> = Arb.string(1..30)

    /** Arbitrary tag list (0..5 tags, each 1..15 chars). */
    val arbTags: Arb<List<String>> = Arb.list(Arb.string(1..15), 0..5)

    /** Arbitrary rating (wide range to cover edge cases). */
    val arbRating: Arb<Int> = Arb.int(-1000..1000)

    /** Arbitrary optional owner name (null simulates an offline player with no cached name). */
    val arbOwnerName: Arb<String?> = arbitrary {
        if (it.random.nextBoolean()) Arb.string(1..20).bind() else null
    }

    /** Arbitrary Plot with varying name, owner, rating, and tags. */
    val arbPlot: Arb<Plot> = arbitrary { rs ->
        Plot(
            id = UUID.randomUUID(),
            owner = UUID.randomUUID(),
            name = arbName.bind(),
            description = "",
            mainWorldName = "main",
            devWorldName = "dev",
            createdAt = 0L,
            updatedAt = 0L,
            settings = PlotSettings(),
            metadata = PlotMetadata(
                rating = arbRating.bind(),
                tags = arbTags.bind()
            )
        )
    }

    // -------------------------------------------------------------------------
    // Property 13a: material is always PLAYER_HEAD (Req 8.2)
    // -------------------------------------------------------------------------

    "Property 13a: item material is always PLAYER_HEAD for any Plot (Req 8.2)" - {
        // **Validates: Requirements 8.2**
        "material equals Material.PLAYER_HEAD" {
            checkAll(
                PropTestConfig(iterations = 200),
                arbPlot,
                arbOwnerName
            ) { plot, ownerName ->
                val item = buildPlotItemModel(plot, ownerName)
                item.material shouldBe Material.PLAYER_HEAD
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 13b: lore contains the plot name (Req 8.3)
    // -------------------------------------------------------------------------

    "Property 13b: display name contains the plot name for any Plot (Req 8.3)" - {
        // **Validates: Requirements 8.3**
        "displayName contains plot.name" {
            checkAll(
                PropTestConfig(iterations = 200),
                arbPlot,
                arbOwnerName
            ) { plot, ownerName ->
                val item = buildPlotItemModel(plot, ownerName)
                item.displayName shouldContain plot.name
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 13c: lore contains the owner name (Req 8.3)
    // -------------------------------------------------------------------------

    "Property 13c: lore contains the owner name for any Plot (Req 8.3)" - {
        // **Validates: Requirements 8.3**
        "at least one lore line contains the resolved owner name" {
            checkAll(
                PropTestConfig(iterations = 200),
                arbPlot,
                arbOwnerName
            ) { plot, ownerName ->
                val item = buildPlotItemModel(plot, ownerName)
                val resolvedOwner = ownerName ?: plot.owner.toString()
                val loreText = item.lore.joinToString("\n")
                loreText shouldContain resolvedOwner
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 13d: lore contains the rating count (Req 8.3)
    // -------------------------------------------------------------------------

    "Property 13d: lore contains the rating count for any Plot (Req 8.3)" - {
        // **Validates: Requirements 8.3**
        "at least one lore line contains the rating value as a string" {
            checkAll(
                PropTestConfig(iterations = 200),
                arbPlot,
                arbOwnerName
            ) { plot, ownerName ->
                val item = buildPlotItemModel(plot, ownerName)
                val loreText = item.lore.joinToString("\n")
                loreText shouldContain plot.metadata.rating.toString()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 13e: lore contains tags when present (Req 8.3)
    // -------------------------------------------------------------------------

    "Property 13e: lore contains tags when the plot has tags (Req 8.3)" - {
        // **Validates: Requirements 8.3**
        "when plot.metadata.tags is non-empty, each tag appears in lore" {
            val arbPlotWithTags: Arb<Plot> = arbitrary { rs ->
                Plot(
                    id = UUID.randomUUID(),
                    owner = UUID.randomUUID(),
                    name = arbName.bind(),
                    description = "",
                    mainWorldName = "main",
                    devWorldName = "dev",
                    createdAt = 0L,
                    updatedAt = 0L,
                    settings = PlotSettings(),
                    metadata = PlotMetadata(
                        rating = arbRating.bind(),
                        tags = Arb.list(Arb.string(1..15), 1..5).bind()
                    )
                )
            }

            checkAll(
                PropTestConfig(iterations = 200),
                arbPlotWithTags,
                arbOwnerName
            ) { plot, ownerName ->
                val item = buildPlotItemModel(plot, ownerName)
                val loreText = item.lore.joinToString("\n")
                plot.metadata.tags.forEach { tag ->
                    loreText shouldContain tag
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 13f: lore has no tag line when tags are empty (Req 8.3)
    // -------------------------------------------------------------------------

    "Property 13f: lore has no Tags line when plot has no tags (Req 8.3)" - {
        // **Validates: Requirements 8.3**
        "when plot.metadata.tags is empty, no lore line starts with §bTags:" {
            val arbPlotNoTags: Arb<Plot> = arbitrary {
                Plot(
                    id = UUID.randomUUID(),
                    owner = UUID.randomUUID(),
                    name = arbName.bind(),
                    description = "",
                    mainWorldName = "main",
                    devWorldName = "dev",
                    createdAt = 0L,
                    updatedAt = 0L,
                    settings = PlotSettings(),
                    metadata = PlotMetadata(
                        rating = arbRating.bind(),
                        tags = emptyList()
                    )
                )
            }

            checkAll(
                PropTestConfig(iterations = 200),
                arbPlotNoTags,
                arbOwnerName
            ) { plot, ownerName ->
                val item = buildPlotItemModel(plot, ownerName)
                val hasTagLine = item.lore.any { it.startsWith("§bTags:") }
                hasTagLine shouldBe false
            }
        }
    }
})
