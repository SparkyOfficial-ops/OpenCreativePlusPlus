package com.opencreativeplus.plugin.compiler

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.watchdog.TPSMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [BytecodeCompiler] covering:
 * - Fallback при исключении компиляции (Req 13.7)
 * - Инвалидация при смене режима (Req 13.4)
 */
class BytecodeCompilerTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private val plotId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun stubEvent(): IEvent = object : IEvent {
        override val nodeId = "stub_event"
        override val displayName = "Stub Event"
        override val eventType = "stub"
    }

    private fun stubAction(): IAction = object : IAction {
        override val nodeId = "stub_action"
        override val displayName = "stub"
        override suspend fun execute(context: ExecutionContext) {}
    }

    private fun compiledScript(): CompiledScript = CompiledScript(
        event = stubEvent(),
        actions = listOf(stubAction()),
        sourceLocation = "world@0,64,0"
    )

    /**
     * Creates a compiler with a dedicated Job scope so child coroutines can be joined.
     */
    private fun makeCompiler(
        scriptProvider: (UUID) -> CompiledScript?
    ): Pair<BytecodeCompiler, Job> {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Default + job)
        val compiler = BytecodeCompiler(
            tpsMonitor = TPSMonitor(),
            scope = scope,
            scriptProvider = scriptProvider
        )
        return compiler to job
    }

    /** Schedules a compile and waits for all child coroutines to finish. */
    private suspend fun BytecodeCompiler.scheduleAndJoin(id: UUID, parentJob: Job) {
        scheduleCompile(id)
        parentJob.children.forEach { it.join() }
    }

    // -----------------------------------------------------------------------
    // Fallback при исключении компиляции (Req 13.7)
    // -----------------------------------------------------------------------

    @Test
    fun `getCompiled returns null when scriptProvider throws during compilation`() = runBlocking {
        val (compiler, job) = makeCompiler { throw RuntimeException("Simulated compile failure") }

        compiler.scheduleAndJoin(plotId, job)

        assertNull(
            compiler.getCompiled(plotId),
            "Cache must stay empty when compilation throws — fallback to AST interpretation"
        )
    }

    @Test
    fun `compilation exception does not propagate to the caller`() = runBlocking {
        val (compiler, job) = makeCompiler { throw RuntimeException("Simulated compile failure") }

        // Must not throw — exception must be swallowed inside compilePlot
        compiler.scheduleAndJoin(plotId, job)

        // Reaching here confirms no exception escaped
        assertNull(compiler.getCompiled(plotId))
    }

    @Test
    fun `after failed compile a subsequent successful compile populates the cache`() = runBlocking {
        // First attempt: failing compiler — cache stays null
        val (failingCompiler, failingJob) = makeCompiler { throw RuntimeException("fail") }
        failingCompiler.scheduleAndJoin(plotId, failingJob)
        assertNull(failingCompiler.getCompiled(plotId))

        // Second attempt: working compiler for the same plotId — cache is populated
        val (successCompiler, successJob) = makeCompiler { compiledScript() }
        successCompiler.scheduleAndJoin(plotId, successJob)
        assertNotNull(successCompiler.getCompiled(plotId))
    }

    // -----------------------------------------------------------------------
    // Инвалидация при смене режима (Req 13.4)
    // -----------------------------------------------------------------------

    @Test
    fun `invalidate removes compiled form from cache on mode change`() = runBlocking {
        val (compiler, job) = makeCompiler { compiledScript() }

        compiler.scheduleAndJoin(plotId, job)
        assertNotNull(compiler.getCompiled(plotId), "Cache should be populated before invalidation")

        // Simulate play → dev mode transition
        compiler.invalidate(plotId)

        assertNull(
            compiler.getCompiled(plotId),
            "Cache must be empty after invalidate — plot switched to dev mode"
        )
    }

    @Test
    fun `invalidate on uncached plot does not throw`() {
        val (compiler, _) = makeCompiler { compiledScript() }

        // Invalidating a plot that was never compiled must be safe
        compiler.invalidate(plotId)

        assertNull(compiler.getCompiled(plotId))
    }

    @Test
    fun `invalidate only removes the targeted plot leaving others cached`() = runBlocking {
        val otherPlotId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val (compiler, job) = makeCompiler { compiledScript() }

        compiler.scheduleAndJoin(plotId, job)
        compiler.scheduleAndJoin(otherPlotId, job)

        assertNotNull(compiler.getCompiled(plotId))
        assertNotNull(compiler.getCompiled(otherPlotId))

        // Only invalidate plotId (play → dev mode for that plot)
        compiler.invalidate(plotId)

        assertNull(compiler.getCompiled(plotId), "Invalidated plot must not be in cache")
        assertNotNull(compiler.getCompiled(otherPlotId), "Other plot must remain cached")
    }

    @Test
    fun `compile after invalidate re-populates the cache`() = runBlocking {
        val (compiler, job) = makeCompiler { compiledScript() }

        // Initial compile (play mode)
        compiler.scheduleAndJoin(plotId, job)
        assertNotNull(compiler.getCompiled(plotId))

        // Switch to dev mode → invalidate
        compiler.invalidate(plotId)
        assertNull(compiler.getCompiled(plotId))

        // Switch back to play mode → re-compile
        compiler.scheduleAndJoin(plotId, job)
        assertNotNull(compiler.getCompiled(plotId), "Cache must be repopulated after re-compile")
    }
}
