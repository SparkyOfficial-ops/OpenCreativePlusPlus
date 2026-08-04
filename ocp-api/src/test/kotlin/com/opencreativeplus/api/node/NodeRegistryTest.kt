package com.opencreativeplus.api.node

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.adapters.LegacyActionAdapter
import com.opencreativeplus.api.node.adapters.LegacyConditionAdapter
import com.opencreativeplus.api.node.adapters.LegacyValueAdapter
import com.opencreativeplus.api.registry.NodeRegistry
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import org.bukkit.Material
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests for [NodeRegistry] contract: CommandNode registration, lookup,
 * legacy registerAction/Condition/Value, adapter fallback, and unregistered lookup.
 *
 * Tests use a minimal in-memory [TestNodeRegistry] that covers all NodeRegistry
 * methods, which lets this module stay independent of ocp-plugin.
 *
 * Validates: Requirements 8.7, 10.1, 10.4
 */
class NodeRegistryTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Minimal in-memory NodeRegistry for testing
    // -----------------------------------------------------------------------

    fun freshRegistry(): TestNodeRegistry = TestNodeRegistry()

    // -----------------------------------------------------------------------
    // Helpers — stub legacy node implementations
    // -----------------------------------------------------------------------

    fun stubAction(id: String): IAction = object : IAction {
        override val nodeId = id
        override val displayName = "Action $id"
        override suspend fun execute(context: ExecutionContext) {}
        override fun getParams(): Map<String, Any> = mapOf("key" to "value")
    }

    fun stubCondition(id: String): ICondition = object : ICondition {
        override val nodeId = id
        override val displayName = "Condition $id"
        override suspend fun evaluate(context: ExecutionContext): Boolean = true
        override fun getParams(): Map<String, Any> = mapOf("threshold" to 5)
    }

    fun stubValue(id: String): IValue<String> = object : IValue<String> {
        override val nodeId = id
        override val displayName = "Value $id"
        override suspend fun compute(context: ExecutionContext): String = "result"
        override fun getParams(): Map<String, Any> = mapOf("val" to "hello")
    }

    // -----------------------------------------------------------------------
    // 1. CommandNode registration and lookup (Req 8.7)
    // -----------------------------------------------------------------------

    "CommandNode registration and lookup" - {

        "registerCommandNode stores factory and getCommandNodeFactory returns it" {
            // Req 8.7: NodeRegistry SHALL register CommandNode-types by nodeId and return factory
            val registry = freshRegistry()
            registry.registerCommandNode("send_message", NodeType.ACTION) { params ->
                CommandNode(type = NodeType.ACTION, nodeId = "send_message", params = params)
            }
            registry.getCommandNodeFactory("send_message").shouldNotBeNull()
        }

        "getCommandNodeFactory returns null for unregistered nodeId" {
            // Req 8.7: lookup for unregistered nodeId returns null
            val registry = freshRegistry()
            registry.getCommandNodeFactory("unknown_node").shouldBeNull()
        }

        "factory creates CommandNode with correct type and nodeId" {
            // Req 8.7: factory produces instance with arbitrary params
            val registry = freshRegistry()
            registry.registerCommandNode("move_player", NodeType.ACTION) { params ->
                CommandNode(type = NodeType.ACTION, nodeId = "move_player", params = params)
            }
            val factory = registry.getCommandNodeFactory("move_player")!!
            val params = mapOf("direction" to "NORTH", "distance" to 5)
            val node = factory(params)
            node.type shouldBe NodeType.ACTION
            node.nodeId shouldBe "move_player"
            node.params shouldBe params
        }

        "factory creates CommandNode with empty params when called with emptyMap" {
            // Req 8.7: factory supports empty params
            val registry = freshRegistry()
            registry.registerCommandNode("noop_action", NodeType.ACTION) { params ->
                CommandNode(type = NodeType.ACTION, nodeId = "noop_action", params = params)
            }
            val node = registry.getCommandNodeFactory("noop_action")!!(emptyMap())
            node.params shouldBe emptyMap()
        }

        "registering CommandNode for CONDITION type stores correct NodeType" {
            // Req 8.7: NodeType is preserved in registered factory output
            val registry = freshRegistry()
            registry.registerCommandNode("is_daytime", NodeType.CONDITION) { params ->
                CommandNode(type = NodeType.CONDITION, nodeId = "is_daytime", params = params)
            }
            val node = registry.getCommandNodeFactory("is_daytime")!!(emptyMap())
            node.type shouldBe NodeType.CONDITION
        }

        "getRegisteredNodeIds contains nodeId after registration" {
            // Req 8.7: registry tracks all registered nodeIds
            val registry = freshRegistry()
            registry.registerCommandNode("heal_player", NodeType.ACTION) { params ->
                CommandNode(type = NodeType.ACTION, nodeId = "heal_player", params = params)
            }
            registry.getRegisteredNodeIds() shouldContain "heal_player"
        }

        "getRegisteredNodeIds does not contain unregistered nodeId" {
            // Req 8.7: only registered nodeIds appear in the set
            val registry = freshRegistry()
            registry.registerCommandNode("heal_player", NodeType.ACTION) { params ->
                CommandNode(type = NodeType.ACTION, nodeId = "heal_player", params = params)
            }
            registry.getRegisteredNodeIds() shouldNotContain "unknown_node"
        }

        "getRegisteredNodeIds is empty before any registration" {
            // Req 8.7: fresh registry has no registered nodeIds
            val registry = freshRegistry()
            registry.getRegisteredNodeIds() shouldBe emptySet()
        }

        "multiple CommandNode registrations are all retrievable" {
            // Req 8.7: multiple nodeIds can be registered independently
            val registry = freshRegistry()
            listOf("action_a", "action_b", "action_c").forEach { id ->
                registry.registerCommandNode(id, NodeType.ACTION) { params ->
                    CommandNode(type = NodeType.ACTION, nodeId = id, params = params)
                }
            }
            registry.getCommandNodeFactory("action_a").shouldNotBeNull()
            registry.getCommandNodeFactory("action_b").shouldNotBeNull()
            registry.getCommandNodeFactory("action_c").shouldNotBeNull()
        }
    }

    // -----------------------------------------------------------------------
    // 2. Legacy registerAction / registerCondition / registerValue (Req 10.4)
    // -----------------------------------------------------------------------

    "Legacy registration methods" - {

        "registerAction with nodeId stores factory, retrievable by material and by nodeId" {
            // Req 10.4: registerAction(id, factory) continues to work
            val registry = freshRegistry()
            registry.registerAction(Material.PAPER, "send_message") { _ -> stubAction("send_message") }

            registry.getActionFactory(Material.PAPER).shouldNotBeNull()
            registry.getActionFactoryById("send_message").shouldNotBeNull()
            registry.getActionNodeId(Material.PAPER) shouldBe "send_message"
        }

        "registerCondition with nodeId stores factory, retrievable by material and by nodeId" {
            // Req 10.4: registerCondition(id, factory) continues to work
            val registry = freshRegistry()
            registry.registerCondition(Material.REDSTONE_BLOCK, "is_night") { _ -> stubCondition("is_night") }

            registry.getConditionFactory(Material.REDSTONE_BLOCK).shouldNotBeNull()
            registry.getConditionFactoryById("is_night").shouldNotBeNull()
            registry.getConditionNodeId(Material.REDSTONE_BLOCK) shouldBe "is_night"
        }

        "registerValue with nodeId stores factory, retrievable by material and by nodeId" {
            // Req 10.4: registerValue(id, factory) continues to work
            val registry = freshRegistry()
            registry.registerValue(Material.GOLD_BLOCK, "player_health") { _ -> stubValue("player_health") }

            registry.getValueFactory(Material.GOLD_BLOCK).shouldNotBeNull()
            registry.getValueFactoryById("player_health").shouldNotBeNull()
            registry.getValueNodeId(Material.GOLD_BLOCK) shouldBe "player_health"
        }

        "legacy factory produces a working IAction instance" {
            // Req 10.4: factory callable with params produces valid node
            val registry = freshRegistry()
            registry.registerAction(Material.PAPER, "say_hello") { params ->
                object : IAction {
                    override val nodeId = "say_hello"
                    override val displayName = "Say Hello"
                    override suspend fun execute(context: ExecutionContext) {}
                    override fun getParams() = params
                }
            }
            val factory = registry.getActionFactoryById("say_hello")!!
            val node = factory(mapOf("message" to "Hello!"))
            node.nodeId shouldBe "say_hello"
            node.getParams() shouldBe mapOf("message" to "Hello!")
        }

        "getActionFactory returns null for unregistered material" {
            // Req 10.4: lookup for unregistered material returns null
            val registry = freshRegistry()
            registry.getActionFactory(Material.STONE).shouldBeNull()
        }

        "getConditionFactory returns null for unregistered material" {
            val registry = freshRegistry()
            registry.getConditionFactory(Material.GRASS_BLOCK).shouldBeNull()
        }

        "getValueFactory returns null for unregistered material" {
            val registry = freshRegistry()
            registry.getValueFactory(Material.IRON_BLOCK).shouldBeNull()
        }

        "getActionFactoryById returns null for unregistered nodeId" {
            val registry = freshRegistry()
            registry.getActionFactoryById("not_registered").shouldBeNull()
        }

        "getConditionFactoryById returns null for unregistered nodeId" {
            val registry = freshRegistry()
            registry.getConditionFactoryById("not_registered").shouldBeNull()
        }

        "getValueFactoryById returns null for unregistered nodeId" {
            val registry = freshRegistry()
            registry.getValueFactoryById("not_registered").shouldBeNull()
        }
    }

    // -----------------------------------------------------------------------
    // 3. Adapter fallback: wrapping IAction/ICondition/IValue in CommandNode (Req 10.1)
    // -----------------------------------------------------------------------

    "Legacy adapter wrapping (fallback to CommandNode)" - {

        "LegacyActionAdapter.toCommandNode() produces CommandNode with ACTION type" {
            // Req 10.1: IAction wrapped in adapter exposes CommandNode with ACTION type
            val action = stubAction("fire_event")
            val adapter = LegacyActionAdapter(action)
            val node = adapter.toCommandNode()
            node.type shouldBe NodeType.ACTION
        }

        "LegacyActionAdapter.toCommandNode() preserves nodeId from wrapped IAction" {
            // Req 10.1: wrapped action's nodeId is preserved
            val action = stubAction("fire_event")
            val adapter = LegacyActionAdapter(action)
            adapter.toCommandNode().nodeId shouldBe "fire_event"
        }

        "LegacyActionAdapter.toCommandNode() preserves params from wrapped IAction" {
            // Req 10.1: wrapped action's getParams() are used as CommandNode.params
            val action = stubAction("fire_event")
            val adapter = LegacyActionAdapter(action)
            adapter.toCommandNode().params shouldBe mapOf("key" to "value")
        }

        "LegacyActionAdapter delegates IAction methods to wrapped instance" {
            // Req 10.1: adapter delegates nodeId/displayName via `by` delegation
            val action = stubAction("fire_event")
            val adapter = LegacyActionAdapter(action)
            adapter.nodeId shouldBe "fire_event"
            adapter.displayName shouldBe "Action fire_event"
        }

        "LegacyConditionAdapter.toCommandNode() produces CommandNode with CONDITION type" {
            // Req 10.1: ICondition wrapped in adapter exposes CommandNode with CONDITION type
            val condition = stubCondition("is_raining")
            val adapter = LegacyConditionAdapter(condition)
            val node = adapter.toCommandNode()
            node.type shouldBe NodeType.CONDITION
        }

        "LegacyConditionAdapter.toCommandNode() preserves nodeId and params" {
            // Req 10.1: adapter preserves nodeId and params from wrapped ICondition
            val condition = stubCondition("is_raining")
            val adapter = LegacyConditionAdapter(condition)
            val node = adapter.toCommandNode()
            node.nodeId shouldBe "is_raining"
            node.params shouldBe mapOf("threshold" to 5)
        }

        "LegacyConditionAdapter delegates ICondition methods to wrapped instance" {
            // Req 10.1: adapter delegates nodeId/displayName via `by` delegation
            val condition = stubCondition("is_raining")
            val adapter = LegacyConditionAdapter(condition)
            adapter.nodeId shouldBe "is_raining"
            adapter.displayName shouldBe "Condition is_raining"
        }

        "LegacyValueAdapter.toCommandNode() produces CommandNode with VALUE type" {
            // Req 10.1: IValue wrapped in adapter exposes CommandNode with VALUE type
            val value = stubValue("player_name")
            val adapter = LegacyValueAdapter(value)
            val node = adapter.toCommandNode()
            node.type shouldBe NodeType.VALUE
        }

        "LegacyValueAdapter.toCommandNode() preserves nodeId and params" {
            // Req 10.1: adapter preserves nodeId and params from wrapped IValue
            val value = stubValue("player_name")
            val adapter = LegacyValueAdapter(value)
            val node = adapter.toCommandNode()
            node.nodeId shouldBe "player_name"
            node.params shouldBe mapOf("val" to "hello")
        }

        "LegacyValueAdapter delegates IValue methods to wrapped instance" {
            // Req 10.1: adapter delegates nodeId/displayName via `by` delegation
            val value = stubValue("player_name")
            val adapter = LegacyValueAdapter(value)
            adapter.nodeId shouldBe "player_name"
            adapter.displayName shouldBe "Value player_name"
        }

        "registerCommandNode with factory wrapping LegacyActionAdapter produces correct node" {
            // Req 10.1: IAction can be registered via CommandNode factory wrapping an adapter
            val registry = freshRegistry()
            val legacyAction = stubAction("teleport")
            registry.registerCommandNode("teleport", NodeType.ACTION) { params ->
                LegacyActionAdapter(legacyAction).toCommandNode().copy(params = params)
            }
            val factory = registry.getCommandNodeFactory("teleport")!!
            val node = factory(mapOf("x" to 10, "z" to 20))
            node.type shouldBe NodeType.ACTION
            node.nodeId shouldBe "teleport"
            node.params shouldBe mapOf("x" to 10, "z" to 20)
        }
    }

    // -----------------------------------------------------------------------
    // 4. Negative / edge cases
    // -----------------------------------------------------------------------

    "Negative and edge cases" - {

        "getCommandNodeFactory on empty registry returns null" {
            // Req 8.7: fresh registry has no factories
            val registry = freshRegistry()
            registry.getCommandNodeFactory("any_id").shouldBeNull()
        }

        "registering same nodeId twice overwrites the previous factory" {
            // Overwrite semantics: second registration replaces first
            val registry = freshRegistry()
            registry.registerCommandNode("msg", NodeType.ACTION) { _ ->
                CommandNode(NodeType.ACTION, "msg", mapOf("v" to "first"))
            }
            registry.registerCommandNode("msg", NodeType.ACTION) { _ ->
                CommandNode(NodeType.ACTION, "msg", mapOf("v" to "second"))
            }
            val node = registry.getCommandNodeFactory("msg")!!(emptyMap())
            node.params shouldBe mapOf("v" to "second")
        }

        "getRegisteredNodeIds returns immutable snapshot (adding later does not affect prior snapshot)" {
            // Req 8.7: getRegisteredNodeIds returns a snapshot, not a live view
            val registry = freshRegistry()
            registry.registerCommandNode("first", NodeType.ACTION) { params ->
                CommandNode(NodeType.ACTION, "first", params)
            }
            val snapshot = registry.getRegisteredNodeIds()
            registry.registerCommandNode("second", NodeType.ACTION) { params ->
                CommandNode(NodeType.ACTION, "second", params)
            }
            // snapshot should NOT contain "second" (taken before second registration)
            snapshot shouldNotContain "second"
            snapshot shouldContain "first"
        }

        "factory receives params exactly as provided" {
            // Req 8.7: factory is called with the exact params map passed in
            val registry = freshRegistry()
            var capturedParams: Map<String, Any> = emptyMap()
            registry.registerCommandNode("capture_test", NodeType.VALUE) { params ->
                capturedParams = params
                CommandNode(NodeType.VALUE, "capture_test", params)
            }
            val expectedParams = mapOf("a" to "1", "b" to 42)
            registry.getCommandNodeFactory("capture_test")!!(expectedParams)
            capturedParams shouldBe expectedParams
        }
    }
})

