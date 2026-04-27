// Feature: ocp-gameplay-systems, Requirement 12.5: Chat suppressed when hologram present
package com.opencreativeplus.plugin.visualizer

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.core.execution.CoroutineConfiguration
import com.opencreativeplus.core.execution.ExecutionEngine
import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.core.watchdog.Watchdog
import io.kotest.core.spec.style.StringSpec
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Validates: Requirements 12.5
 *
 * When an errorReporter is configured, chat sendMessage must NOT be called (hologram handles it).
 * When no errorReporter is configured, chat sendMessage IS called as fallback.
 */
class HologramErrorReporterTest : StringSpec({

    fun makeEngine(
        errorReporter: ((String, String) -> Unit)?
    ): Triple<ExecutionEngine, VariableManager, CoroutineConfiguration> {
        val tpsMonitor = mockk<com.opencreativeplus.core.watchdog.TPSMonitor>()
        every { tpsMonitor.getCurrentTPS() } returns 20.0
        val watchdog = Watchdog(tpsMonitor)

        val db = mockk<com.mongodb.kotlin.client.coroutine.MongoDatabase>(relaxed = true)
        val variableManager = VariableManager(db)

        val coroutineConfig = CoroutineConfiguration(syncRunner = { it() })

        val engine = ExecutionEngine(
            watchdog = watchdog,
            variableManager = variableManager,
            coroutineConfig = coroutineConfig,
            errorReporter = errorReporter
        )
        return Triple(engine, variableManager, coroutineConfig)
    }

    fun throwingScript(): CompiledScript {
        val event = mockk<IEvent>()
        every { event.nodeId } returns "test_event"
        every { event.displayName } returns "Test Event"
        every { event.eventType } returns "test"

        val action = object : IAction {
            override val nodeId = "throwing"
            override val displayName = "Throwing"
            override suspend fun execute(context: ExecutionContext) {
                throw RuntimeException("test error")
            }
        }
        return CompiledScript(event = event, actions = listOf(action), sourceLocation = "test@0,0,0")
    }

    "chat sendMessage is NOT called when errorReporter is configured" {
        val reporterCalls = mutableListOf<Pair<String, String>>()
        val errorReporter: (String, String) -> Unit = { loc, msg -> reporterCalls.add(loc to msg) }

        val (engine, _, coroutineConfig) = makeEngine(errorReporter)
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns UUID.randomUUID()

        runBlocking {
            engine.executeScript(throwingScript(), UUID.randomUUID(), player, emptyMap())
            kotlinx.coroutines.delay(200)
        }

        verify(exactly = 0) { player.sendMessage(any<String>()) }
        assert(reporterCalls.size == 1) { "errorReporter should be invoked once, was: ${reporterCalls.size}" }

        coroutineConfig.close()
    }

    "chat sendMessage IS called when no errorReporter is configured" {
        val (engine, _, coroutineConfig) = makeEngine(errorReporter = null)
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns UUID.randomUUID()

        runBlocking {
            engine.executeScript(throwingScript(), UUID.randomUUID(), player, emptyMap())
            kotlinx.coroutines.delay(200)
        }

        verify(exactly = 1) { player.sendMessage(any<String>()) }

        coroutineConfig.close()
    }
})
