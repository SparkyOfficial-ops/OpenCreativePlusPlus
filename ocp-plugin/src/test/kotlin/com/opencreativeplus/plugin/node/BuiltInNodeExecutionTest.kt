package com.opencreativeplus.plugin.node

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.plugin.node.action.SendMessageAction
import com.opencreativeplus.plugin.node.action.WaitAction
import com.opencreativeplus.plugin.node.event.OnJoinEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// ---------------------------------------------------------------------------
// Fake helpers
// ---------------------------------------------------------------------------

class FakeVariableScope : VariableScope {
    private val map = mutableMapOf<String, Any>()
    override fun get(name: String) = map[name]
    override fun set(name: String, value: Any) { map[name] = value }
    override fun has(name: String) = map.containsKey(name)
    override fun clear() = map.clear()
}

class FakeExecutionContext(override val player: Player? = null) : ExecutionContext {
    override val plotId: UUID = UUID.randomUUID()
    override val eventData: Map<String, Any> = emptyMap()
    override val localScope: VariableScope = FakeVariableScope()
    override val plotScope: VariableScope = FakeVariableScope()
    override val savedScope: VariableScope = FakeVariableScope()
    override val operationCount: AtomicInteger = AtomicInteger(0)
    override val callStackSize: AtomicInteger = AtomicInteger(0)
    override suspend fun <T> syncContext(block: () -> T): T = block()
}

// ---------------------------------------------------------------------------
// WaitAction tests
// ---------------------------------------------------------------------------

/** Creates a mock Plugin whose BukkitScheduler immediately runs any scheduled task. */
private fun mockPluginWithImmediateScheduler(): Plugin {
    val task = mockk<BukkitTask>(relaxed = true)
    val scheduler = mockk<BukkitScheduler>()
    val server = mockk<Server>()
    val plugin = mockk<Plugin>()

    every { plugin.server } returns server
    every { server.scheduler } returns scheduler

    // runTask(Plugin, Runnable) — execute the Runnable immediately
    every { scheduler.runTask(plugin, any<Runnable>()) } answers {
        (secondArg<Runnable>()).run()
        task
    }

    // runTaskLater(Plugin, Runnable, Long) — execute the Runnable immediately (ignoring delay in tests)
    every { scheduler.runTaskLater(plugin, any<Runnable>(), any<Long>()) } answers {
        (secondArg<Runnable>()).run()
        task
    }

    return plugin
}

class WaitActionTest {

    @Test
    fun `nodeId is wait and displayName is Wait`() {
        val action = WaitAction(emptyMap(), mockPluginWithImmediateScheduler())
        assertEquals("wait", action.nodeId)
        assertEquals("Wait", action.displayName)
    }

    @Test
    fun `execute with default duration completes without real delay`() = runTest {
        val action = WaitAction(emptyMap(), mockPluginWithImmediateScheduler())
        val ctx = FakeExecutionContext()
        action.execute(ctx)
    }

    @Test
    fun `execute with numeric duration param completes`() = runTest {
        val action = WaitAction(mapOf("duration" to 10), mockPluginWithImmediateScheduler())
        val ctx = FakeExecutionContext()
        action.execute(ctx)
    }

    @Test
    fun `execute with string duration param completes`() = runTest {
        val action = WaitAction(mapOf("duration" to "5"), mockPluginWithImmediateScheduler())
        val ctx = FakeExecutionContext()
        action.execute(ctx)
    }

    @Test
    fun `execute with invalid string duration falls back to 20 ticks`() = runTest {
        val action = WaitAction(mapOf("duration" to "notanumber"), mockPluginWithImmediateScheduler())
        val ctx = FakeExecutionContext()
        action.execute(ctx) // should not throw
    }

