package com.opencreativeplus.plugin.node

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.plugin.node.action.SendMessageAction
import com.opencreativeplus.plugin.node.action.WaitAction
import com.opencreativeplus.plugin.node.event.OnJoinEvent
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

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

class WaitActionTest {

    @Test
    fun `nodeId is wait and displayName is Wait`() {
        val action = WaitAction(emptyMap())
        assertEquals("wait", action.nodeId)
        assertEquals("Wait", action.displayName)
    }

    @Test
    fun `execute with default duration completes without real delay`() = runTest {
        // No duration param → defaults to 20 ticks (1000 ms virtual time)
        val action = WaitAction(emptyMap())
        val ctx = FakeExecutionContext()
        action.execute(ctx) // runTest advances virtual time automatically
    }

    @Test
    fun `execute with numeric duration param completes`() = runTest {
        val action = WaitAction(mapOf("duration" to 10))
        val ctx = FakeExecutionContext()
        action.execute(ctx)
    }

    @Test
    fun `execute with string duration param completes`() = runTest {
        val action = WaitAction(mapOf("duration" to "5"))
        val ctx = FakeExecutionContext()
        action.execute(ctx)
    }

    @Test
    fun `execute with invalid string duration falls back to 20 ticks`() = runTest {
        val action = WaitAction(mapOf("duration" to "notanumber"))
        val ctx = FakeExecutionContext()
        action.execute(ctx) // should not throw
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
