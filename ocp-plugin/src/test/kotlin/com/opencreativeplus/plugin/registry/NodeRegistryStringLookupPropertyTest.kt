// Feature: category-based-coding-ui, Property 9: NodeRegistry string lookup
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.registry

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.node.IValue
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import org.bukkit.Material

/**
 * Property-based test for Property 9: NodeRegistry string lookup.
 *
 * For any nodeId registered via registerAction(material, nodeId, factory),
 * getActionFactoryById(nodeId) must return a non-null factory.
 * For any string not registered, getActionFactoryById must return null.
 * The same invariant holds for getConditionFactoryById and getValueFactoryById.
 *
 * Validates: Requirements 5.1, 5.2, 5.3, 5.4
 */
class NodeRegistryStringLookupPropertyTest : FreeSpec({

    val arbNodeId = Arb.string(minSize = 1, maxSize = 32).filter { it.isNotBlank() }
    val arbMaterial = Arb.element(Material.entries.filter { !it.isLegacy })

    // -----------------------------------------------------------------------
    // Property 9: NodeRegistry string lookup
    // -----------------------------------------------------------------------

    "Property 9: NodeRegistry string lookup" - {

        "getActionFactoryById returns non-null for a registered nodeId" {
            // Feature: category-based-coding-ui, Property 9: NodeRegistry string lookup
            checkAll(PropTestConfig(iterations = 100), arbMaterial, arbNodeId) { material, nodeId ->
                val registry = NodeRegistryImpl()
                registry.registerAction(material, nodeId) { _ ->
                    object : IAction {
                        override val nodeId = nodeId
                        override val displayName = "Test Action"
                        override suspend fun execute(context: ExecutionContext) {}
                    }
                }
                registry.getActionFactoryById(nodeId) shouldNotBe null
            }
        }

        "getActionFactoryById returns null for an unregistered nodeId" {
            // Feature: category-based-coding-ui, Property 9: NodeRegistry string lookup
            checkAll(PropTestConfig(iterations = 100), arbNodeId) { nodeId ->
                val registry = NodeRegistryImpl()
                // Nothing registered — any nodeId lookup must return null
                registry.getActionFactoryById(nodeId) shouldBe null
            }
        }

        "getConditionFactoryById returns non-null for a registered nodeId" {
            // Feature: category-based-coding-ui, Property 9: NodeRegistry string lookup
            checkAll(PropTestConfig(iterations = 100), arbMaterial, arbNodeId) { material, nodeId ->
                val registry = NodeRegistryImpl()
                registry.registerCondition(material, nodeId) { _ ->
                    object : ICondition {
                        override val nodeId = nodeId
                        override val displayName = "Test Condition"
                        override suspend fun evaluate(context: ExecutionContext): Boolean = true
                    }
                }
                registry.getConditionFactoryById(nodeId) shouldNotBe null
            }
        }

        "getConditionFactoryById returns null for an unregistered nodeId" {
            // Feature: category-based-coding-ui, Property 9: NodeRegistry string lookup
            checkAll(PropTestConfig(iterations = 100), arbNodeId) { nodeId ->
                val registry = NodeRegistryImpl()
                registry.getConditionFactoryById(nodeId) shouldBe null
            }
        }

        "getValueFactoryById returns non-null for a registered nodeId" {
            // Feature: category-based-coding-ui, Property 9: NodeRegistry string lookup
            checkAll(PropTestConfig(iterations = 100), arbMaterial, arbNodeId) { material, nodeId ->
                val registry = NodeRegistryImpl()
                registry.registerValue(material, nodeId) { _ ->
                    object : IValue<String> {
                        override val nodeId = nodeId
                        override val displayName = "Test Value"
                        override suspend fun compute(context: ExecutionContext) = "value"
                    }
                }
                registry.getValueFactoryById(nodeId) shouldNotBe null
            }
        }

        "getValueFactoryById returns null for an unregistered nodeId" {
            // Feature: category-based-coding-ui, Property 9: NodeRegistry string lookup
            checkAll(PropTestConfig(iterations = 100), arbNodeId) { nodeId ->
                val registry = NodeRegistryImpl()
                registry.getValueFactoryById(nodeId) shouldBe null
            }
        }

        "factory returned by getActionFactoryById produces a working node" {
            // Feature: category-based-coding-ui, Property 9: NodeRegistry string lookup
            checkAll(PropTestConfig(iterations = 100), arbMaterial, arbNodeId) { material, nodeId ->
                val registry = NodeRegistryImpl()
                registry.registerAction(material, nodeId) { _ ->
                    object : IAction {
                        override val nodeId = nodeId
                        override val displayName = "Test Action"
                        override suspend fun execute(context: ExecutionContext) {}
                    }
                }
                val factory = registry.getActionFactoryById(nodeId)!!
                val node = factory(emptyMap())
                node.nodeId shouldBe nodeId
            }
        }

        "Material-based lookup still works after registering with nodeId (backward compatibility)" {
            // Feature: category-based-coding-ui, Property 9: NodeRegistry string lookup
            checkAll(PropTestConfig(iterations = 100), arbMaterial, arbNodeId) { material, nodeId ->
                val registry = NodeRegistryImpl()
                registry.registerAction(material, nodeId) { _ ->
                    object : IAction {
                        override val nodeId = nodeId
                        override val displayName = "Test Action"
                        override suspend fun execute(context: ExecutionContext) {}
                    }
                }
                // Both lookup paths must work
                registry.getActionFactory(material) shouldNotBe null
                registry.getActionFactoryById(nodeId) shouldNotBe null
                registry.getActionNodeId(material) shouldBe nodeId
            }
        }
    }
})
