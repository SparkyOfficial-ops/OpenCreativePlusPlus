// Feature: ocp-manifest-roadmap, Property 7: Изоляция player-scoped переменных
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.core.execution

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.*
import org.bson.Document
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.FindFlow
import io.mockk.every
import io.mockk.coEvery

/**
 * Property-based tests for player-scoped variable isolation in [VariableManager].
 *
 * Property 7: Изоляция player-scoped переменных
 *
 * For any two distinct players on the same plot, setting `%player%_score` for
 * player A must not affect the value of `%player%_score` for player B.
 *
 * **Validates: Requirements 6.1, 6.3**
 */
class VariableScopeIsolationPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Arbitrary non-empty player names that are distinct from each other. */
    val arbPlayerName: Arb<String> =
        Arb.string(1..20).map { s ->
            val cleaned = s.filter { it.isLetterOrDigit() || it == '_' }
            if (cleaned.isEmpty()) "player" else cleaned
        }.filter { it.isNotEmpty() }

    /** Arbitrary pairs of distinct player names. */
    val arbDistinctPlayerPair: Arb<Pair<String, String>> =
        Arb.pair(arbPlayerName, arbPlayerName).filter { (a, b) -> a != b }

    /** Arbitrary non-empty variable values. */
    val arbValue: Arb<String> =
        Arb.string(1..30).filter { it.isNotEmpty() }

    fun makeVariableManager(): VariableManager {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>(relaxed = true)
        every { database.getCollection<Document>("plot_variables") } returns collection
        val ff = mockk<FindFlow<Document>>(relaxed = true)
        coEvery { ff.collect(any()) } returns Unit
        coEvery { collection.find(any<Document>()) } returns ff
        return VariableManager(database)
    }

    // -----------------------------------------------------------------------
    // Property 7a: player-scoped keys are isolated between players
    // -----------------------------------------------------------------------

    "Property 7a: setting %player%_score for player A does not affect player B" - {
        "player-scoped variable isolation" {
            // Validates: Requirements 6.1, 6.3
            checkAll(
                PropTestConfig(iterations = 100),
                arbDistinctPlayerPair,
                arbValue,
                arbValue
            ) { (playerA, playerB), valueA, valueB ->
                val vm = makeVariableManager()
                val plotScope = vm.getPlotScope(java.util.UUID.randomUUID())

                val varName = "%player%_score"

                // Resolve keys for each player
                val keyA = vm.resolveVariableKey(varName, playerA)
                val keyB = vm.resolveVariableKey(varName, playerB)

                // Keys must be distinct for distinct players
                keyA shouldNotBe keyB

                // Set value for player A
                plotScope.set(keyA, valueA)
                // Set value for player B
                plotScope.set(keyB, valueB)

                // Reading player A's value must not return player B's value
                plotScope.get(keyA) shouldBe valueA
                plotScope.get(keyB) shouldBe valueB
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 7b: resolveVariableKey produces distinct keys for distinct players
    // -----------------------------------------------------------------------

    "Property 7b: resolveVariableKey produces distinct keys for any two distinct players" - {
        "key uniqueness per player" {
            // Validates: Requirements 6.1
            checkAll(
                PropTestConfig(iterations = 100),
                arbDistinctPlayerPair
            ) { (playerA, playerB) ->
                val vm = makeVariableManager()
                val keyA = vm.resolveVariableKey("%player%_score", playerA)
                val keyB = vm.resolveVariableKey("%player%_score", playerB)
                keyA shouldNotBe keyB
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 7c: global (non-prefixed) variables are shared across players
    // -----------------------------------------------------------------------

    "Property 7c: global variable is shared — same key regardless of player" - {
        "global variable key is player-independent" {
            // Validates: Requirements 6.2
            checkAll(
                PropTestConfig(iterations = 100),
                arbDistinctPlayerPair
            ) { (playerA, playerB) ->
                val vm = makeVariableManager()
                val keyA = vm.resolveVariableKey("globalScore", playerA)
                val keyB = vm.resolveVariableKey("globalScore", playerB)
                keyA shouldBe keyB
                keyA shouldBe "globalScore"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 7d: null playerName falls back to raw name
    // -----------------------------------------------------------------------

    "Property 7d: resolveVariableKey with null playerName returns raw name" - {
        "null player fallback" {
            // Validates: Requirements 6.1 (null playerName → raw name)
            checkAll(PropTestConfig(iterations = 100), arbPlayerName) { suffix ->
                val vm = makeVariableManager()
                val rawName = "%player%_$suffix"
                val key = vm.resolveVariableKey(rawName, null)
                key shouldBe rawName
            }
        }
    }
})
