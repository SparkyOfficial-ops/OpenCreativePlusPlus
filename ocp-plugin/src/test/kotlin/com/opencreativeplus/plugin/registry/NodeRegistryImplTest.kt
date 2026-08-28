package com.opencreativeplus.plugin.registry

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.api.node.IValue
import org.bukkit.Material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
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
            registry.registerAction(Material.DIRT, "") { _ ->
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
    fun `registerEvent does not validate eventType at registration time`() {
        // registerEvent stores the factory without invoking it — eventType is only
        // available inside the factory lambda, so no validation occurs at registration.
        var threw = false
        try {
            registry.registerEvent(Material.OBSIDIAN) {
                object : IEvent {
                    override val nodeId = "bad_event"
                    override val displayName = "Bad"
                    override val eventType = ""
                }
            }
        } catch (e: Exception) {
            threw = true
        }
        assertFalse(threw)
        assertNotNull(registry.getEventFactory(Material.OBSIDIAN))
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
        // Material.CLOCK is registered via registerPluginActions() (requires Plugin), not register()
    }

    @Test
    fun `OnJoinEvent factory produces correct node`() {
        val reg = NodeRegistryImpl()
        BuiltInNodeRegistry.register(reg)

        val event = reg.getEventFactory(Material.DIAMOND_BLOCK)!!.invoke()
        assertEquals("on_join", event.nodeId)
        assertEquals("player_join", event.eventType)
    }

    // --- Bug 1 fix check: factory with required params does not throw on registration ---

    @Test
    fun `registerAction with explicit nodeId and required-param factory does not throw`() {
        var threw = false
        try {
            registry.registerAction(Material.STONE, "my_action") { params ->
                object : IAction {
                    private val target: String = params["target"] as? String
                        ?: error("target param required")
                    override val nodeId = "my_action"
                    override val displayName = "My Action"
                    override suspend fun execute(context: ExecutionContext) {}
                }
            }
        } catch (e: Exception) {
            threw = true
        }
        assertFalse(threw)
        assertNotNull(registry.getActionFactory(Material.STONE))
        assertEquals("my_action", registry.getActionNodeId(Material.STONE))
    }

    @Test
    fun `registerCondition with explicit nodeId and required-param factory does not throw`() {
        var threw = false
        try {
            registry.registerCondition(Material.STONE, "my_condition") { params ->
                object : ICondition {
                    private val threshold: Int = params["threshold"] as? Int
                        ?: error("threshold param required")
                    override val nodeId = "my_condition"
                    override val displayName = "My Condition"
                    override suspend fun evaluate(context: ExecutionContext) = threshold > 0
                }
            }
        } catch (e: Exception) {
            threw = true
        }
        assertFalse(threw)
        assertNotNull(registry.getConditionFactory(Material.STONE))
        assertEquals("my_condition", registry.getConditionNodeId(Material.STONE))
    }

    @Test
    fun `registerValue with explicit nodeId and required-param factory does not throw`() {
        var threw = false
        try {
            registry.registerValue(Material.STONE, "my_value") { params ->
                object : IValue<Int> {
                    private val value: Int = params["value"] as? Int
                        ?: error("value param required")
                    override val nodeId = "my_value"
                    override val displayName = "My Value"
                    override suspend fun compute(context: ExecutionContext) = value
                }
            }
        } catch (e: Exception) {
            threw = true
        }
        assertFalse(threw)
        assertNotNull(registry.getValueFactory(Material.STONE))
        assertEquals("my_value", registry.getValueNodeId(Material.STONE))
    }

    // --- Bug 1 preservation: nodes without required params still register and work identically ---

    @Test
    fun `registerAction without required params registers factory and nodeId correctly`() {
        registry.registerAction(Material.SAND, "simple_action") { _ ->
            object : IAction {
                override val nodeId = "simple_action"
                override val displayName = "Simple Action"
                override suspend fun execute(context: ExecutionContext) {}
            }
        }
        assertNotNull(registry.getActionFactory(Material.SAND))
        assertEquals("simple_action", registry.getActionNodeId(Material.SAND))
    }

    @Test
    fun `registerCondition without required params registers factory and nodeId correctly`() {
        registry.registerCondition(Material.SAND, "simple_condition") { _ ->
            object : ICondition {
                override val nodeId = "simple_condition"
                override val displayName = "Simple Condition"
                override suspend fun evaluate(context: ExecutionContext) = true
            }
        }
        assertNotNull(registry.getConditionFactory(Material.SAND))
        assertEquals("simple_condition", registry.getConditionNodeId(Material.SAND))
    }

    @Test
    fun `registerValue without required params registers factory and nodeId correctly`() {
        registry.registerValue(Material.SAND, "simple_value") { _ ->
            object : IValue<String> {
                override val nodeId = "simple_value"
                override val displayName = "Simple Value"
                override suspend fun compute(context: ExecutionContext) = "hello"
            }
        }
        assertNotNull(registry.getValueFactory(Material.SAND))
        assertEquals("simple_value", registry.getValueNodeId(Material.SAND))
    }

    @Test
    fun `getActionNodeId returns null for node registered without explicit nodeId`() {
        registry.registerAction(Material.GRAVEL) { _ ->
            object : IAction {
                override val nodeId = "legacy_action"
                override val displayName = "Legacy"
                override suspend fun execute(context: ExecutionContext) {}
            }
        }
        // Factory is registered but nodeId metadata is absent (legacy overload)
        assertNotNull(registry.getActionFactory(Material.GRAVEL))
        assertNull(registry.getActionNodeId(Material.GRAVEL))
    }
}
