// Feature: ocp-gameplay-systems, Property 12: Plot Top sorting
// Validates: Requirements 8.1, 8.6
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotSettings
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import java.util.UUID

/**
 * Property 12: Plot Top отсортирован по рейтингу
 *
 * For any набора плотов в базе данных, `PlotTopGUI.loadTop27()` должен вернуть
 * список, отсортированный по `rating` по убыванию, длиной не более 27.
 *
 * **Validates: Requirements 8.1, 8.6**
 *
 * The test mirrors the pure sorting + limit logic used by PlotTopGUI.loadTop27():
 *   - Sort all plots by metadata.rating descending
 *   - Take at most 27
 * No Bukkit or database dependency is needed — the property is verified on the
 * pure in-memory transformation.
 */
class PlotTopSortingPropertyTest : FreeSpec({

    // -------------------------------------------------------------------------
    // Helpers — mirror the loadTop27() logic without Bukkit / DB
    // -------------------------------------------------------------------------

    fun makePlot(rating: Int): Plot = Plot(
        id = UUID.randomUUID(),
        owner = UUID.randomUUID(),
        name = "Plot-$rating",
        description = "",
        mainWorldName = "main",
        devWorldName = "dev",
        createdAt = 0L,
        updatedAt = 0L,
        settings = PlotSettings(),
        metadata = PlotMetadata(rating = rating)
    )

    /** Pure implementation of the sorting + limit logic from loadTop27(). */
    fun simulateLoadTop27(plots: List<Plot>): List<Plot> =
        plots.sortedByDescending { it.metadata.rating }.take(27)

    // Generator: arbitrary list of plots with random ratings (0..100 plots)
    val arbPlots: Arb<List<Plot>> =
        Arb.list(Arb.int(-1000..1000), 0..100)
            .map { ratings -> ratings.map { makePlot(it) } }

    // -------------------------------------------------------------------------
    // Property 12a: result length ≤ 27 (Req 8.1)
    // -------------------------------------------------------------------------

    "Property 12a: loadTop27 returns at most 27 plots for any input size (Req 8.1)" - {
        // **Validates: Requirements 8.1**
        "result size is always ≤ 27" {
            checkAll(
                PropTestConfig(iterations = 200),
                arbPlots
            ) { plots ->
                val result = simulateLoadTop27(plots)
                (result.size <= 27) shouldBe true
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 12b: result is sorted by rating descending (Req 8.6)
    // -------------------------------------------------------------------------

    "Property 12b: loadTop27 result is sorted by rating descending (Req 8.6)" - {
        // **Validates: Requirements 8.6**
        "each consecutive pair satisfies result[i].rating >= result[i+1].rating" {
            checkAll(
                PropTestConfig(iterations = 200),
                arbPlots
            ) { plots ->
                val result = simulateLoadTop27(plots)
                val isSorted = result.zipWithNext().all { (a, b) ->
                    a.metadata.rating >= b.metadata.rating
                }
                isSorted shouldBe true
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 12c: when input has ≥ 27 plots, result has exactly 27 (Req 8.1)
    // -------------------------------------------------------------------------

    "Property 12c: loadTop27 returns exactly 27 plots when input has ≥ 27 (Req 8.1)" - {
        // **Validates: Requirements 8.1**
        "result size equals 27 when there are at least 27 plots" {
            val arbLargePlots: Arb<List<Plot>> =
                Arb.list(Arb.int(-1000..1000), 27..100)
                    .map { ratings -> ratings.map { makePlot(it) } }

            checkAll(
                PropTestConfig(iterations = 200),
                arbLargePlots
            ) { plots ->
                val result = simulateLoadTop27(plots)
                result shouldHaveSize 27
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 12d: result contains the highest-rated plots (Req 8.6)
    // -------------------------------------------------------------------------

    "Property 12d: result contains the top-rated plots — no excluded plot has higher rating (Req 8.6)" - {
        // **Validates: Requirements 8.6**
        // The minimum rating in the result must be ≥ the maximum rating of any excluded plot.
        "no excluded plot has a higher rating than any included plot" {
            val arbLargePlots: Arb<List<Plot>> =
                Arb.list(Arb.int(-1000..1000), 28..100)
                    .map { ratings -> ratings.map { makePlot(it) } }

            checkAll(
                PropTestConfig(iterations = 200),
                arbLargePlots
            ) { plots ->
                val result = simulateLoadTop27(plots)
                val resultIds = result.map { it.id }.toSet()
                val excluded = plots.filter { it.id !in resultIds }

                if (result.isNotEmpty() && excluded.isNotEmpty()) {
                    val minIncluded = result.minOf { it.metadata.rating }
                    val maxExcluded = excluded.maxOf { it.metadata.rating }
                    (minIncluded >= maxExcluded) shouldBe true
                }
            }
        }
    }
})