/**
 * Minimal in-memory [NodeRegistry] implementation used exclusively in tests.
 * Supports the full NodeRegistry contract including CommandNode-based registration.
 *
 * This avoids a test dependency on ocp-plugin while staying inside the ocp-api module.
 */
class TestNodeRegistry : NodeRegistry {

    private val actionFactories = ConcurrentHashMap<Material, (Map<String, Any>) -> IAction>()
    private val conditionFactories = ConcurrentHashMap<Material, (Map<String, Any>) -> ICondition>()
    private val valueFactories = ConcurrentHashMap<Material, (Map<String, Any>) -> IValue<*>>()
    private val eventFactories = ConcurrentHashMap<Material, () -> IEvent>()

    private val actionNodeIds = ConcurrentHashMap<Material, String>()
    private val conditionNodeIds = ConcurrentHashMap<Material, String>()
    private val valueNodeIds = ConcurrentHashMap<Material, String>()

    private val actionFactoriesById = ConcurrentHashMap<String, (Map<String, Any>) -> IAction>()
    private val conditionFactoriesById = ConcurrentHashMap<String, (Map<String, Any>) -> ICondition>()
    private val valueFactoriesById = ConcurrentHashMap<String, (Map<String, Any>) -> IValue<*>>()

    private val commandNodeFactories = ConcurrentHashMap<String, (Map<String, Any>) -> CommandNode>()

