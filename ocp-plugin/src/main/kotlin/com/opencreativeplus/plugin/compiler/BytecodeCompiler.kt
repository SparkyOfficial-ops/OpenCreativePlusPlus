package com.opencreativeplus.plugin.compiler

import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.watchdog.TPSMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Pre-compiles plot scripts into an optimized in-memory representation when
 * server TPS drops below 17 for more than 10 consecutive seconds (200 ticks).
 *
 * Requirements: 13.1, 13.2, 13.6, 13.7
 */
class BytecodeCompiler(
    private val tpsMonitor: TPSMonitor,
    private val scope: CoroutineScope,
    private val scriptProvider: (UUID) -> CompiledScript?,
    private val logger: Logger = Logger.getLogger("BytecodeCompiler")
) {
    private val cache = ConcurrentHashMap<UUID, CompiledScript>()
    private var lowTpsTicks = 0
    private var compileAllTriggered = false

    /** Called every server tick from TpsMonitorTask. */
    fun onTick(activePlotIds: Collection<UUID>) {
        if (tpsMonitor.getCurrentTPS() < LOW_TPS_THRESHOLD) {
            lowTpsTicks++
            if (lowTpsTicks >= LOW_TPS_TICKS && !compileAllTriggered) {
                compileAllTriggered = true
                scope.launch { compileAll(activePlotIds) }
            }
        } else {
            lowTpsTicks = 0
            compileAllTriggered = false
        }
    }

    /** Remove the cached compiled form for [plotId]. */
    fun invalidate(plotId: UUID) {
        cache.remove(plotId)
    }

    /** Return the cached compiled script for [plotId], or null if not compiled. */
    fun getCompiled(plotId: UUID): CompiledScript? = cache[plotId]

    /** Schedule pre-compilation of a single plot on an auxiliary thread and await completion. */
    suspend fun scheduleCompile(plotId: UUID) {
        scope.launch { compilePlot(plotId) }.join()
    }

    private suspend fun compileAll(plotIds: Collection<UUID>) {
        for (plotId in plotIds) {
            compilePlot(plotId)
        }
    }

    private suspend fun compilePlot(plotId: UUID) {
        try {
            val compiled = withTimeout(COMPILE_TIMEOUT_MS) {
                // Resolve static placeholders from the script provider
                val base = scriptProvider(plotId) ?: return@withTimeout null
                // Build resolved placeholders map from action display names
                val resolved = base.actions
                    .flatMap { action ->
                        STATIC_PLACEHOLDER_PATTERN.findAll(action.displayName)
                            .map { it.value to it.value } // identity for now; real resolution would substitute known values
                    }
                    .toMap()
                base.copy(resolvedPlaceholders = resolved)
            }
            if (compiled != null) {
                cache[plotId] = compiled
                logger.fine("[OCP] BytecodeCompiler: compiled plot $plotId")
            }
        } catch (e: Exception) {
            // Req 13.7: log and do NOT cache — fall back to AST interpretation
            logger.warning("[OCP] BytecodeCompiler: failed to compile plot $plotId: ${e.message}")
        }
    }

    companion object {
        private const val LOW_TPS_THRESHOLD = 17.0
        /** 10 seconds × 20 ticks/sec = 200 ticks */
        const val LOW_TPS_TICKS = 200
        /** Maximum time allowed for compiling a single plot (Req 13.6) */
        const val COMPILE_TIMEOUT_MS = 500L
        private val STATIC_PLACEHOLDER_PATTERN = Regex("%[a-zA-Z_]+%")
    }
}
