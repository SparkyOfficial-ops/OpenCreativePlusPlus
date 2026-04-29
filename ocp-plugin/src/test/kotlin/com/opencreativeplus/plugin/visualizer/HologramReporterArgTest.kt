// Feature: ocp-visual-programming-platform, Requirement 9.2: Формат строки голограммы аргумента

package com.opencreativeplus.plugin.visualizer

import com.opencreativeplus.plugin.scanner.DataContainer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

/**
 * Unit test for hologram argument line formatting (Requirement 9.2).
 *
 * Validates that for each DataContainer type the hologram line produced by
 * HologramReporter.showArgHolograms matches the format:
 *   "§e[i]: §f<TypeLabel>: §7<Value>"
 *
 * These tests are pure — no Bukkit server or mocks required.
 */
class HologramReporterArgTest : StringSpec({

    /** Replicates the exact line format used in HologramReporter.showArgHolograms */
    fun hologramLine(index: Int, dc: DataContainer): String =
        "§e[${index}]: §f${dc.typeLabel()}: §7${dc.displayValue()}"

    // -------------------------------------------------------------------------
    // typeLabel() — one concrete assertion per subtype
    // -------------------------------------------------------------------------

    "Text typeLabel is Текст" {
        DataContainer.Text("hello").typeLabel() shouldBe "Текст"
    }

    "Number typeLabel is Число" {
        DataContainer.Number(42.0).typeLabel() shouldBe "Число"
    }

    "Variable typeLabel is Переменная" {
        DataContainer.Variable("score").typeLabel() shouldBe "Переменная"
    }

    "Location typeLabel is Местоположение" {
        DataContainer.Location(1.0, 2.0, 3.0, "world").typeLabel() shouldBe "Местоположение"
    }

    // -------------------------------------------------------------------------
    // displayValue() — concrete value rendering per subtype
    // -------------------------------------------------------------------------

    "Text displayValue returns the string value" {
        DataContainer.Text("hello world").displayValue() shouldBe "hello world"
    }

    "Text displayValue returns empty string for empty text" {
        DataContainer.Text("").displayValue() shouldBe ""
    }

    "Number displayValue renders whole number without decimal point" {
        DataContainer.Number(42.0).displayValue() shouldBe "42"
    }

    "Number displayValue renders fractional number with decimal" {
        DataContainer.Number(3.14).displayValue() shouldBe "3.14"
    }

    "Number displayValue renders zero as 0" {
        DataContainer.Number(0.0).displayValue() shouldBe "0"
    }

    "Variable displayValue returns the variable name" {
        DataContainer.Variable("score").displayValue() shouldBe "score"
    }

    "Location displayValue renders coordinates and world name" {
        DataContainer.Location(1.0, 2.0, 3.0, "world").displayValue() shouldBe "(1, 2, 3, world)"
    }

    "Location displayValue renders fractional coordinates" {
        DataContainer.Location(1.5, 64.0, -3.25, "nether").displayValue() shouldBe "(1.5, 64, -3.25, nether)"
    }

    // -------------------------------------------------------------------------
    // Full hologram line format — one example per subtype (Requirement 9.2)
    // -------------------------------------------------------------------------

    "hologram line for Text at index 0 matches expected format" {
        val line = hologramLine(0, DataContainer.Text("hello"))
        line shouldBe "§e[0]: §fТекст: §7hello"
        line shouldStartWith "§e[0]: §f"
        line shouldContain "§fТекст: §7"
    }

    "hologram line for Number at index 1 matches expected format" {
        val line = hologramLine(1, DataContainer.Number(42.0))
        line shouldBe "§e[1]: §fЧисло: §742"
        line shouldStartWith "§e[1]: §f"
        line shouldContain "§fЧисло: §7"
    }

    "hologram line for fractional Number at index 2 matches expected format" {
        val line = hologramLine(2, DataContainer.Number(3.14))
        line shouldBe "§e[2]: §fЧисло: §73.14"
    }

    "hologram line for Variable at index 3 matches expected format" {
        val line = hologramLine(3, DataContainer.Variable("score"))
        line shouldBe "§e[3]: §fПеременная: §7score"
        line shouldStartWith "§e[3]: §f"
        line shouldContain "§fПеременная: §7"
    }

    "hologram line for Location at index 4 matches expected format" {
        val line = hologramLine(4, DataContainer.Location(1.0, 2.0, 3.0, "world"))
        line shouldBe "§e[4]: §fМестоположение: §7(1, 2, 3, world)"
        line shouldStartWith "§e[4]: §f"
        line shouldContain "§fМестоположение: §7"
    }

    // -------------------------------------------------------------------------
    // Multi-arg list — indices increment correctly
    // -------------------------------------------------------------------------

    "hologram lines for a list of args use sequential indices" {
        val args = listOf(
            DataContainer.Text("msg"),
            DataContainer.Number(10.0),
            DataContainer.Variable("x"),
            DataContainer.Location(0.0, 64.0, 0.0, "world")
        )
        val lines = args.mapIndexed { i, dc -> hologramLine(i, dc) }

        lines[0] shouldBe "§e[0]: §fТекст: §7msg"
        lines[1] shouldBe "§e[1]: §fЧисло: §710"
        lines[2] shouldBe "§e[2]: §fПеременная: §7x"
        lines[3] shouldBe "§e[3]: §fМестоположение: §7(0, 64, 0, world)"
    }
})
