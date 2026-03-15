package com.opencreativeplus.plugin

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.plugin.compiler.ASTCompiler
import com.opencreativeplus.plugin.node.event.OnJoinEvent
import com.opencreativeplus.plugin.registry.NodeRegistryImpl
import com.opencreativeplus.plugin.registry.BuiltInNodeRegistry
import com.opencreativeplus.plugin.scanner.CodeLine
import com.opencreativeplus.plugin.scanner.ScannedNode
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * End-to-end flow tests documenting the complete pipeline:
 * Code placement → Scanning → Compilation → Script registration → Execution
 *
 * Requirements: All requirements (integration validation)
 */
class EndToEndFlowTest {

    @Test
    fun `compile a simple event-action code line`() {
        val registry = NodeRegistryImpl()
        BuiltInNodeRegistry.register(registry)
        val compiler = ASTCompiler(registry)

        val location = mockk<Location>(relaxed = true)

        // Simulate a scanned code line: [DIAMOND_BLOCK (event), PAPER (action)]
        val codeLine = CodeLine(
            startLocation = location,
            nodes = listOf(
                ScannedNode(Material.DIAMOND_BLOCK, location, emptyMap()),
                ScannedNode(Material.PAPER, location, mapOf("message" to "Hello!"))
            )
        )

        val result = compiler.compile(listOf(codeLine))

        assertTrue(result.errors.isEmpty(), "Expected no compilation errors but got: ${result.errors}")
        assertEquals(1, result.scripts.size)
        assertEquals("player_join", result.scripts[0].event.eventType)
        assertEquals(1, result.scripts[0].actions.size)
    }

    @Test
    fun `compilation fails when first block is not an event`() {
        val registry = NodeRegistryImpl()
        BuiltInNodeRegistry.register(registry)
        val compiler = ASTCompiler(registry)

        val location = mockk<Location>(relaxed = true)

        // PAPER first — not an event block
        val codeLine = CodeLine(
            startLocation = location,
            nodes = listOf(
                ScannedNode(Material.PAPER, location, mapOf("message" to "Hello!"))
            )
        )

        val result = compiler.compile(listOf(codeLine))

        assertTrue(result.errors.isNotEmpty(), "Expected compilation error for missing event block")
        assertTrue(result.scripts.isEmpty())
    }

    @Test
    fun `compilation collects all errors without stopping`() {
        val registry = NodeRegistryImpl()
        BuiltInNodeRegistry.register(registry)
        val compiler = ASTCompiler(registry)

        val location = mockk<Location>(relaxed = true)

        // Two invalid code lines
        val codeLines = listOf(
            CodeLine(location, listOf(ScannedNode(Material.PAPER, location, emptyMap()))),
            CodeLine(location, listOf(ScannedNode(Material.CLOCK, location, emptyMap())))
        )

        val result = compiler.compile(codeLines)

        assertEquals(2, result.errors.size, "Expected 2 errors, one per invalid code line")
        assertTrue(result.scripts.isEmpty())
    }
}
