@file:OptIn(io.kotest.common.ExperimentalKotest::class)

// Feature: ocp-visual-programming-platform, Property 15: Формат строки голограммы аргумента

package com.opencreativeplus.plugin.visualizer

import com.opencreativeplus.plugin.scanner.DataContainer
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property 15: Формат строки голограммы аргумента
 *
 * Validates: Requirements 9.2
 *
 * THE HologramReporter SHALL format each hologram line as `[ArgIndex]: [TypeLabel]: [Value]`,
 * where TypeLabel reflects the DataContainer type (Текст, Число, Переменная, Местоположение).
 *
 * Actual format used in HologramReporter.showArgHolograms:
 *   "§e[${lineIndex}]: §f${dc.typeLabel()}: §7${dc.displayValue()}"
 *
 * This test validates the pure formatting logic on DataContainer without any Bukkit server.
 */
class HologramFormatPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Arbitraries
    // -----------------------------------------------------------------------

    /** Finite doubles only — NaN/Infinity are not valid display values. */
    val arbFiniteDouble: Arb<Double> =
        Arb.double().filter { !it.isNaN() && !it.isInfinite() }

    /** Finite floats only. */
    val arbFiniteFloat: Arb<Float> =
        Arb.float().filter { !it.isNaN() && !it.isInfinite() }

    /** Non-negative line indices (hologram line numbers). */
    val arbIndex: Arb<Int> =
        Arb.int(0..999)

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
    // Helper: build the hologram line exactly as HologramReporter does
    // -----------------------------------------------------------------------

    fun hologramLine(index: Int, dc: DataContainer): String =
        "§e[${index}]: §f${dc.typeLabel()}: §7${dc.displayValue()}"

    // -----------------------------------------------------------------------
    // Property 15a: typeLabel() returns the correct label for each type
    // -----------------------------------------------------------------------

    "Property 15a: typeLabel() returns correct label for each DataContainer type" - {
        /**
         * Validates: Requirements 9.2
         *
         * For any DataContainer, typeLabel() must return exactly the label that
         * corresponds to its subtype:
         *   Text     → "Текст"
         *   Number   → "Число"
         *   Variable → "Переменная"
         *   Location → "Местоположение"
         */

        "Text.typeLabel() always returns \"Текст\"" {
            checkAll(PropTestConfig(iterations = 100), arbText) { dc ->
                dc.typeLabel() shouldBe "Текст"
            }
        }

        "Number.typeLabel() always returns \"Число\"" {
            checkAll(PropTestConfig(iterations = 100), arbNumber) { dc ->
                dc.typeLabel() shouldBe "Число"
            }
        }

        "Variable.typeLabel() always returns \"Переменная\"" {
            checkAll(PropTestConfig(iterations = 100), arbVariable) { dc ->
                dc.typeLabel() shouldBe "Переменная"
            }
        }

        "Location.typeLabel() always returns \"Местоположение\"" {
            checkAll(PropTestConfig(iterations = 100), arbLocation) { dc ->
                dc.typeLabel() shouldBe "Местоположение"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 15b: hologram line format matches §e[i]: §f<TypeLabel>: §7<Value>
    // -----------------------------------------------------------------------

    "Property 15b: hologram line format matches §e[i]: §f<TypeLabel>: §7<Value>" - {
        /**
         * Validates: Requirements 9.2
         *
         * For any DataContainer dc and any non-negative index i, the formatted line
         *   "§e[${i}]: §f${dc.typeLabel()}: §7${dc.displayValue()}"
         * must:
         *   - Start with "§e[i]: §f"
         *   - Contain the correct typeLabel for the DataContainer subtype
         *   - End with "§7" followed by dc.displayValue()
         *   - Match the regex ^§e\[\d+\]: §f\w+: §7.*$
         */

        "Text hologram line matches expected format" {
            checkAll(PropTestConfig(iterations = 100), arbIndex, arbText) { i, dc ->
                val line = hologramLine(i, dc)
                line shouldStartWith "§e[$i]: §f"
                line shouldContain "§f${dc.typeLabel()}: §7"
                line shouldContain "§7${dc.displayValue()}"
                line shouldMatch Regex("^§e\\[\\d+\\]: §f\\S+: §7.*$", RegexOption.DOT_MATCHES_ALL)
            }
        }

        "Number hologram line matches expected format" {
            checkAll(PropTestConfig(iterations = 100), arbIndex, arbNumber) { i, dc ->
                val line = hologramLine(i, dc)
                line shouldStartWith "§e[$i]: §f"
                line shouldContain "§f${dc.typeLabel()}: §7"
                line shouldContain "§7${dc.displayValue()}"
                line shouldMatch Regex("^§e\\[\\d+\\]: §f\\S+: §7.*$", RegexOption.DOT_MATCHES_ALL)
            }
        }

        "Variable hologram line matches expected format" {
            checkAll(PropTestConfig(iterations = 100), arbIndex, arbVariable) { i, dc ->
                val line = hologramLine(i, dc)
                line shouldStartWith "§e[$i]: §f"
                line shouldContain "§f${dc.typeLabel()}: §7"
                line shouldContain "§7${dc.displayValue()}"
                line shouldMatch Regex("^§e\\[\\d+\\]: §f\\S+: §7.*$", RegexOption.DOT_MATCHES_ALL)
            }
        }

        "Location hologram line matches expected format" {
            checkAll(PropTestConfig(iterations = 100), arbIndex, arbLocation) { i, dc ->
                val line = hologramLine(i, dc)
                line shouldStartWith "§e[$i]: §f"
                line shouldContain "§f${dc.typeLabel()}: §7"
                line shouldContain "§7${dc.displayValue()}"
                line shouldMatch Regex("^§e\\[\\d+\\]: §f\\S+: §7.*$", RegexOption.DOT_MATCHES_ALL)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 15c: displayValue() is non-null for all DataContainer types
    // -----------------------------------------------------------------------

    "Property 15c: displayValue() is non-null for all DataContainer types" - {
        /**
         * Validates: Requirements 9.2
         *
         * For any DataContainer, displayValue() must return a non-null string.
         * This ensures the hologram line can always be constructed safely.
         */

        "Text.displayValue() is never null" {
            checkAll(PropTestConfig(iterations = 100), arbText) { dc ->
                dc.displayValue() shouldNotBe null
            }
        }

        "Number.displayValue() is never null" {
            checkAll(PropTestConfig(iterations = 100), arbNumber) { dc ->
                dc.displayValue() shouldNotBe null
            }
        }

        "Variable.displayValue() is never null for non-empty names" {
            checkAll(PropTestConfig(iterations = 100), arbVariable) { dc ->
                dc.displayValue() shouldNotBe null
            }
        }

        "Location.displayValue() is never null" {
            checkAll(PropTestConfig(iterations = 100), arbLocation) { dc ->
                dc.displayValue() shouldNotBe null
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 15d: each DataContainer type maps to exactly one typeLabel
    // -----------------------------------------------------------------------

    "Property 15d: each DataContainer type maps to exactly one typeLabel (bijection)" - {
        /**
         * Validates: Requirements 9.2
         *
         * The mapping from DataContainer subtype to typeLabel is a bijection:
         *   Text     ↔ "Текст"
         *   Number   ↔ "Число"
         *   Variable ↔ "Переменная"
         *   Location ↔ "Местоположение"
         *
         * No two distinct subtypes share the same label, and each subtype always
         * produces the same label regardless of its value.
         */

        "Text and Number have distinct typeLabels" {
            checkAll(PropTestConfig(iterations = 50), arbText, arbNumber) { t, n ->
                t.typeLabel() shouldBe "Текст"
                n.typeLabel() shouldBe "Число"
                (t.typeLabel() == n.typeLabel()) shouldBe false
            }
        }

        "Text and Variable have distinct typeLabels" {
            checkAll(PropTestConfig(iterations = 50), arbText, arbVariable) { t, v ->
                t.typeLabel() shouldBe "Текст"
                v.typeLabel() shouldBe "Переменная"
                (t.typeLabel() == v.typeLabel()) shouldBe false
            }
        }

        "Text and Location have distinct typeLabels" {
            checkAll(PropTestConfig(iterations = 50), arbText, arbLocation) { t, l ->
                t.typeLabel() shouldBe "Текст"
                l.typeLabel() shouldBe "Местоположение"
                (t.typeLabel() == l.typeLabel()) shouldBe false
            }
        }

        "Number and Variable have distinct typeLabels" {
            checkAll(PropTestConfig(iterations = 50), arbNumber, arbVariable) { n, v ->
                n.typeLabel() shouldBe "Число"
                v.typeLabel() shouldBe "Переменная"
                (n.typeLabel() == v.typeLabel()) shouldBe false
            }
        }

        "Number and Location have distinct typeLabels" {
            checkAll(PropTestConfig(iterations = 50), arbNumber, arbLocation) { n, l ->
                n.typeLabel() shouldBe "Число"
                l.typeLabel() shouldBe "Местоположение"
                (n.typeLabel() == l.typeLabel()) shouldBe false
            }
        }

        "Variable and Location have distinct typeLabels" {
            checkAll(PropTestConfig(iterations = 50), arbVariable, arbLocation) { v, l ->
                v.typeLabel() shouldBe "Переменная"
                l.typeLabel() shouldBe "Местоположение"
                (v.typeLabel() == l.typeLabel()) shouldBe false
            }
        }
    }
})
