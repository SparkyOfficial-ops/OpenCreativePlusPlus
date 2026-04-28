@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.compiler

// Feature: ocp-gameplay-systems, Property 24: BytecodeCompiler fallback при ошибке компиляции

import com.opencreativeplus.api.execution.ExecutionContext
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Property 24: BytecodeCompiler fallback при ошибке компиляции
 *
 * When pre-compilation fails for a plot, the engine must NOT cache the result
 * (falling back to AST interpretation) and must not propagate the exception.
 *
 * **Validates: Requirements 13.7**
 */
class BytecodeCompilerFallbackPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    fun stubTpsMonitor(): TPSMonitor = TPSMonitor()

    fun stubEvent(): IEvent = object : IEvent {
        override val nodeId = "stub_event"
        override val displayName = "Stub Event"
        override val eventType = "stub"
    }

    fun stubAction(): IAction = object : IAction {
        override val nodeId = "stub_action"
        override val displayName = "stub"
        override suspend fun execute(context: ExecutionContext) {}
    }

    fun compiledScript(id: UUID): CompiledScript = CompiledScript(
        event = stubEvent(),
        actions = listOf(stubAction()),
        sourceLocation = "world@0,64,0",
        resolvedPlaceholders = emptyMap()
    )

    /**
     * Creates a compiler backed by a dedicated Job scope so we can join() it
     * after scheduleCompile to ensure the async work has fully completed.
     */
    fun makeFailingCompiler(): Pair<BytecodeCompiler, Job> {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Default + job)
        val compiler = BytecodeCompiler(
            tpsMonitor = stubTpsMonitor(),
            scope = scope,
            scriptProvider = { _ -> throw RuntimeException("Simulated compile failure") }
        )
        return compiler to job
    }

    fun makeSuccessCompiler(): Pair<BytecodeCompiler, Job> {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Default + job)
        val compiler = BytecodeCompiler(
            tpsMonitor = stubTpsMonitor(),
            scope = scope,
            scriptProvider = { id -> compiledScript(id) }
        )
        return compiler to job
    }

    /**
     * Schedules a compile and waits for all children of the scope's Job to finish.
     */
    suspend fun BytecodeCompiler.scheduleAndJoin(plotId: UUID, parentJob: Job) {
        scheduleCompile(plotId)
        // Wait for all child coroutines launched under parentJob to complete
        parentJob.children.forEach { it.join() }
    }

    // -----------------------------------------------------------------------
    // Property 24a: scriptProvider throwing → cache stays null (Req 13.7)
    // -----------------------------------------------------------------------

    "Property 24a: failed compile does not populate the cache" - {
        // **Validates: Requirements 13.7**
        // When the scriptProvider throws, compilePlot must catch the exception
        // and NOT store anything in the cache — fallback to AST interpretation.
        "getCompiled returns null after a throwing scriptProvider" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid()
            ) { plotId ->
                val (compiler, job) = makeFailingCompiler()
                compiler.scheduleAndJoin(plotId, job)
                compiler.getCompiled(plotId).shouldBeNull()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 24b: exception does not propagate out of scheduleCompile (Req 13.7)
    // -----------------------------------------------------------------------

    "Property 24b: compile failure does not propagate the exception to the caller" - {
        // **Validates: Requirements 13.7**
        // scheduleCompile must swallow the compilation error internally.
        // The test passing without any thrown exception proves this property.
        "scheduleCompile does not throw when scriptProvider fails" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid()
            ) { plotId ->
                val (compiler, job) = makeFailingCompiler()
                // Must not throw — if it does, the test fails
                compiler.scheduleAndJoin(plotId, job)
                // Reaching here means no exception escaped
                compiler.getCompiled(plotId).shouldBeNull()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 24c: after failed compile, a subsequent successful compile works (Req 13.7)
    // -----------------------------------------------------------------------

    "Property 24c: a successful compile after a failure populates the cache normally" - {
        // **Validates: Requirements 13.7**
        // After a failed compile leaves the cache empty, a new compiler with a
        // working scriptProvider must be able to populate the cache for the same plotId.
        "cache is populated on the second (successful) compile attempt" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid()
            ) { plotId ->
                // First: failing compiler — cache stays null
                val (failingCompiler, failingJob) = makeFailingCompiler()
                failingCompiler.scheduleAndJoin(plotId, failingJob)
                failingCompiler.getCompiled(plotId).shouldBeNull()

                // Second: working compiler for the same plotId — cache is populated
                val (successCompiler, successJob) = makeSuccessCompiler()
                successCompiler.scheduleAndJoin(plotId, successJob)
                successCompiler.getCompiled(plotId).shouldNotBeNull()
            }
        }
    }
})
