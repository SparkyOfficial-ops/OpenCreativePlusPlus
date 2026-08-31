// Feature: gameready-enhancements, Property 1: Отмена события только в синхронной фазе
package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.CancellableEventReference
import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.NoOpEventReference
import com.opencreativeplus.core.nodes.event.CancelEventAction
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bukkit.event.Cancellable
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Property-based tests for [CancellableEventReference], [NoOpEventReference]
 * and [CancelEventAction].
 *
 * **Property 1: Отмена события только в синхронной фазе** — for any Cancellable event:
 * if cancellation is requested during the sync phase (before the first WaitAction),
 * the event must be cancelled; if requested in the async phase, the event must
 * remain uncancelled and the call is ignored.
 *
 * **Validates: Requirements 1.4, 1.5, 1.6**
 */
class EventReferenceTest : FreeSpec({

    /** Minimal [Cancellable] fake that records the cancellation flag. */
    class FakeCancellableEvent : Cancellable {
        private var cancelled = false
        override fun isCancelled(): Boolean = cancelled
        override fun setCancelled(cancel: Boolean) {
            cancelled = cancel
        }
    }

    // Suppress the expected async-phase warning logs during property runs
    val silentLogger = Logger.getLogger("EventReferenceTest").apply { level = Level.OFF }

    "Property 1: cancelEvent() cancels the event only in the sync phase" {
        // (engineFlag, lambdaFlag) — async phase is active when either signal is true
        checkAll(Arb.boolean(), Arb.boolean()) { engineAsyncFlag, lambdaAsyncFlag ->
            val event = FakeCancellableEvent()
            val ref = CancellableEventReference(
                event = event,
                isAsyncPhase = { lambdaAsyncFlag },
                logger = silentLogger
            )
            ref.asyncPhaseStarted = engineAsyncFlag

            ref.cancelEvent()

            val asyncPhase = engineAsyncFlag || lambdaAsyncFlag
            event.isCancelled shouldBe !asyncPhase
            ref.isCancelled shouldBe !asyncPhase
        }
    }

    "Property 1: repeated sync-phase cancellation is idempotent" {
        checkAll(Arb.int(1..10)) { calls ->
            val event = FakeCancellableEvent()
            val ref = CancellableEventReference(event, logger = silentLogger)

            repeat(calls) { ref.cancelEvent() }

            event.isCancelled shouldBe true
            ref.isCancelled shouldBe true
        }
    }

    "Property 1: once the async phase starts, cancellation is never applied" {
        checkAll(Arb.int(1..10)) { calls ->
            val event = FakeCancellableEvent()
            val ref = CancellableEventReference(event, logger = silentLogger)
            ref.asyncPhaseStarted = true

            repeat(calls) { ref.cancelEvent() }

            event.isCancelled shouldBe false
            ref.isCancelled shouldBe false
        }
    }

    "NoOpEventReference is a no-op for non-Cancellable events (Req 1.6)" {
        NoOpEventReference.cancelEvent()
        NoOpEventReference.isCancelled shouldBe false
    }

    "CancelEventAction delegates to context.eventReference (Req 1.4)" {
        checkAll(Arb.boolean()) { async ->
            val event = FakeCancellableEvent()
            val ref = CancellableEventReference(event, logger = silentLogger).apply {
                asyncPhaseStarted = async
            }
            val ctx = mockk<ExecutionContext>(relaxed = true)
            every { ctx.eventReference } returns ref

            runBlocking { CancelEventAction().execute(ctx) }

            event.isCancelled shouldBe !async
            ref.isCancelled shouldBe !async
        }
    }

    "CancelEventAction exposes the cancel_event nodeId (Req 1.4)" {
        CancelEventAction().nodeId shouldBe "cancel_event"
    }
})