    @Test
    fun `delayTicks with zero uses runTask not runTaskLater`() = runTest {
        val task = mockk<BukkitTask>(relaxed = true)
        val scheduler = mockk<BukkitScheduler>()
        val server = mockk<Server>()
        val plugin = mockk<Plugin>()
        every { plugin.server } returns server
        every { server.scheduler } returns scheduler
        every { scheduler.runTask(plugin, any<Runnable>()) } answers {
            (secondArg<Runnable>()).run(); task
        }

        val action = WaitAction(emptyMap(), plugin)
        action.delayTicks(0)

        verify(exactly = 1) { scheduler.runTask(plugin, any<Runnable>()) }
        verify(exactly = 0) { scheduler.runTaskLater(plugin, any<Runnable>(), any<Long>()) }
    }

    @Test
    fun `delayTicks with positive ticks uses runTaskLater`() = runTest {
        val task = mockk<BukkitTask>(relaxed = true)
        val scheduler = mockk<BukkitScheduler>()
        val server = mockk<Server>()
        val plugin = mockk<Plugin>()
        every { plugin.server } returns server
        every { server.scheduler } returns scheduler
        every { scheduler.runTaskLater(plugin, any<Runnable>(), 5L) } answers {
            (secondArg<Runnable>()).run(); task
        }

        val action = WaitAction(emptyMap(), plugin)
        action.delayTicks(5)

        verify(exactly = 1) { scheduler.runTaskLater(plugin, any<Runnable>(), 5L) }
        verify(exactly = 0) { scheduler.runTask(plugin, any<Runnable>()) }
    }

    @Test
    fun `delayTicks with negative ticks throws IllegalArgumentException`() {
        val action = WaitAction(emptyMap(), mockPluginWithImmediateScheduler())
        assertFailsWith<IllegalArgumentException> {
            kotlinx.coroutines.runBlocking { action.delayTicks(-1) }
        }
    }
}

// ---------------------------------------------------------------------------
// SendMessageAction tests
// ---------------------------------------------------------------------------

class SendMessageActionTest {

    @Test
    fun `nodeId is send_message and displayName is Send Message`() {
        val action = SendMessageAction(emptyMap())
        assertEquals("send_message", action.nodeId)
        assertEquals("Send Message", action.displayName)
    }

    @Test
    fun `execute sends message to player`() = runTest {
        val player = mockk<Player>(relaxed = true)
        val ctx = FakeExecutionContext(player = player)
        val action = SendMessageAction(mapOf("message" to "Hello, world!"))

        action.execute(ctx)

        verify { player.sendMessage("Hello, world!") }
    }

    @Test
    fun `execute resolves variable references from local scope`() = runTest {
        val player = mockk<Player>(relaxed = true)
        val ctx = FakeExecutionContext(player = player)
        ctx.localScope.set("name", "Steve")
        val action = SendMessageAction(mapOf("message" to "Hello, \$name!"))

        action.execute(ctx)

        verify { player.sendMessage("Hello, Steve!") }
    }

    @Test
    fun `execute does nothing when player is null`() = runTest {
        val ctx = FakeExecutionContext(player = null)
        val action = SendMessageAction(mapOf("message" to "Hello"))
        action.execute(ctx) // should not throw
    }

    @Test
    fun `execute does nothing when message param is missing`() = runTest {
        val player = mockk<Player>(relaxed = true)
        val ctx = FakeExecutionContext(player = player)
        val action = SendMessageAction(emptyMap())

        action.execute(ctx)

        verify(exactly = 0) { player.sendMessage(any<String>()) }
    }
}

// ---------------------------------------------------------------------------
// OnJoinEvent tests
// ---------------------------------------------------------------------------

class OnJoinEventTest {

    @Test
    fun `nodeId is on_join`() {
        assertEquals("on_join", OnJoinEvent().nodeId)
    }

    @Test
    fun `eventType is player_join`() {
        assertEquals("player_join", OnJoinEvent().eventType)
    }

    @Test
    fun `displayName is On Player Join`() {
        assertEquals("On Player Join", OnJoinEvent().displayName)
    }
}