    override fun registerAction(blockType: Material, factory: (Map<String, Any>) -> IAction) {
        actionFactories[blockType] = factory
    }

    override fun registerAction(blockType: Material, nodeId: String, factory: (Map<String, Any>) -> IAction) {
        require(nodeId.isNotBlank()) { "Action nodeId must not be blank" }
        actionFactories[blockType] = factory
        actionNodeIds[blockType] = nodeId
        actionFactoriesById[nodeId] = factory
    }

    override fun registerCondition(blockType: Material, factory: (Map<String, Any>) -> ICondition) {
        conditionFactories[blockType] = factory
    }

    override fun registerCondition(blockType: Material, nodeId: String, factory: (Map<String, Any>) -> ICondition) {
        require(nodeId.isNotBlank()) { "Condition nodeId must not be blank" }
        conditionFactories[blockType] = factory
        conditionNodeIds[blockType] = nodeId
        conditionFactoriesById[nodeId] = factory
    }

    override fun registerValue(blockType: Material, factory: (Map<String, Any>) -> IValue<*>) {
        valueFactories[blockType] = factory
    }

    override fun registerValue(blockType: Material, nodeId: String, factory: (Map<String, Any>) -> IValue<*>) {
        require(nodeId.isNotBlank()) { "Value nodeId must not be blank" }
        valueFactories[blockType] = factory
        valueNodeIds[blockType] = nodeId
        valueFactoriesById[nodeId] = factory
    }

