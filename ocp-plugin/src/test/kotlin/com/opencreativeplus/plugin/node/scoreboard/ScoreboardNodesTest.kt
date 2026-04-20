package com.opencreativeplus.plugin.node.scoreboard

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Score
import org.bukkit.scoreboard.Scoreboard
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Fake helpers (same pattern as EntityNodesTest)
// ---------------------------------------------------------------------------

private class FakeVariableScope : VariableScope {
    private val map = mutableMapOf<String, Any>()
    override fun get(name: String) = map[name]
    override fun set(name: String, value: Any) { map[name] = value }
    override fun has(name: String) = map.containsKey(name)
    override fun clear() = map.clear()
}

private class TrackingSyncContext(override val player: Player? = null) : ExecutionContext {
    override val plotId: UUID = UUID.randomUUID()
    override val eventData: Map<String, Any> = emptyMap()
    override val localScope: VariableScope = FakeVariableScope()
    override val plotScope: VariableScope = FakeVariableScope()
    override val savedScope: VariableScope = FakeVariableScope()
    override val operationCount: AtomicInteger = AtomicInteger(0)
    override val callStackSize: AtomicInteger = AtomicInteger(0)

    var syncContextCalled = false

    override suspend fun <T> syncContext(block: () -> T): T {
        syncContextCalled = true
        return block()
    }
}

// ---------------------------------------------------------------------------
// ShowScoreboardNode tests (s: 12.6)
// ---------------------------------------------------------------------------

class ShowScoreboardNodeTest {

    @Test
    fun `nodeId is show_scoreboard and displayName is Show Scoreboard`() {
        val node = ShowScoreboardNode(mapOf("player" to "p", "name" to "sb"))
        assertEquals("show_scoreboard", node.nodeId)
        assertEquals("Show Scoreboard", node.displayName)
    }

    @Test
    fun `execute calls syncContext to apply scoreboard on main thread`() = runTest {
        val player = mockk<Player>(relaxed = true)
        val scoreboard = mockk<Scoreboard>(relaxed = true)

        val ctx = TrackingSyncContext()
        ctx.localScope.set("p", player)
        ctx.plotScope.set("scoreboard_sb", scoreboard)

        val node = ShowScoreboardNode(mapOf("player" to "p", "name" to "sb"))
        node.execute(ctx)

        assertTrue(ctx.syncContextCalled, "syncContext must be called to apply scoreboard on main thread (s:12.6)")
        verify { player.scoreboard = scoreboard }
    }

    @Test
    fun `execute does nothing when player variable is missing`() = runTest {
        val scoreboard = mockk<Scoreboard>(relaxed = true)

        val ctx = TrackingSyncContext()
        ctx.plotScope.set("scoreboard_sb", scoreboard)
        // no player in localScope

        val node = ShowScoreboardNode(mapOf("player" to "p", "name" to "sb"))
        node.execute(ctx) // should not throw

        assertFalse(ctx.syncContextCalled)
    }

    @Test
    fun `execute does nothing when scoreboard is not in plotScope`() = runTest {
        val player = mockk<Player>(relaxed = true)

        val ctx = TrackingSyncContext()
        ctx.localScope.set("p", player)
        // no scoreboard in plotScope

        val node = ShowScoreboardNode(mapOf("player" to "p", "name" to "sb"))
        node.execute(ctx) // should not throw

        assertFalse(ctx.syncContextCalled)
    }
}

// ---------------------------------------------------------------------------
// HideScoreboardNode tests
// ---------------------------------------------------------------------------

class HideScoreboardNodeTest {

    @Test
    fun `nodeId is hide_scoreboard and displayName is Hide Scoreboard`() {
        val node = HideScoreboardNode(mapOf("player" to "p"))
        assertEquals("hide_scoreboard", node.nodeId)
        assertEquals("Hide Scoreboard", node.displayName)
    }
}

// ---------------------------------------------------------------------------
// SetScoreboardLineNode tests
// ---------------------------------------------------------------------------

class SetScoreboardLineNodeTest {

    @Test
    fun `nodeId is set_scoreboard_line and displayName is Set Scoreboard Line`() {
        val node = SetScoreboardLineNode(mapOf("name" to "sb", "line" to 1, "text" to "hello"))
        assertEquals("set_scoreboard_line", node.nodeId)
        assertEquals("Set Scoreboard Line", node.displayName)
    }

    @Test
    fun `execute calls syncContext to set score on main thread`() = runTest {
        val score = mockk<Score>(relaxed = true)
        val objective = mockk<Objective>(relaxed = true)
        every { objective.getScore(any<String>()) } returns score

        val ctx = TrackingSyncContext()
        ctx.plotScope.set("scoreboard_obj_scoreboard", objective)

        val node = SetScoreboardLineNode(mapOf("name" to "scoreboard", "line" to 2, "text" to "hello"))
        node.execute(ctx)

        assertTrue(ctx.syncContextCalled, "syncContext must be called for SetScoreboardLine")
        verify { score.score = 2 }
    }

    @Test
    fun `execute does nothing when objective is missing`() = runTest {
        val ctx = TrackingSyncContext()
        // no objective in plotScope

        val node = SetScoreboardLineNode(mapOf("name" to "scoreboard", "line" to 1, "text" to "hello"))
        node.execute(ctx) // should not throw

        assertFalse(ctx.syncContextCalled)
    }
}
