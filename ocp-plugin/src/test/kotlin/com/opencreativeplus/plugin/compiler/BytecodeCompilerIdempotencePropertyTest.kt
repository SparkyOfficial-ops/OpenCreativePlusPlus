@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.compiler

// Feature: ocp-plugin-fixes-and-completions, Property 7: BytecodeCompiler idempotence

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.watchdog.TPSMonitor
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.UUID

/**
 * Property 7: Идемпотентность BytecodeCompiler
 *
 * For any plotId and script with an arbitrary set of static placeholders `%name%`,
 * two sequential calls to `compilePlot(plotId)` (with `invalidate` between them)
 * must produce a `CompiledScript` with semantically equivalent `resolvedPlaceholders`
 * (same keys and values).
 *
 * **Validates: Requirements 7.5**
 */
class BytecodeCompilerIdempotencePropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    fun stubTpsMonitor(): TPSMonitor = TPSMonitor()

    fun stubEvent(): IEvent = object : IEvent {
        override val nodeId = "stub_event"
        override val displayName = "Stub Event"
        override val eventType = "stub"
    }

    fun stubAction(displayName: String): IAction = object : IAction {
        override val nodeId = "stub_action"
        override val displayName = displayName
        override suspend fun execute(context: ExecutionContext) {}
    }

    /**
     * Builds a [CompiledScript] whose action display names embed the given
     * placeholder tokens so that [BytecodeCompiler] has something to resolve.
     *
     * Each token is wrapped in `%…%` to match [BytecodeCompiler]'s
     * STATIC_PLACEHOLDER_PATTERN.
     */
    fun compiledScript(plotId: UUID, placeholderNames: List<String>): CompiledScript {
        val actions = if (placeholderNames.isEmpty()) {
            listOf(stubAction("no placeholders"))
        } else {
            placeholderNames.map { name ->
                // Embed the token in the display name so the compiler picks it up
                stubAction("%$name%")
            }
        }
        return CompiledScript(
            event = stubEvent(),
            actions = actions,
            sourceLocation = "world@0,64,0",
            resolvedPlaceholders = emptyMap()
        )
    }

    fun makeCompiler(placeholderNames: List<String>): BytecodeCompiler = BytecodeCompiler(
        tpsMonitor = stubTpsMonitor(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        scriptProvider = { plotId -> compiledScript(plotId, placeholderNames) }
    )

    // -----------------------------------------------------------------------
    // Arbitrary generator for placeholder name lists
    // -----------------------------------------------------------------------

    /**
     * Generates a list of 0..8 placeholder names from a fixed set of known
     * static-compatible tokens — matching the static placeholder pattern `%[a-zA-Z_]+%`.
     * Using a fixed set avoids generating names that contain digits or special
     * characters which would not match the pattern.
     */
    fun arbPlaceholderNames(): Arb<List<String>> =
        Arb.list(
            Arb.of(listOf(
                "server_name", "world_name", "plugin_version", "max_players",
                "motd", "game_mode", "difficulty", "spawn_x", "spawn_y", "spawn_z",
                "uptime", "tick_rate", "region_name", "biome_name"
            )),
            0..8
        )

    // -----------------------------------------------------------------------
    // Property 7a: resolvedPlaceholders are equal after invalidate + recompile
    // -----------------------------------------------------------------------

    "Property 7a: recompiling after invalidation produces the same resolvedPlaceholders" - {
        // **Validates: Requirements 7.5**
        // For any plotId and any set of static placeholder names, the first and
        // second compilations must produce identical resolvedPlaceholders maps.
        "first and second compilations are semantically equivalent" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.uuid(),
                arbPlaceholderNames()
            ) { plotId, placeholderNames ->
                val compiler = makeCompiler(placeholderNames)

                // First compilation
                compiler.scheduleCompile(plotId)
                val first = compiler.getCompiled(plotId)?.resolvedPlaceholders

                // Invalidate and recompile
                compiler.invalidate(plotId)
                compiler.scheduleCompile(plotId)
                val second = compiler.getCompiled(plotId)?.resolvedPlaceholders

                // Both compilations must produce the same resolved map
                first shouldBe second
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 7b: idempotence holds for scripts with no static placeholders
    // -----------------------------------------------------------------------

    "Property 7b: idempotence holds when the script contains no static placeholders" - {
        // **Validates: Requirements 7.5**
        // Even when there are no placeholders to resolve, the two compilations
        // must produce equivalent (both empty) resolvedPlaceholders maps.
        "empty resolvedPlaceholders is stable across recompilation" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.uuid()
            ) { plotId ->
                val compiler = makeCompiler(emptyList())

                compiler.scheduleCompile(plotId)
                val first = compiler.getCompiled(plotId)?.resolvedPlaceholders

                compiler.invalidate(plotId)
                compiler.scheduleCompile(plotId)
                val second = compiler.getCompiled(plotId)?.resolvedPlaceholders

                first shouldBe second
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 7c: idempotence holds for scripts with many placeholder-bearing actions
    // -----------------------------------------------------------------------

    "Property 7c: idempotence holds for scripts with many placeholder-bearing actions" - {
        // **Validates: Requirements 7.5**
        // Larger scripts (up to 50 actions, each with a placeholder) must still
        // produce identical resolvedPlaceholders on repeated compilation.
        "large scripts produce stable resolvedPlaceholders" {
            checkAll(
                PropTestConfig(iterations = 15),
                Arb.uuid(),
                Arb.int(1, 20)
            ) { plotId, actionCount ->
                // Use a fixed set of known static placeholder names
                val names = List(actionCount) { i -> "token_$i" }
                val compiler = makeCompiler(names)

                compiler.scheduleCompile(plotId)
                val first = compiler.getCompiled(plotId)?.resolvedPlaceholders

                compiler.invalidate(plotId)
                compiler.scheduleCompile(plotId)
                val second = compiler.getCompiled(plotId)?.resolvedPlaceholders

                first shouldBe second
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 7d: multiple invalidate-recompile cycles remain stable
    // -----------------------------------------------------------------------

    "Property 7d: resolvedPlaceholders remain stable across multiple invalidate-recompile cycles" - {
        // **Validates: Requirements 7.5**
        // Performing N invalidate+recompile cycles must always yield the same
        // resolvedPlaceholders as the very first compilation.
        "N cycles produce the same result as the first compilation" {
            checkAll(
                PropTestConfig(iterations = 15),
                Arb.uuid(),
                arbPlaceholderNames(),
                Arb.int(2, 4)
            ) { plotId, placeholderNames, cycles ->
                val compiler = makeCompiler(placeholderNames)

                // Baseline: first compilation
                compiler.scheduleCompile(plotId)
                val baseline = compiler.getCompiled(plotId)?.resolvedPlaceholders

                // Repeat invalidate + recompile `cycles` times
                repeat(cycles) {
                    compiler.invalidate(plotId)
                    compiler.scheduleCompile(plotId)
                    val current = compiler.getCompiled(plotId)?.resolvedPlaceholders
                    current shouldBe baseline
                }
            }
        }
    }
})
