@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.compiler

// Feature: ocp-gameplay-systems, Property 22: BytecodeCompiler invalidation

import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.watchdog.TPSMonitor
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.UUID

/**
 * Property 22: BytecodeCompiler инвалидация при смене режима
 *
 * For any plot, switching from play to dev mode must remove the compiled form
 * from the cache (`getCompiled(plotId) == null`).
 *
 * **Validates: Requirements 13.4**
 */
class BytecodeCompilerInvalidationPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    fun stubTpsMonitor(tps: Double = 20.0): TPSMonitor = TPSMonitor()

    fun stubEvent(): IEvent = object : IEvent {
        override val nodeId = "stub_event"
        override val displayName = "Stub Event"
        override val eventType = "stub"
    }

    fun stubAction(name: String = "stub"): IAction = object : IAction {
        override val nodeId = "stub_action"
        override val displayName = name
        override suspend fun execute(context: com.opencreativeplus.api.execution.ExecutionContext) {}
    }

    fun compiledScript(plotId: UUID): CompiledScript = CompiledScript(
        event = stubEvent(),
        actions = listOf(stubAction()),
        sourceLocation = "world@0,64,0",
        resolvedPlaceholders = emptyMap()
    )

    fun makeCompiler(
        tps: Double = 20.0,
        scriptProvider: (UUID) -> CompiledScript? = ::compiledScript
    ): BytecodeCompiler = BytecodeCompiler(
        tpsMonitor = stubTpsMonitor(tps),
        scope = CoroutineScope(Dispatchers.Unconfined),
        scriptProvider = scriptProvider
    )

    // -----------------------------------------------------------------------
    // Property 22a: invalidate() removes a cached entry (Req 13.4)
    // -----------------------------------------------------------------------

    "Property 22a: invalidate() removes the compiled form from the cache" - {
        // **Validates: Requirements 13.4**
        // For any plotId that has been compiled and cached, calling invalidate()
        // must result in getCompiled() returning null.
        "getCompiled returns null after invalidate" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid()
            ) { plotId ->
                val compiler = makeCompiler()

                // Seed the cache by scheduling a compile synchronously via Unconfined dispatcher
                compiler.scheduleCompile(plotId)

                // Confirm it was cached
                compiler.getCompiled(plotId).shouldNotBeNull()

                // Simulate play → dev mode transition: invalidate
                compiler.invalidate(plotId)

                // Cache must be empty for this plot
                compiler.getCompiled(plotId).shouldBeNull()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 22b: invalidate() is idempotent (Req 13.4)
    // -----------------------------------------------------------------------

    "Property 22b: invalidate() is idempotent — calling it multiple times is safe" - {
        // **Validates: Requirements 13.4**
        // Calling invalidate() on a plot that is not in the cache (or already
        // invalidated) must not throw and must leave getCompiled() returning null.
        "repeated invalidate does not throw and keeps result null" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid()
            ) { plotId ->
                val compiler = makeCompiler()

                // Invalidate without ever compiling — must be safe
                compiler.invalidate(plotId)
                compiler.getCompiled(plotId).shouldBeNull()

                // Compile, then invalidate twice
                compiler.scheduleCompile(plotId)
                compiler.invalidate(plotId)
                compiler.invalidate(plotId)
                compiler.getCompiled(plotId).shouldBeNull()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 22c: invalidate() only removes the targeted plot (Req 13.4)
    // -----------------------------------------------------------------------

    "Property 22c: invalidate() only removes the targeted plot, not others" - {
        // **Validates: Requirements 13.4**
        // When multiple plots are compiled, invalidating one must not affect
        // the cached entries of other plots.
        "other plots remain cached after targeted invalidation" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid(),
                Arb.uuid()
            ) { plotA, plotB ->
                // Ensure distinct UUIDs to avoid false negatives
                if (plotA == plotB) return@checkAll

                val compiler = makeCompiler()

                compiler.scheduleCompile(plotA)
                compiler.scheduleCompile(plotB)

                compiler.getCompiled(plotA).shouldNotBeNull()
                compiler.getCompiled(plotB).shouldNotBeNull()

                // Invalidate only plotA (play → dev mode for plotA)
                compiler.invalidate(plotA)

                compiler.getCompiled(plotA).shouldBeNull()
                compiler.getCompiled(plotB).shouldNotBeNull()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 22d: compile after invalidate re-populates the cache (Req 13.4, 13.5)
    // -----------------------------------------------------------------------

    "Property 22d: scheduling compile after invalidate re-populates the cache" - {
        // **Validates: Requirements 13.4, 13.5**
        // After a plot transitions back to play mode, scheduling a new compile
        // must restore a non-null entry in the cache.
        "cache is repopulated after invalidate + scheduleCompile" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid()
            ) { plotId ->
                val compiler = makeCompiler()

                // Initial compile
                compiler.scheduleCompile(plotId)
                compiler.getCompiled(plotId).shouldNotBeNull()

                // dev mode → invalidate
                compiler.invalidate(plotId)
                compiler.getCompiled(plotId).shouldBeNull()

                // play mode → re-compile
                compiler.scheduleCompile(plotId)
                compiler.getCompiled(plotId).shouldNotBeNull()
            }
        }
    }
})
