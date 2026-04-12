@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.api.dsl

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.ParameterTypeMismatchException
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.api.node.IValue
import com.opencreativeplus.api.registry.NodeRegistry
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.bukkit.Material
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based tests for [NodeDSL] registration round-trip.
 *
 *  10.2, 10.3, 10.4
 */
class NodeDSLPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Minimal in-memory stub implementation of [NodeRegistry]. */
    fun stubRegistry(): NodeRegistry {
        val actions = mutableMapOf<Material, (Map<String, Any>) -> IAction>()
        val conditions = mutableMapOf<Material, (Map<String, Any>) -> ICondition>()
        return object : NodeRegistry {
            override fun registerAction(blockType: Material, factory: (Map<String, Any>) -> IAction) {
                actions[blockType] = factory
            }
            override fun registerCondition(blockType: Material, factory: (Map<String, Any>) -> ICondition) {
                conditions[blockType] = factory
            }
            override fun registerValue(blockType: Material, factory: (Map<String, Any>) -> IValue<*>) =
                throw UnsupportedOperationException()
            override fun registerEvent(blockType: Material, factory: () -> IEvent) =
                throw UnsupportedOperationException()
            override fun getActionFactory(blockType: Material) = actions[blockType]
            override fun getConditionFactory(blockType: Material) = conditions[blockType]
            override fun getValueFactory(blockType: Material): ((Map<String, Any>) -> IValue<*>)? =
                throw UnsupportedOperationException()
            override fun getEventFactory(blockType: Material): (() -> IEvent)? =
                throw UnsupportedOperationException()
        }
    }

    /** Minimal no-op [VariableScope] backed by a mutable map. */
    fun mapScope(initial: Map<String, Any> = emptyMap()): VariableScope {
        val store = initial.toMutableMap()
        return object : VariableScope {
            override fun get(name: String): Any? = store[name]
            override fun set(name: String, value: Any) { store[name] = value }
            override fun has(name: String): Boolean = store.containsKey(name)
            override fun clear() = store.clear()
        }
    }

    /** Build a minimal [ExecutionContext] with configurable [eventData] and [localScope]. */
    fun stubContext(
        eventData: Map<String, Any> = emptyMap(),
        localScope: Map<String, Any> = emptyMap()
    ): ExecutionContext = object : ExecutionContext {
        override val plotId: UUID = UUID.randomUUID()
        override val player = null
        override val eventData: Map<String, Any> = eventData
        override val localScope: VariableScope = mapScope(localScope)
        override val plotScope: VariableScope = mapScope()
        override val savedScope: VariableScope = mapScope()
        override val operationCount: AtomicInteger = AtomicInteger(0)
        override suspend fun <T> syncContext(block: () -> T): T = block()
    }

    /** Fixed set of [Material] values safe to use without a running Bukkit server. */
    val arbMaterial: Arb<Material> = Arb.element(
        listOf(
            Material.STONE,
            Material.GOLD_BLOCK,
            Material.IRON_BLOCK,
            Material.DIAMOND_BLOCK,
            Material.EMERALD_BLOCK
        )
    )

    val arbName: Arb<String> = Arb.string(1..30)
    val arbDescription: Arb<String> = Arb.string(1..30)
    val arbParamName: Arb<String> = Arb.string(1..30)

    // -----------------------------------------------------------------------
    // Property 15a — Action registration round-trip (Req 10.2)
    // -----------------------------------------------------------------------

    "Property 15a: Action registration round-trip" - {

        "factory is non-null after registerAction" {
            //  10.2
            checkAll(PropTestConfig(iterations = 20), arbMaterial, arbName) { material: Material, name: String ->
                val registry = stubRegistry()
                val dsl = NodeDSL(registry)
                dsl.registerAction(material) {
                    name(name)
                    execute { }
                }
                registry.getActionFactory(material).shouldNotBeNull()
            }
        }

        "produced IAction has correct displayName" {
            //  10.2
            checkAll(PropTestConfig(iterations = 20), arbMaterial, arbName) { material: Material, name: String ->
                val registry = stubRegistry()
                val dsl = NodeDSL(registry)
                dsl.registerAction(material) {
                    name(name)
                    execute { }
                }
                val factory = registry.getActionFactory(material)!!
                val action = factory(emptyMap())
                action.displayName shouldBe name
            }
        }

        "produced IAction has nodeId equal to material.name" {
            //  10.2
            checkAll(PropTestConfig(iterations = 20), arbMaterial, arbName) { material: Material, name: String ->
                val registry = stubRegistry()
                val dsl = NodeDSL(registry)
                dsl.registerAction(material) {
                    name(name)
                    execute { }
                }
                val factory = registry.getActionFactory(material)!!
                val action = factory(emptyMap())
                action.nodeId shouldBe material.name
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 15b — Condition registration round-trip (Req 10.3)
    // -----------------------------------------------------------------------

    "Property 15b: Condition registration round-trip" - {

        "factory is non-null after registerCondition" {
            //  10.3
            checkAll(PropTestConfig(iterations = 20), arbMaterial, arbName) { material: Material, name: String ->
                val registry = stubRegistry()
                val dsl = NodeDSL(registry)
                dsl.registerCondition(material) {
                    name(name)
                    evaluate { true }
                }
                registry.getConditionFactory(material).shouldNotBeNull()
            }
        }

        "produced ICondition has correct displayName" {
            //  10.3
            checkAll(PropTestConfig(iterations = 20), arbMaterial, arbName) { material: Material, name: String ->
                val registry = stubRegistry()
                val dsl = NodeDSL(registry)
                dsl.registerCondition(material) {
                    name(name)
                    evaluate { true }
                }
                val factory = registry.getConditionFactory(material)!!
                val condition = factory(emptyMap())
                condition.displayName shouldBe name
            }
        }

        "produced ICondition has nodeId equal to material.name" {
            //  10.3
            checkAll(PropTestConfig(iterations = 20), arbMaterial, arbName) { material: Material, name: String ->
                val registry = stubRegistry()
                val dsl = NodeDSL(registry)
                dsl.registerCondition(material) {
                    name(name)
                    evaluate { true }
                }
                val factory = registry.getConditionFactory(material)!!
                val condition = factory(emptyMap())
                condition.nodeId shouldBe material.name
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 15c — name() and description() are preserved (Req 10.4)
    // -----------------------------------------------------------------------

    "Property 15c: name() and description() are preserved in produced IAction" - {

        "displayName matches name() call" {
            //  10.4
            checkAll(
                PropTestConfig(iterations = 20),
                arbMaterial,
                arbName,
                arbDescription
            ) { material: Material, name: String, desc: String ->
                val registry = stubRegistry()
                val dsl = NodeDSL(registry)
                dsl.registerAction(material) {
                    name(name)
                    description(desc)
                    execute { }
                }
                val factory = registry.getActionFactory(material)!!
                val action = factory(emptyMap())
                action.displayName shouldBe name
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 15d — requires<T> validates params at execution time (Req 10.2)
    // -----------------------------------------------------------------------

    "Property 15d: requires<T> validates params at execution time" - {

        "execute() with empty eventData throws ParameterTypeMismatchException" {
            //  10.2
            checkAll(PropTestConfig(iterations = 20), arbMaterial, arbParamName) { material: Material, paramName: String ->
                val registry = stubRegistry()
                val dsl = NodeDSL(registry)
                dsl.registerAction(material) {
                    requires<String>(paramName)
                    execute { }
                }
                val factory = registry.getActionFactory(material)!!
                val action = factory(emptyMap())
                val ctx = stubContext(eventData = emptyMap(), localScope = emptyMap())
                shouldThrow<ParameterTypeMismatchException> {
                    runBlocking { action.execute(ctx) }
                }
            }
        }

        "execute() with correct param does NOT throw" {
            //  10.2
            checkAll(PropTestConfig(iterations = 20), arbMaterial, arbParamName) { material: Material, paramName: String ->
                val registry = stubRegistry()
                val dsl = NodeDSL(registry)
                dsl.registerAction(material) {
                    requires<String>(paramName)
                    execute { }
                }
                val factory = registry.getActionFactory(material)!!
                val action = factory(mapOf(paramName to "hello"))
                val ctx = stubContext(eventData = mapOf(paramName to "hello"))
                shouldNotThrowAny {
                    runBlocking { action.execute(ctx) }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 15e — OcpPluginAPI.nodes {} extension delegates to registry (Req 10.1)
    // -----------------------------------------------------------------------

    "Property 15e: OcpPluginAPI.nodes {} extension delegates to registry" - {

        "getActionFactory is non-null after registering via api.nodes {}" {
            //  10.1
            checkAll(PropTestConfig(iterations = 20), arbMaterial) { material: Material ->
                val registry = stubRegistry()
                val api = object : OcpPluginAPI {
                    override val nodeRegistry: NodeRegistry = registry
                }
                api.nodes {
                    registerAction(material) {
                        execute { }
                    }
                }
                registry.getActionFactory(material).shouldNotBeNull()
            }
        }
    }
})
