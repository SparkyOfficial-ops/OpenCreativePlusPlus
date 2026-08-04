package com.opencreativeplus.core.execution

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.mockk
import java.util.UUID

/**
 * Unit tests for [ScriptFrame] and [LoopFrame].
 *
 * Covers:
 * - Default field values on construction (Req 2.1)
 * - Mutation of [ScriptFrame.programCounter]
 * - Push/pop of [LoopFrame] onto [ScriptFrame.loopStack] (Req 2.4)
 * - Nested loop stack order (LIFO) (Req 2.4)
 *
 * Validates: Requirements 2.1, 2.4
 */
class ScriptFrameTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    fun makeScript() = CompiledScript(
        event = mockk(relaxed = true),
        actions = emptyList(),
        sourceLocation = "test@0,0,0"
    )

    fun makeContext() = mockk<com.opencreativeplus.api.execution.ExecutionContext>(relaxed = true)

    fun freshFrame(
        frameId: UUID = UUID.randomUUID(),
        plotId: UUID = UUID.randomUUID()
    ) = ScriptFrame(
        frameId = frameId,
        plotId = plotId,
        player = null,
        script = makeScript(),
        context = makeContext()
    )

    fun loopFrame(startIndex: Int, totalIterations: Int, currentIteration: Int = 0) =
        LoopFrame(startIndex, totalIterations, currentIteration)

    // -----------------------------------------------------------------------
    // 1. Initialization — default field values (Req 2.1)
    // -----------------------------------------------------------------------

    "ScriptFrame initialization" - {

        "programCounter defaults to 0" {
            // Req 2.1: ScriptFrame SHALL store the index of the current instruction
            val frame = freshFrame()
            frame.programCounter shouldBe 0
        }

        "loopStack is empty on creation" {
            // Req 2.1: ScriptFrame SHALL store a stack of nested loops; it starts empty
            val frame = freshFrame()
            frame.loopStack.shouldBeEmpty()
        }

        "waitingForNextTick defaults to false" {
            // Req 2.1: ScriptFrame SHALL store a waiting-for-next-tick flag, default false
            val frame = freshFrame()
            frame.waitingForNextTick shouldBe false
        }

        "asyncPending defaults to false" {
            // Req 2.1: asyncPending flag defaults to false
            val frame = freshFrame()
            frame.asyncPending shouldBe false
        }

        "staleTicks defaults to 0" {
            // Req 2.1: staleTicks counter defaults to 0
            val frame = freshFrame()
            frame.staleTicks shouldBe 0
        }

        "tickBudgetUsedMs defaults to 0" {
            // Req 2.1: tickBudgetUsedMs defaults to 0
            val frame = freshFrame()
            frame.tickBudgetUsedMs shouldBe 0L
        }

        "provided frameId and plotId are stored correctly" {
            val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val plotId = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val frame = ScriptFrame(
                frameId = id,
                plotId = plotId,
                player = null,
                script = makeScript(),
                context = makeContext()
            )
            frame.frameId shouldBe id
            frame.plotId shouldBe plotId
        }

        "player is null when not provided" {
            val frame = freshFrame()
            frame.player shouldBe null
        }
    }

    // -----------------------------------------------------------------------
    // 2. programCounter mutation (Req 2.1)
    // -----------------------------------------------------------------------

    "ScriptFrame programCounter" - {

        "can be incremented" {
            // Req 2.1: programCounter must be mutable to allow the Ticker to advance it
            val frame = freshFrame()
            frame.programCounter++
            frame.programCounter shouldBe 1
        }

        "can be incremented multiple times" {
            val frame = freshFrame()
            repeat(5) { frame.programCounter++ }
            frame.programCounter shouldBe 5
        }

        "can be set to an arbitrary value" {
            val frame = freshFrame()
            frame.programCounter = 42
            frame.programCounter shouldBe 42
        }

        "can be reset to 0" {
            val frame = freshFrame()
            frame.programCounter = 10
            frame.programCounter = 0
            frame.programCounter shouldBe 0
        }
    }

    // -----------------------------------------------------------------------
    // 3. Push LoopFrame (Req 2.4)
    // -----------------------------------------------------------------------

    "ScriptFrame loopStack — push" - {

        "pushing one LoopFrame makes stack size 1" {
            // Req 2.4: loopStack SHALL grow on push
            val frame = freshFrame()
            frame.loopStack.addLast(loopFrame(startIndex = 0, totalIterations = 3))
            frame.loopStack shouldHaveSize 1
        }

        "pushed LoopFrame has correct fields" {
            // Req 2.4: stored LoopFrame retains its startIndex, totalIterations, currentIteration
            val frame = freshFrame()
            val lf = loopFrame(startIndex = 5, totalIterations = 10, currentIteration = 2)
            frame.loopStack.addLast(lf)
            val top = frame.loopStack.last()
            top.startIndex shouldBe 5
            top.totalIterations shouldBe 10
            top.currentIteration shouldBe 2
        }

        "pushing two LoopFrames makes stack size 2" {
            val frame = freshFrame()
            frame.loopStack.addLast(loopFrame(0, 3))
            frame.loopStack.addLast(loopFrame(4, 5))
            frame.loopStack shouldHaveSize 2
        }

        "pushing three LoopFrames makes stack size 3" {
            val frame = freshFrame()
            repeat(3) { i -> frame.loopStack.addLast(loopFrame(i, i + 1)) }
            frame.loopStack shouldHaveSize 3
        }
    }

    // -----------------------------------------------------------------------
    // 4. Pop LoopFrame (Req 2.4)
    // -----------------------------------------------------------------------

    "ScriptFrame loopStack — pop" - {

        "popping from a single-element stack returns the pushed frame" {
            // Req 2.4: pop returns the correct LoopFrame
            val frame = freshFrame()
            val lf = loopFrame(startIndex = 3, totalIterations = 7)
            frame.loopStack.addLast(lf)
            val popped = frame.loopStack.removeLast()
            popped.startIndex shouldBe 3
            popped.totalIterations shouldBe 7
        }

        "stack is empty after popping the only element" {
            val frame = freshFrame()
            frame.loopStack.addLast(loopFrame(0, 1))
            frame.loopStack.removeLast()
            frame.loopStack.shouldBeEmpty()
        }

        "stack size decreases by 1 after each pop" {
            val frame = freshFrame()
            frame.loopStack.addLast(loopFrame(0, 2))
            frame.loopStack.addLast(loopFrame(1, 3))
            frame.loopStack shouldHaveSize 2
            frame.loopStack.removeLast()
            frame.loopStack shouldHaveSize 1
            frame.loopStack.removeLast()
            frame.loopStack.shouldBeEmpty()
        }
    }

    // -----------------------------------------------------------------------
    // 5. Nested loops — LIFO order (Req 2.4)
    // -----------------------------------------------------------------------

    "ScriptFrame loopStack — nested loops LIFO order" - {

        "last pushed LoopFrame is on top (innermost loop)" {
            // Req 2.4: ScriptFrame SHALL store the full loop stack, allowing
            // correct resumption of nested repeat constructs (LIFO order)
            val frame = freshFrame()
            val outer = loopFrame(startIndex = 0, totalIterations = 5)
            val inner = loopFrame(startIndex = 3, totalIterations = 10)
            frame.loopStack.addLast(outer)
            frame.loopStack.addLast(inner)
            frame.loopStack.last().startIndex shouldBe 3   // inner is on top
        }

        "popping reveals the outer loop frame" {
            // Req 2.4: after inner loop completes, outer loop is correctly resumed
            val frame = freshFrame()
            val outer = loopFrame(startIndex = 0, totalIterations = 5)
            val inner = loopFrame(startIndex = 3, totalIterations = 10)
            frame.loopStack.addLast(outer)
            frame.loopStack.addLast(inner)
            frame.loopStack.removeLast()  // inner loop done
            frame.loopStack.last().startIndex shouldBe 0   // outer loop is now top
        }

        "three nested loops are returned in innermost-first order" {
            // Req 2.4: three-level nesting pops in reverse push order
            val frame = freshFrame()
            val lvl1 = loopFrame(startIndex = 0, totalIterations = 2)
            val lvl2 = loopFrame(startIndex = 5, totalIterations = 3)
            val lvl3 = loopFrame(startIndex = 9, totalIterations = 4)
            frame.loopStack.addLast(lvl1)
            frame.loopStack.addLast(lvl2)
            frame.loopStack.addLast(lvl3)

            frame.loopStack.removeLast().startIndex shouldBe 9  // innermost first
            frame.loopStack.removeLast().startIndex shouldBe 5
            frame.loopStack.removeLast().startIndex shouldBe 0  // outermost last
            frame.loopStack.shouldBeEmpty()
        }

        "currentIteration of inner loop is independent from outer loop" {
            // Req 2.4: each LoopFrame tracks its own iteration independently
            val frame = freshFrame()
            val outer = loopFrame(startIndex = 0, totalIterations = 3, currentIteration = 1)
            val inner = loopFrame(startIndex = 2, totalIterations = 5, currentIteration = 3)
            frame.loopStack.addLast(outer)
            frame.loopStack.addLast(inner)

            frame.loopStack.last().currentIteration shouldBe 3         // inner
            frame.loopStack.first().currentIteration shouldBe 1        // outer
        }

        "mutating currentIteration on LoopFrame is reflected in stack" {
            // Req 2.4: LoopFrame.currentIteration is mutable (var), changes persist in stack
            val frame = freshFrame()
            val lf = loopFrame(startIndex = 0, totalIterations = 4, currentIteration = 0)
            frame.loopStack.addLast(lf)
            frame.loopStack.last().currentIteration++
            frame.loopStack.last().currentIteration shouldBe 1
        }
    }
})
