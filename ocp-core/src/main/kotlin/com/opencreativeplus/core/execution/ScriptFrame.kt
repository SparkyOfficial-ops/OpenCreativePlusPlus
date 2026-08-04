package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.ExecutionContext
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Tracks the loop state for a single `repeat`/`while` node in the State Machine pattern.
 *
 * Each time the script enters a loop, a [LoopFrame] is pushed onto [ScriptFrame.loopStack].
 * When all iterations are complete (or the loop is broken), the frame is popped.
 *
 * Requirements: 2.1, 2.4
 */
data class LoopFrame(
    /** Index of the `repeat`/`while` node in the compiled script's action list. */
    val startIndex: Int,
    /** Total number of iterations N from `repeat(N)`. */
    val totalIterations: Int,
    /** The iteration that will be executed next (0-based). */
    var currentIteration: Int
)

/**
 * State object for one executing script instance, replacing the previous coroutine (`Job`)-based model.
 *
 * A [ScriptFrame] is created by [ExecutionEngine] and handed to the [Ticker], which advances it
 * one step per tick in the main thread. All mutable state that was previously held inside a
 * suspended coroutine is now explicit on this object, making the execution model fully
 * deterministic and inspectable.
 *
 * Lifecycle:
 * 1. Created by `ExecutionEngine.executeScript()` with `programCounter = 0`.
 * 2. Enqueued in `Ticker.activeFrames`.
 * 3. Advanced by `Ticker.stepFrame()` each tick until the script completes or is cancelled.
 * 4. Removed from the ticker and resources are released.
 *
 * Requirements: 2.1
 */
class ScriptFrame(
    /** Unique identifier for this execution instance. */
    val frameId: UUID,
    /** The plot this script belongs to. */
    val plotId: UUID,
    /** The player who triggered the script, or `null` for server-side executions. */
    val player: Player?,
    /** The compiled script being executed. */
    val script: CompiledScript,
    /** Execution context providing variable storage and player references. */
    val context: ExecutionContext,
    /**
     * Index of the next action in [CompiledScript.actions] to execute.
     * Incremented by the Ticker after each successful step.
     */
    var programCounter: Int = 0,
    /**
     * Stack of active loop frames for nested `repeat`/`while` constructs.
     * Top of stack (last element) is the innermost active loop.
     */
    val loopStack: ArrayDeque<LoopFrame> = ArrayDeque(),
    /**
     * When `true`, the Ticker skips this frame until the next tick.
     * Set after exhausting the per-tick batch budget for a loop iteration.
     */
    var waitingForNextTick: Boolean = false,
    /**
     * When `true`, the frame is waiting for an `AsyncAction` to complete
     * in a background thread before it can be resumed.
     */
    var asyncPending: Boolean = false,
    /**
     * Number of consecutive ticks this frame has been waiting (either
     * [waitingForNextTick] or [asyncPending]). Used by the Watchdog stale-check (Req 2.6):
     * if this exceeds 200, the frame is cancelled and the plot owner is notified.
     */
    var staleTicks: Int = 0,
    /**
     * Cumulative execution time (ms) consumed by this frame in the current tick.
     * Reset to 0 at the start of each tick. Used to enforce the 40 ms tick budget (Req 9.5).
     */
    var tickBudgetUsedMs: Long = 0L
)
