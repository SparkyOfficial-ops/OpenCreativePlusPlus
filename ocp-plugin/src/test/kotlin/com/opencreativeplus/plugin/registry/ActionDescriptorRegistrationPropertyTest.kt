// Feature: category-based-coding-ui, Property 2: ActionDescriptor registration validation
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.registry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import org.bukkit.Material

/**
 * Property-based test for Property 2: ActionDescriptor registration validation.
 *
 * Validates that:
 * - Registering an ActionDescriptor with a blank id throws IllegalArgumentException (Req 1.4)
 * - Registering a duplicate id throws IllegalArgumentException (Req 1.5)
 *
 * Validates: Requirements 1.4, 1.5
 */
class ActionDescriptorRegistrationPropertyTest : FreeSpec({

    val validIds = listOf(
        "action_a", "send_msg", "teleport", "kill_player", "set_var",
        "wait", "loop", "branch", "event_join", "event_quit"
    )

    // -----------------------------------------------------------------------
    // Property 2: ActionDescriptor registration validation
    // -----------------------------------------------------------------------

    "Property 2: ActionDescriptor registration validation" - {

        "registering a descriptor with a blank id throws IllegalArgumentException" {
            // Feature: category-based-coding-ui, Property 2: ActionDescriptor registration validation
            val blankIds = listOf("", " ", "   ", "\t", "\n", "  \t  ")
            val arbBlankId = Arb.element(blankIds)
            val arbCategory = Arb.element(NodeCategory.entries)

            checkAll(PropTestConfig(iterations = 10), arbBlankId, arbCategory) { blankId, category ->
                val registry = CategoryRegistry()
                val descriptor = ActionDescriptor(
                    id = blankId,
                    displayName = "Test Action",
                    icon = Material.PAPER,
                    category = category
                )
                shouldThrow<IllegalArgumentException> {
                    registry.register(descriptor)
                }
            }
        }

        "registering a descriptor with a duplicate id throws IllegalArgumentException" {
            // Feature: category-based-coding-ui, Property 2: ActionDescriptor registration validation
            val arbId = Arb.element(validIds)
            val arbCategory = Arb.element(NodeCategory.entries)

            checkAll(PropTestConfig(iterations = 20), arbId, arbCategory, arbCategory) { id, cat1, cat2 ->
                val registry = CategoryRegistry()
                val first = ActionDescriptor(
                    id = id,
                    displayName = "First",
                    icon = Material.PAPER,
                    category = cat1
                )
                val second = ActionDescriptor(
                    id = id,
                    displayName = "Second",
                    icon = Material.STONE,
                    category = cat2
                )
                registry.register(first)
                shouldThrow<IllegalArgumentException> {
                    registry.register(second)
                }
            }
        }

        "registering a descriptor with a valid unique id succeeds" {
            // Feature: category-based-coding-ui, Property 2: ActionDescriptor registration validation
            val arbId = Arb.element(validIds)
            val arbCategory = Arb.element(NodeCategory.entries)

            checkAll(PropTestConfig(iterations = 20), arbId, arbCategory) { id, category ->
                val registry = CategoryRegistry()
                val descriptor = ActionDescriptor(
                    id = id,
                    displayName = "Valid Action",
                    icon = Material.PAPER,
                    category = category
                )
                registry.register(descriptor)
                registry.getDescriptorById(id) shouldBe descriptor
            }
        }

        "after failed blank-id registration, registry remains empty" {
            // Feature: category-based-coding-ui, Property 2: ActionDescriptor registration validation
            val blankIds = listOf("", " ", "   ")
            val arbBlankId = Arb.element(blankIds)
            val arbCategory = Arb.element(NodeCategory.entries)

            checkAll(PropTestConfig(iterations = 10), arbBlankId, arbCategory) { blankId, category ->
                val registry = CategoryRegistry()
                val descriptor = ActionDescriptor(
                    id = blankId,
                    displayName = "Test",
                    icon = Material.PAPER,
                    category = category
                )
                try { registry.register(descriptor) } catch (_: IllegalArgumentException) {}
                NodeCategory.entries.forEach { cat ->
                    registry.getDescriptors(cat).size shouldBe 0
                }
            }
        }

        "after failed duplicate registration, only the first descriptor is retained" {
            // Feature: category-based-coding-ui, Property 2: ActionDescriptor registration validation
            val arbId = Arb.element(validIds)
            val arbCategory = Arb.element(NodeCategory.entries)

            checkAll(PropTestConfig(iterations = 20), arbId, arbCategory) { id, category ->
                val registry = CategoryRegistry()
                val first = ActionDescriptor(
                    id = id,
                    displayName = "First",
                    icon = Material.PAPER,
                    category = category
                )
                val second = ActionDescriptor(
                    id = id,
                    displayName = "Second",
                    icon = Material.STONE,
                    category = category
                )
                registry.register(first)
                try { registry.register(second) } catch (_: IllegalArgumentException) {}
                registry.getDescriptorById(id) shouldBe first
            }
        }
    }
})
