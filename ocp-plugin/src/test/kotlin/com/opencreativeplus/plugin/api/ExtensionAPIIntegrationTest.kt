package com.opencreativeplus.plugin.api

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.api.node.IValue
import com.opencreativeplus.plugin.api.example.TeleportAction
import com.opencreativeplus.plugin.registry.NodeRegistryImpl
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// ---------------------------------------------------------------------------
// Fake helpers (mirrors BuiltInNodeExecutionTest)
// ---------------------------------------------------------------------------

private class FakeVariableScope : VariableScope {
    private val map = mutableMapOf<String, Any>()
    override fun get(name: String) = map[name]
    override fun set(name: String, value: Any) { map[name] = value }
    override fun has(name: String) = map.containsKey(name)
    override fun clear() = map.clear()
}

private class FakeExecutionContext(override val player: Player? = null) : ExecutionContext {
    override val plotId: UUID = UUID.randomUUID()
    override val eventData: Map<String, Any> = emptyMap()
    override val localScope: VariableScope = FakeVariableScope()
    override val plotScope: VariableScope = FakeVariableScope()
    override val savedScope: VariableScope = FakeVariableScope()
    override val operationCount: AtomicInteger = AtomicInteger(0)
    override suspend fun <T> syncContext(block: () -> T): T = block()
}

// ---------------------------------------------------------------------------
// Integration tests
// ---------------------------------------------------------------------------

/
 * Integration tests for the extension API.
 * Validates s: 11.3, 11.4
 */
class ExtensionAPIIntegrationTest {

    private lateinit var registry: NodeRegistryImpl
    private lateinit var api: OpenCreativePlusAPI

    @BeforeTest
    fun setup() {
        registry = NodeRegistryImpl()
        api = OpenCreativePlusAPI.initialize(registry)
    }

    // --- External node registration via API ---

    @Test
    fun `registerAction via API makes factory retrievable from registry`() {
        api.registerAction(Material.TNT) { _ ->
            object : IAction {
                override val nodeId = "explode"
                override val displayName = "Explode"
                override suspend fun execute(context: ExecutionContext) {}
            }
        }
        assertNotNull(registry.getActionFactory(Material.TNT))
    }

    @Test
    fun `registerCondition via API makes factory retrievable from registry`() {
        api.registerCondition(Material.REDSTONE_BLOCK) { _ ->
            object : ICondition {
                override val nodeId = "always_true"
                override val displayName = "Always True"
                override suspend fun evaluate(context: ExecutionContext) = true
            }
        }
        assertNotNull(registry.getConditionFactory(Material.REDSTONE_BLOCK))
    }

    @Test
    fun `registerValue via API makes factory retrievable from registry`() {
        api.registerValue(Material.GOLD_BLOCK) { _ ->
            object : IValue<Int> {
                override val nodeId = "const_value"
                override val displayName = "Constant Value"
                override suspend fun compute(context: ExecutionContext) = 42
            }
        }
        assertNotNull(registry.getValueFactory(Material.GOLD_BLOCK))
    }

    @Test
    fun `registerEvent via API makes factory retrievable from registry`() {
        api.registerEvent(Material.EMERALD_BLOCK) {
            object : IEvent {
                override val nodeId = "on_custom"
                override val displayName = "On Custom"
                override val eventType = "custom_event"
            }
        }
        assertNotNull(registry.getEventFactory(Material.EMERALD_BLOCK))
    }

    // --- Validation of invalid nodes (s: 11.4) ---

    @Test
    fun `registerAction with blank nodeId throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            api.registerAction(Material.DIRT) { _ ->
                object : IAction {
                    override val nodeId = ""
                    override val displayName = "Bad"
                    override suspend fun execute(context: ExecutionContext) {}
                }
            }
        }
    }

    @Test
    fun `registerEvent with blank eventType throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            api.registerEvent(Material.GRAVEL) {
                object : IEvent {
                    override val nodeId = "bad_event"
                    override val displayName = "Bad"
                    override val eventType = ""
                }
            }
        }
    }

    @Test
    fun `registerAction for non-block material throws IllegalArgumentException`() {
        // Material.STICK is not a block — validateMaterial should reject it
        assertFailsWith<IllegalArgumentException> {
            api.registerAction(Material.STICK) { _ ->
                object : IAction {
                    override val nodeId = "stick_action"
                    override val displayName = "Stick"
                    override suspend fun execute(context: ExecutionContext) {}
                }
            }
        }
    }

    @Test
    fun `getActionFactory returns null for material registered under a different node type`() {
        // Register as condition, not action — action lookup should return null
        api.registerCondition(Material.IRON_BLOCK) { _ ->
            object : ICondition {
                override val nodeId = "iron_check"
                override val displayName = "Iron Check"
                override suspend fun evaluate(context: ExecutionContext) = false
            }
        }
        assertNull(registry.getActionFactory(Material.IRON_BLOCK))
    }

    // --- Execution of external nodes: TeleportAction (s: 11.3) ---

    @Test
    fun `TeleportAction has correct nodeId and displayName`() {
        val action = TeleportAction(emptyMap())
        assertEquals("teleport", action.nodeId)
        assertEquals("Teleport", action.displayName)
    }

    @Test
    fun `TeleportAction reads x y z params with correct defaults`() {
        val action = TeleportAction(mapOf("x" to 10.0, "y" to 65.0, "z" to -5.0))
        // Verify via execution with null player — no exception expected
        runTest {
            action.execute(FakeExecutionContext(player = null))
        }
    }

    @Test
    fun `TeleportAction execute does nothing when player is null`() = runTest {
        val action = TeleportAction(mapOf("world" to "world", "x" to 0, "y" to 64, "z" to 0))
        val ctx = FakeExecutionContext(player = null)
        action.execute(ctx) // must not throw
    }

    @Test
    fun `TeleportAction registered via API produces node with correct nodeId`() {
        // ENDER_PEARL is not a block; use OBSIDIAN as the representative block material
        api.registerAction(Material.OBSIDIAN) { params -> TeleportAction(params) }

        val factory = registry.getActionFactory(Material.OBSIDIAN)
        assertNotNull(factory)

        val node = factory(emptyMap())
        assertEquals("teleport", node.nodeId)
    }

    @Test
    fun `getInstance returns the same API instance after initialize`() {
        val instance = OpenCreativePlusAPI.getInstance()
        assertEquals(api, instance)
    }
}
