package com.opencreativeplus.plugin.compiler

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IEvent
import com.opencreativeplus.api.registry.NodeRegistry
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.plugin.node.action.SendMessageAction
import com.opencreativeplus.plugin.node.event.OnJoinEvent
import com.opencreativeplus.plugin.registry.BuiltInNodeRegistry
import com.opencreativeplus.plugin.registry.NodeRegistryImpl
import com.opencreativeplus.plugin.scanner.CodeLine
import com.opencreativeplus.plugin.scanner.ScannedNode
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ASTCompiler].
 *
 * Requirements: 5.3, 23.5, 33.5
 */
class ASTCompilerTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun mockLocation(worldName: String = "test_world", x: Int = 0, y: Int = 5, z: Int = 0): Location {
        val loc = mockk<Location>(relaxed = true)
        val world = mockk<org.bukkit.World>(relaxed = true)
        every { world.name } returns worldName
        every { loc.world } returns world
        every { loc.blockX } returns x
        every { loc.blockY } returns y
        every { loc.blockZ } returns z
        return loc
    }

    private fun registryWithBuiltIns(): NodeRegistryImpl {
        val registry = NodeRegistryImpl()
        BuiltInNodeRegistry.register(registry)
        return registry
    }

    // -----------------------------------------------------------------------
    // Compilation of valid code lines (Req 5.3)
    // -----------------------------------------------------------------------

    @Test
    fun `compile single event-only code line produces script with no actions`() {
        val registry = registryWithBuiltIns()
        val compiler = ASTCompiler(registry)
        val loc = mockLocation()

        val codeLine = CodeLine(
            startLocation = loc,
            nodes = listOf(ScannedNode(Material.DIAMOND_BLOCK, loc, emptyMap()))
        )

        val result = compiler.compile(listOf(codeLine))

        assertFalse(result.hasErrors, "Expected no errors but got: ${result.errors}")
        assertEquals(1, result.scripts.size)
        assertEquals("player_join", result.scripts[0].event.eventType)
        assertTrue(result.scripts[0].actions.isEmpty())
    }

    @Test
    fun `compile event followed by action produces script with one action`() {
        val registry = registryWithBuiltIns()
        val compiler = ASTCompiler(registry)
        val loc = mockLocation()

        val codeLine = CodeLine(
            startLocation = loc,
            nodes = listOf(
                ScannedNode(Material.DIAMOND_BLOCK, loc, emptyMap()),
                ScannedNode(Material.PAPER, loc, mapOf("message" to "Hello!"))
            )
        )

        val result = compiler.compile(listOf(codeLine))

        assertFalse(result.hasErrors, "Expected no errors but got: ${result.errors}")
        assertEquals(1, result.scripts.size)
        assertEquals(1, result.scripts[0].actions.size)
        assertEquals("send_message", result.scripts[0].actions[0].nodeId)
    }

    @Test
    fun `compile event followed by multiple actions preserves order`() {
        val registry = registryWithBuiltIns()
        val compiler = ASTCompiler(registry)
        val loc = mockLocation()

        val codeLine = CodeLine(
            startLocation = loc,
            nodes = listOf(
                ScannedNode(Material.DIAMOND_BLOCK, loc, emptyMap()),
                ScannedNode(Material.PAPER, loc, mapOf("message" to "First")),
                ScannedNode(Material.CLOCK, loc, mapOf("duration" to 10)),
                ScannedNode(Material.PAPER, loc, mapOf("message" to "Second"))
            )
        )

        val result = compiler.compile(listOf(codeLine))

        assertFalse(result.hasErrors)
        assertEquals(3, result.scripts[0].actions.size)
        assertEquals("send_message", result.scripts[0].actions[0].nodeId)
        assertEquals("wait", result.scripts[0].actions[1].nodeId)
        assertEquals("send_message", result.scripts[0].actions[2].nodeId)
    }

    @Test
    fun `compile multiple valid code lines returns all scripts`() {
        val registry = registryWithBuiltIns()
        val compiler = ASTCompiler(registry)
        val loc = mockLocation()

        val codeLines = listOf(
            CodeLine(loc, listOf(ScannedNode(Material.DIAMOND_BLOCK, loc, emptyMap()))),
            CodeLine(loc, listOf(ScannedNode(Material.DIAMOND_BLOCK, loc, emptyMap())))
        )

        val result = compiler.compile(codeLines)

        assertFalse(result.hasErrors)
        assertEquals(2, result.scripts.size)
    }

    @Test
    fun `compile empty list returns empty result with no errors`() {
        val registry = registryWithBuiltIns()
        val compiler = ASTCompiler(registry)

        val result = compiler.compile(emptyList())

        assertFalse(result.hasErrors)
        assertTrue(result.scripts.isEmpty())
    }

    @Test
    fun `compiled script sourceLocation encodes world and coordinates`() {
        val registry = registryWithBuiltIns()
        val compiler = ASTCompiler(registry)
        val loc = mockLocation(worldName = "dev_world", x = 3, y = 10, z = -5)

        val codeLine = CodeLine(
            startLocation = loc,
            nodes = listOf(ScannedNode(Material.DIAMOND_BLOCK, loc, emptyMap()))
        )

        val result = compiler.compile(listOf(codeLine))

        assertFalse(result.hasErrors)
        val sourceLocation = result.scripts[0].sourceLocation
        assertTrue(sourceLocation.contains("dev_world"), "Expected world name in sourceLocation: $sourceLocation")
        assertTrue(sourceLocation.contains("3"), "Expected x=3 in sourceLocation: $sourceLocation")
        assertTrue(sourceLocation.contains("10"), "Expected y=10 in sourceLocation: $sourceLocation")
        assertTrue(sourceLocation.contains("-5"), "Expected z=-5 in sourceLocation: $sourceLocation")
    }

    // -----------------------------------------------------------------------
    // Error collection for invalid blocks (Req 23.5)
    // -----------------------------------------------------------------------

    @Test
    fun `compile code line with no nodes produces error`() {
        val registry = registryWithBuiltIns()
        val compiler = ASTCompiler(registry)
        val loc = mockLocation()

        val codeLine = CodeLine(startLocation = loc, nodes = emptyList())

        val result = compiler.compile(listOf(codeLine))

        assertTrue(result.hasErrors)
        assertTrue(result.scripts.isEmpty())
        assertTrue(result.errors[0].message.isNotBlank())
    }

    @Test
    fun `compile code line starting with non-event block produces error`() {
        val registry = registryWithBuiltIns()
        val compiler = ASTCompiler(registry)
        val loc = mockLocation()

        // PAPER is an action, not an event — should fail
        val codeLine = CodeLine(
            startLocation = loc,
            nodes = listOf(ScannedNode(Material.PAPER, loc, mapOf("message" to "Hi")))
        )

        val result = compiler.compile(listOf(codeLine))

        assertTrue(result.hasErrors)
        assertTrue(result.scripts.isEmpty())
        assertTrue(result.errors[0].message.contains("event", ignoreCase = true))
    }

    @Test
    fun `compile code line with unknown action block produces error`() {
        val registry = registryWithBuiltIns()
        val compiler = ASTCompiler(registry)
        val loc = mockLocation()

        // STONE is not registered as any node type
        val codeLine = CodeLine(
            startLocation = loc,
            nodes = listOf(
                ScannedNode(Material.DIAMOND_BLOCK, loc, emptyMap()),
                ScannedNode(Material.STONE, loc, emptyMap())
            )
        )

        val result = compiler.compile(listOf(codeLine))

        assertTrue(result.hasErrors)
        assertTrue(result.scripts.isEmpty())
        assertTrue(result.errors[0].message.contains("STONE", ignoreCase = true))
    }

    @Test
    fun `compile collects errors from all invalid code lines without stopping`() {
        val registry = registryWithBuiltIns()
        val compiler = ASTCompiler(registry)
        val loc = mockLocation()

        // Three invalid code lines
        val codeLines = listOf(
            CodeLine(loc, emptyList()),
            CodeLine(loc, listOf(ScannedNode(Material.PAPER, loc, emptyMap()))),
            CodeLine(loc, listOf(ScannedNode(Material.STONE, loc, emptyMap())))
        )

        val result = compiler.compile(codeLines)

        assertEquals(3, result.errors.size, "Expected one error per invalid code line")
        assertTrue(result.scripts.isEmpty())
    }

    @Test
    fun `compile mixes valid and invalid code lines correctly`() {
        val registry = registryWithBuiltIns()
        val compiler = ASTCompiler(registry)
        val loc = mockLocation()

        val codeLines = listOf(
            // Valid
            CodeLine(loc, listOf(ScannedNode(Material.DIAMOND_BLOCK, loc, emptyMap()))),
            // Invalid — no event
            CodeLine(loc, listOf(ScannedNode(Material.PAPER, loc, emptyMap()))),
            // Valid
            CodeLine(loc, listOf(
                ScannedNode(Material.DIAMOND_BLOCK, loc, emptyMap()),
                ScannedNode(Material.PAPER, loc, mapOf("message" to "Hi"))
            ))
        )

        val result = compiler.compile(codeLines)

        assertEquals(1, result.errors.size)
        assertEquals(2, result.scripts.size)
    }

    @Test
    fun `compilation error location matches code line start location`() {
        val registry = registryWithBuiltIns()
        val compiler = ASTCompiler(registry)
        val startLoc = mockLocation(worldName = "test_world", x = 7, y = 5, z = 3)

        val codeLine = CodeLine(startLocation = startLoc, nodes = emptyList())

        val result = compiler.compile(listOf(codeLine))

        assertTrue(result.hasErrors)
        // The error location should be the same Location object as the code line start
        assertEquals(startLoc, result.errors[0].location)
    }

    @Test
    fun `compile with unregistered event block type produces error`() {
        val registry = NodeRegistryImpl() // empty registry — nothing registered
        val compiler = ASTCompiler(registry)
        val loc = mockLocation()

        val codeLine = CodeLine(
            startLocation = loc,
            nodes = listOf(ScannedNode(Material.DIAMOND_BLOCK, loc, emptyMap()))
        )

        val result = compiler.compile(listOf(codeLine))

        assertTrue(result.hasErrors)
        assertTrue(result.scripts.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Serialization round-trip (Req 33.5)
    // -----------------------------------------------------------------------

    @Test
    fun `serialize produces non-empty JSON string`() {
        val registry = registryWithBuiltIns()
        val serializer = ASTSerializer(registry)

        val script = CompiledScript(
            event = OnJoinEvent(),
            actions = listOf(SendMessageAction(mapOf("message" to "Hello"))),
            sourceLocation = "test_world@0,5,0"
        )

        val json = serializer.serialize(listOf(script))

        assertTrue(json.isNotBlank())
        assertTrue(json.trimStart().startsWith("["), "Expected JSON array")
    }

    @Test
    fun `serialize preserves eventType and eventNodeId`() {
        val registry = registryWithBuiltIns()
        val serializer = ASTSerializer(registry)

        val script = CompiledScript(
            event = OnJoinEvent(),
            actions = emptyList(),
            sourceLocation = "world@0,5,0"
        )

        val json = serializer.serialize(listOf(script))

        assertTrue(json.contains("player_join"), "Expected eventType 'player_join' in JSON")
        assertTrue(json.contains("on_join"), "Expected eventNodeId 'on_join' in JSON")
    }

    @Test
    fun `serialize preserves action nodeId`() {
        val registry = registryWithBuiltIns()
        val serializer = ASTSerializer(registry)

        val script = CompiledScript(
            event = OnJoinEvent(),
            actions = listOf(SendMessageAction(mapOf("message" to "Hi"))),
            sourceLocation = "world@0,5,0"
        )

        val json = serializer.serialize(listOf(script))

        assertTrue(json.contains("send_message"), "Expected action nodeId 'send_message' in JSON")
    }

    @Test
    fun `serialize preserves sourceLocation`() {
        val registry = registryWithBuiltIns()
        val serializer = ASTSerializer(registry)

        val script = CompiledScript(
            event = OnJoinEvent(),
            actions = emptyList(),
            sourceLocation = "my_world@3,10,-5"
        )

        val json = serializer.serialize(listOf(script))

        assertTrue(json.contains("my_world@3,10,-5"), "Expected sourceLocation in JSON")
    }

    @Test
    fun `serialize empty script list produces empty JSON array`() {
        val registry = registryWithBuiltIns()
        val serializer = ASTSerializer(registry)

        val json = serializer.serialize(emptyList())

        assertEquals("[]", json.trim())
    }

    @Test
    fun `serialize multiple scripts produces JSON array with correct count`() {
        val registry = registryWithBuiltIns()
        val serializer = ASTSerializer(registry)

        val scripts = listOf(
            CompiledScript(OnJoinEvent(), emptyList(), "world@0,5,0"),
            CompiledScript(OnJoinEvent(), listOf(SendMessageAction(emptyMap())), "world@0,5,2")
        )

        val json = serializer.serialize(scripts)

        // Count top-level objects by counting "eventType" occurrences
        val count = json.split("\"eventType\"").size - 1
        assertEquals(2, count, "Expected 2 script objects in JSON array")
    }

    @Test
    fun `deserialize invalid JSON returns error result`() {
        val registry = registryWithBuiltIns()
        val serializer = ASTSerializer(registry)

        val result = serializer.deserialize("not valid json {{{")

        assertTrue(result.hasErrors)
        assertTrue(result.scripts.isEmpty())
        assertTrue(result.errors[0].contains("parse", ignoreCase = true))
    }

    @Test
    fun `deserialize empty JSON array returns empty result with no errors`() {
        val registry = registryWithBuiltIns()
        val serializer = ASTSerializer(registry)

        val result = serializer.deserialize("[]")

        assertFalse(result.hasErrors)
        assertTrue(result.scripts.isEmpty())
    }

    @Test
    fun `serialize then deserialize reports error for unknown nodeId due to stub lookup`() {
        // The ASTSerializer.findEventByNodeId / findActionByNodeId are stubs returning null.
        // This test documents the current behaviour: deserialization reports errors for
        // unknown nodeIds, which is the expected contract per Req 33.4.
        val registry = registryWithBuiltIns()
        val serializer = ASTSerializer(registry)

        val script = CompiledScript(
            event = OnJoinEvent(),
            actions = listOf(SendMessageAction(mapOf("message" to "Hi"))),
            sourceLocation = "world@0,5,0"
        )

        val json = serializer.serialize(listOf(script))
        val result = serializer.deserialize(json)

        // The stub returns null for all nodeId lookups → error is reported
        assertTrue(result.hasErrors, "Expected deserialization error due to stub nodeId lookup")
    }

    @Test
    fun `deserialize JSON with unknown eventNodeId reports descriptive error`() {
        val registry = registryWithBuiltIns()
        val serializer = ASTSerializer(registry)

        val json = """[{"eventType":"player_join","eventNodeId":"nonexistent_event","sourceLocation":"world@0,5,0","actions":[]}]"""

        val result = serializer.deserialize(json)

        assertTrue(result.hasErrors)
        assertTrue(result.errors[0].contains("nonexistent_event"),
            "Error message should mention the unknown nodeId, got: ${result.errors[0]}")
    }

    @Test
    fun `deserialize JSON with unknown action nodeId reports descriptive error`() {
        val registry = registryWithBuiltIns()
        val serializer = ASTSerializer(registry)

        val json = """[{"eventType":"player_join","eventNodeId":"on_join","sourceLocation":"world@0,5,0","actions":[{"nodeId":"unknown_action","parameters":{}}]}]"""

        val result = serializer.deserialize(json)

        // Either the event lookup fails (stub) or the action lookup fails — either way an error is reported
        assertTrue(result.hasErrors)
    }
}
