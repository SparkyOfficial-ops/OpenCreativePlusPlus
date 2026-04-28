@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.compiler

// Feature: ocp-gameplay-systems, Property 23: BytecodeCompiler timing

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.watchdog.TPSMonitor
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.system.measureTimeMillis

/**
 * Property 23: BytecodeCompiler завершается за ≤ 500 мс
 *
 * For any plot script, compilePlot() must complete within 500 milliseconds
 * on an auxiliary thread.
 *
 * **Validates: Requirements 13.6**
 */
class BytecodeCompilerTimingPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    fun stubTpsMonitor(): TPSMonitor = TPSMonitor()

    fun stubEvent(): IEvent = object : IEvent {
        override val nodeId = "stub_event"
        override val displayName = "Stub Event"
        override val eventType = "stub"
    }

    fun stubAction(name: String = "stub"): IAction = object : IAction {
        override val nodeId = "stub_action"
        override val displayName = name
        override suspend fun execute(context: ExecutionContext) {}
    }

    fun compiledScript(plotId: UUID, actionCount: Int, actionName: String): CompiledScript =
        CompiledScript(
            event = stubEvent(),
            actions = List(actionCount) { stubAction(actionName) },
            sourceLocation = "world@0,64,0",
            resolvedPlaceholders = emptyMap()
        )

    fun makeCompiler(
        actionCount: Int,
        actionName: String
    ): BytecodeCompiler = BytecodeCompiler(
        tpsMonitor = stubTpsMonitor(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        scriptProvider = { plotId -> compiledScript(plotId, actionCount, actionName) }
    )

    // -----------------------------------------------------------------------
    // Property 23a: compilation completes within 500 ms for any script size
    // -----------------------------------------------------------------------

    "Property 23a: scheduleCompile completes within 500 ms" - {
        // **Validates: Requirements 13.6**
        // For any plot script (varying action count and placeholder content),
        // the compile operation must finish within the 500 ms budget.
        "compile time is within COMPILE_TIMEOUT_MS" {
            checkAll(
                PropTestConfig(iterations = 50),
                Arb.uuid(),
                Arb.int(1, 200),          // action count: 1..200
                Arb.string(0, 30)         // action display name (may contain %placeholders%)
            ) { plotId, actionCount, actionName ->
                val compiler = makeCompiler(actionCount, actionName)

                val elapsed = measureTimeMillis {
                    runBlocking {
                        compiler.scheduleCompile(plotId)
                    }
                }

                // The compile must finish within the defined timeout budget
                elapsed shouldBeLessThanOrEqualTo BytecodeCompiler.COMPILE_TIMEOUT_MS

                // And the result must be cached
                compiler.getCompiled(plotId).shouldNotBeNull()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 23b: compilation with placeholder-heavy scripts stays within budget
    // -----------------------------------------------------------------------

    "Property 23b: placeholder-heavy scripts compile within 500 ms" - {
        // **Validates: Requirements 13.6**
        // Scripts with many static placeholder patterns (%name%) must still
        // compile within the 500 ms budget.
        "placeholder resolution does not exceed timing budget" {
            checkAll(
                PropTestConfig(iterations = 50),
                Arb.uuid(),
                Arb.int(1, 100)
            ) { plotId, actionCount ->
                // Action names that contain placeholder patterns to stress the resolver
                val placeholderName = "%victim% attacked %damager% for %damage% damage"
                val compiler = makeCompiler(actionCount, placeholderName)

                val elapsed = measureTimeMillis {
                    runBlocking {
                        compiler.scheduleCompile(plotId)
                    }
                }

                elapsed shouldBeLessThanOrEqualTo BytecodeCompiler.COMPILE_TIMEOUT_MS
                compiler.getCompiled(plotId).shouldNotBeNull()
            }
        }
    }
})
