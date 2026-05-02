package com.opencreativeplus.plugin.compiler

import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.watchdog.TPSMonitor
import com.opencreativeplus.plugin.node.action.SendMessageAction
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
 * Requirements: 7.1, 7.2, 7.4, 7.6, 13.1, 13.2, 13.6, 13.7
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
                val base = scriptProvider(plotId) ?: return@withTimeout null
                val resolved = mutableMapOf<String, String>()

                for (action in base.actions) {
                    // Req 7.1: resolve static placeholders in displayName
                    STATIC_PLACEHOLDER_PATTERN.findAll(action.displayName).forEach { match ->
                        val placeholder = match.value
                        val name = placeholder.removeSurrounding("%")
                        val value = resolveStaticPlaceholder(name)
                        if (value != null) {
                            resolved[placeholder] = value
                        }
                    }

                    // Req 7.2: inline-expansion for SendMessageAction with fully static text
                    if (action is SendMessageAction) {
                        val message = action.params["message"]?.toString() ?: continue
                        if (!DYNAMIC_PLACEHOLDER_PATTERN.containsMatchIn(message)) {
                            resolved["send_message:${action.hashCode()}"] = message
                        }
                    }
                }

                base.copy(resolvedPlaceholders = resolved)
            }
            if (compiled != null) {
                cache[plotId] = compiled
                logger.fine("[OCP] BytecodeCompiler: compiled plot $plotId")
            }
            // Req 7.6: null result (timeout returned null) — do NOT cache
        } catch (e: Exception) {
            // Req 7.6 / 13.7: log and do NOT cache — fall back to AST interpretation
            logger.warning("[OCP] BytecodeCompiler: failed to compile plot $plotId: ${e.message}")
        }
    }

    companion object {
        private const val LOW_TPS_THRESHOLD = 17.0
        /** 10 seconds × 20 ticks/sec = 200 ticks */
        const val LOW_TPS_TICKS = 200
        /** Maximum time allowed for compiling a single plot (Req 7.4, 13.6) */
        const val COMPILE_TIMEOUT_MS = 500L

        /** Matches static placeholder tokens like %player%, %online%, %loc_x%, etc. */
        private val STATIC_PLACEHOLDER_PATTERN = Regex("%[a-zA-Z_]+%")

        /**
         * Matches dynamic placeholders that require runtime context:
         * %var(name)% and any token containing parentheses.
         */
        private val DYNAMIC_PLACEHOLDER_PATTERN = Regex("%var\\([^)]+\\)%|\\$[a-zA-Z_][a-zA-Z0-9_]*")

        /**
         * Resolves a known static placeholder name to its compile-time value.
         * Returns null for dynamic placeholders that require runtime context.
         *
         * Req 7.1: only tokens whose values are known at compile time are resolved.
         */
        fun resolveStaticPlaceholder(name: String): String? = when (name) {
            // These tokens have stable, server-wide values that don't change per-execution
            "online" -> null  // dynamic — changes at runtime
            "player" -> null  // dynamic — depends on who triggered the event
            "victim" -> null  // dynamic — event-specific
            "damager" -> null // dynamic — event-specific
            "killer" -> null  // dynamic — event-specific
            "loc_x"  -> null  // dynamic — player location
            "loc_y"  -> null  // dynamic — player location
            "loc_z"  -> null  // dynamic — player location
            "block_loc" -> null // dynamic — event-specific
            "item"   -> null  // dynamic — event-specific
            // Any unknown token that matches the static pattern is treated as a
            // potential static value (e.g. plugin-registered static placeholders).
            // Return the placeholder itself as a sentinel so callers know it was seen.
            else -> "%$name%"
        }
    }
}