    override fun registerEvent(blockType: Material, factory: () -> IEvent) {
        eventFactories[blockType] = factory
    }

    override fun getActionFactory(blockType: Material) = actionFactories[blockType]
    override fun getConditionFactory(blockType: Material) = conditionFactories[blockType]
    override fun getValueFactory(blockType: Material) = valueFactories[blockType]
    override fun getEventFactory(blockType: Material) = eventFactories[blockType]

    override fun getActionNodeId(blockType: Material) = actionNodeIds[blockType]
    override fun getConditionNodeId(blockType: Material) = conditionNodeIds[blockType]
    override fun getValueNodeId(blockType: Material) = valueNodeIds[blockType]

    override fun getActionFactoryById(nodeId: String) = actionFactoriesById[nodeId]
    override fun getConditionFactoryById(nodeId: String) = conditionFactoriesById[nodeId]
    override fun getValueFactoryById(nodeId: String) = valueFactoriesById[nodeId]

    override fun getMaterialForNode(node: INode): Material? {
        val id = node.nodeId
        actionNodeIds.entries.firstOrNull { it.value == id }?.key?.let { return it }
        conditionNodeIds.entries.firstOrNull { it.value == id }?.key?.let { return it }
        valueNodeIds.entries.firstOrNull { it.value == id }?.key?.let { return it }
        return null
    }

    override fun registerCommandNode(nodeId: String, type: NodeType, factory: (Map<String, Any>) -> CommandNode) {
        require(nodeId.isNotBlank()) { "CommandNode nodeId must not be blank" }
        commandNodeFactories[nodeId] = factory
    }

    override fun getCommandNodeFactory(nodeId: String): ((Map<String, Any>) -> CommandNode)? =
        commandNodeFactories[nodeId]

    override fun getRegisteredNodeIds(): Set<String> = commandNodeFactories.keys.toSet()
}
