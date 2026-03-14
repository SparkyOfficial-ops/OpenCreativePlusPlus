package com.opencreativeplus.plugin.registry

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.api.node.IValue
import io.mockk.mockk
import org.bukkit.Material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NodeRegistryImplTest {

    private val registry = NodeRegistryImpl()

    // --- Action registration ---

    @Test
    fun `registerAction stores factory and getActionFactory returns it`() {
        registry.registerAction(Material.PAPER) { params ->
            object : IAction {
                override val nodeId = "send_message"
                override val displayName = "Send Message"
                override suspend fun execute(context: ExecutionContext) {}
            }
        }
        assertNotNull(registry.getActionFactory(Material.PAPER))
    }

    @Test
    fun `getActionFactory returns null for unregistered material`() {
        assertNull(registry.getActionFactory(Material.STONE))
    }

    @Test
    fun `registerAction throws when nodeId is blank`() {
        assertFailsWith<IllegalArgumentException> {
            registry.registerAction(Material.DIRT) { _ ->
                object : IAction {
                    override val nodeId = ""
                    override val displayName = "Bad"
                    override suspend fun execute(context: ExecutionContext) {}
                }
            }
        }
    }

    // --- Condition registration ---

    @Test
    fun `registerCondition stores factory and getConditionFactory returns it`() {
        registry.registerCondition(Material.REDSTONE_BLOCK) { _ ->
            object : ICondition {
                override val nodeId = "always_true"
                override val displayName = "Always True"
                override suspend fun evaluate(context: ExecutionContext) = true
            }
        }
        assertNotNull(registry.getConditionFactory(Material.REDSTONE_BLOCK))
    }

    @Test
    fun `getConditionFactory returns null for unregistered material`() {
        assertNull(registry.getConditionFactory(Material.GRASS_BLOCK))
    }

    // --- Value registration ---

    @Test
    fun `registerValue stores factory and getValueFactory returns it`() {
        registry.registerValue(Material.GOLD_BLOCK) { _ ->
            object : IValue<Int> {
                override val nodeId = "const_42"
                override val displayName = "Constant 42"
                override suspend fun compute(context: ExecutionContext) = 42
            }
        }
        assertNotNull(registry.getValueFactory(Material.GOLD_BLOCK))
    }

    @Test
    fun `getValueFactory returns null for unregistered material`() {
        assertNull(registry.getValueFactory(Material.IRON_BLOCK))
    }

    // --- Event registration ---

    @Test
    fun `registerEvent stores factory and getEventFactory returns it`() {
        registry.registerEvent(Material.DIAMOND_BLOCK) {
            object : IEvent {
                override val nodeId = "on_join"
                override val displayName = "On Join"
                override val eventType = "player_join"
            }
        }
        assertNotNull(registry.getEventFactory(Material.DIAMOND_BLOCK))
    }

    @Test
    fun `getEventFactory returns null for unregistered material`() {
        assertNull(registry.getEventFactory(Material.EMERALD_BLOCK))
    }

    @Test
    fun `registerEvent throws when eventType is blank`() {
        assertFailsWith<IllegalArgumentException> {
            registry.registerEvent(Material.OBSIDIAN) {
                object : IEvent {
                    override val nodeId = "bad_event"
                    override val displayName = "Bad"
                    override val eventType = ""
                }
            }
        }
    }

    // --- getRegisteredActionMaterials ---

    @Test
    fun `getRegisteredActionMaterials returns all registered action materials`() {
        registry.registerAction(Material.PAPER) { _ ->
            object : IAction {
                override val nodeId = "send_message"
                override val displayName = "Send Message"
                override suspend fun execute(context: ExecutionContext) {}
            }
        }
        registry.registerAction(Material.CLOCK) { _ ->
            object : IAction {
                override val nodeId = "wait"
                override val displayName = "Wait"
                override suspend fun execute(context: ExecutionContext) {}
            }
        }
        val materials = registry.getRegisteredActionMaterials()
        assertEquals(setOf(Material.PAPER, Material.CLOCK), materials)
    }

    // --- BuiltInNodeRegistry integration ---

    @Test
    fun `BuiltInNodeRegistry registers expected nodes`() {
        val reg = NodeRegistryImpl()
        BuiltInNodeRegistry.register(reg)

        assertNotNull(reg.getEventFactory(Material.DIAMOND_BLOCK))
        assertNotNull(reg.getActionFactory(Material.PAPER))
        assertNotNull(reg.getActionFactory(Material.CLOCK))
    }

    @Test
    fun `OnJoinEvent factory produces correct node`() {
        val reg = NodeRegistryImpl()
        BuiltInNodeRegistry.register(reg)

        val event = reg.getEventFactory(Material.DIAMOND_BLOCK)!!.invoke()
        assertEquals("on_join", event.nodeId)
        assertEquals("player_join", event.eventType)
    }
}
