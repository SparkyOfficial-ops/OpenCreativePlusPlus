// Feature: ocp-plugin-fixes-and-completions, Property 1: All four nodes return non-null factory after registration
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.registry

import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.opencreativeplus.core.execution.VariableManager
import com.opencreativeplus.plugin.event.EventDispatcher
import com.opencreativeplus.plugin.mode.ModeManagerImpl
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.of
import io.kotest.property.forAll
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.bukkit.plugin.Plugin

/**
 * Property-based test for Property 1: All four nodes (`set_variable`, `get_variable`,
 * `gui_designer`, `open_menu`) return a non-null factory after registration.
 *
 * Validates: Requirements 1.3, 1.4, 1.5, 1.6
 */
class NodeRegistryInvariantPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Shared test fixtures
    // -----------------------------------------------------------------------

    val mongoDatabase = mockk<MongoDatabase>(relaxed = true)
    val variableManager = VariableManager(mongoDatabase)

    val plugin = mockk<Plugin>(relaxed = true)
    val modeManager = mockk<ModeManagerImpl>(relaxed = true)
    val plotManager = mockk<PlotManagerImpl>(relaxed = true)
    val eventDispatcher = mockk<EventDispatcher>(relaxed = true)
    val scope = CoroutineScope(Dispatchers.Default)

    fun freshRegistry(): NodeRegistryImpl {
        val registry = NodeRegistryImpl()
        BuiltInNodeRegistry.registerVariableNodes(registry, variableManager)
        BuiltInNodeRegistry.registerGUINodes(registry, plugin, modeManager, plotManager, eventDispatcher, scope)
        return registry
    }

    // -----------------------------------------------------------------------
    // Property 1: All four nodes return a non-null factory after registration
    // -----------------------------------------------------------------------

    "Property 1: All four nodes return a non-null factory after registration" - {

        "set_variable action factory is non-null after registerVariableNodes" {
            // Validates: Requirement 1.3
            val registry = freshRegistry()
            registry.getActionFactoryById("set_variable") shouldNotBe null
        }

        "get_variable value factory is non-null after registerVariableNodes" {
            // Validates: Requirement 1.4
            val registry = freshRegistry()
            registry.getValueFactoryById("get_variable") shouldNotBe null
        }

        "gui_designer action factory is non-null after registerGUINodes" {
            // Validates: Requirement 1.5
            val registry = freshRegistry()
            registry.getActionFactoryById("gui_designer") shouldNotBe null
        }

        "open_menu action factory is non-null after registerGUINodes" {
            // Validates: Requirement 1.6
            val registry = freshRegistry()
            registry.getActionFactoryById("open_menu") shouldNotBe null
        }

        "property: every action nodeId in the set returns a non-null factory (property-based)" {
            // Validates: Requirements 1.3, 1.5, 1.6
            val registry = freshRegistry()
            val actionNodeIds = listOf("set_variable", "open_menu", "gui_designer")
            forAll(Arb.of(actionNodeIds)) { nodeId ->
                registry.getActionFactoryById(nodeId) != null
            }
        }

        "property: every value nodeId in the set returns a non-null factory (property-based)" {
            // Validates: Requirement 1.4
            val registry = freshRegistry()
            val valueNodeIds = listOf("get_variable")
            forAll(Arb.of(valueNodeIds)) { nodeId ->
                registry.getValueFactoryById(nodeId) != null
            }
        }
    }
})
