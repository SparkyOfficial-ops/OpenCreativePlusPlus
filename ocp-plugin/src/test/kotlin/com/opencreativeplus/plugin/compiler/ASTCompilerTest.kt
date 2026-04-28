package com.opencreativeplus.plugin.compiler

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
 5.3, 23.5, 33.5
 */
class ASTCompilerTest {

    companion object {
        private val registry: NodeRegistryImpl by lazy {
            NodeRegistryImpl().also { r ->
                BuiltInNodeRegistry.register(r)
                BuiltInNodeRegistry.registerPluginActions(r, mockk(relaxed = true))
            }
        }
    }

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

    @Test
    fun `compile single event-only code line produces script with no actions`() {
        val compiler = ASTCompiler(registry)
        val loc = mockLocation()
        val codeLine = CodeLine(startLocation = loc, nodes = listOf(ScannedNode(Material.DIAMOND_BLOCK, loc, emptyMap())))
        val result = compiler.compile(listOf(codeLine))
        assertFalse(result.hasErrors, "Expected no errors but got: ${result.errors}")
        assertEquals(1, result.scripts.size)
        assertEquals("player_join", result.scripts[0].event.eventType)
        assertTrue(result.scripts[0].actions.isEmpty())
    }

    @Test
    fun `compile event followed by multiple actions preserves order`() {
        val compiler = ASTCompiler(registry)
        val loc = mockLocation()
        val codeLine = CodeLine(startLocation = loc, nodes = listOf(
            ScannedNode(Material.DIAMOND_BLOCK, loc, emptyMap()),
            ScannedNode(Material.PAPER, loc, mapOf("message" to "First")),
            ScannedNode(Material.CLOCK, loc, mapOf("duration" to 10)),
            ScannedNode(Material.PAPER, loc, mapOf("message" to "Second"))
        ))
        val result = compiler.compile(listOf(codeLine))
        assertFalse(result.hasErrors)
        assertEquals(3, result.scripts[0].actions.size)
        assertEquals("send_message", result.scripts[0].actions[0].nodeId)
        assertEquals("wait", result.scripts[0].actions[1].nodeId)
        assertEquals("send_message", result.scripts[0].actions[2].nodeId)
    }

    @Test
    fun `compiled script sourceLocation encodes world and coordinates`() {
        val compiler = ASTCompiler(registry)
        val loc = mockLocation(worldName = "dev_world", x = 3, y = 10, z = -5)
        val codeLine = CodeLine(startLocation = loc, nodes = listOf(ScannedNode(Material.DIAMOND_BLOCK, loc, emptyMap())))
        val result = compiler.compile(listOf(codeLine))
        assertFalse(result.hasErrors)
        val sourceLocation = result.scripts[0].sourceLocation
        assertTrue(sourceLocation.contains("dev_world"))
        assertTrue(sourceLocation.contains("3"))
        assertTrue(sourceLocation.contains("10"))
        assertTrue(sourceLocation.contains("-5"))
    }

    @Test
    fun `compile code line with no nodes produces error`() {
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
        val compiler = ASTCompiler(registry)
        val loc = mockLocation()
        val codeLine = CodeLine(startLocation = loc, nodes = listOf(ScannedNode(Material.PAPER, loc, mapOf("message" to "Hi"))))
        val result = compiler.compile(listOf(codeLine))
        assertTrue(result.hasErrors)
        assertTrue(result.scripts.isEmpty())
        assertTrue(result.errors[0].message.contains("event", ignoreCase = true))
    }

    @Test
    fun `compile code line with unknown action block produces error`() {
        val compiler = ASTCompiler(registry)
        val loc = mockLocation()
        val codeLine = CodeLine(startLocation = loc, nodes = listOf(
            ScannedNode(Material.DIAMOND_BLOCK, loc, emptyMap()),
            ScannedNode(Material.STONE, loc, emptyMap())
        ))
        val result = compiler.compile(listOf(codeLine))
        assertTrue(result.hasErrors)
        assertTrue(result.scripts.isEmpty())
        assertTrue(result.errors[0].message.contains("STONE", ignoreCase = true))
    }

    @Test
    fun `compile collects errors from all invalid code lines without stopping`() {
        val compiler = ASTCompiler(registry)
        val loc = mockLocation()
        val codeLines = listOf(
            CodeLine(loc, emptyList()),
            CodeLine(loc, listOf(ScannedNode(Material.PAPER, loc, emptyMap()))),
            CodeLine(loc, listOf(ScannedNode(Material.STONE, loc, emptyMap())))
        )
        val result = compiler.compile(codeLines)
        assertEquals(3, result.errors.size)
        assertTrue(result.scripts.isEmpty())
    }

    @Test
    fun `compile mixes valid and invalid code lines correctly`() {
        val compiler = ASTCompiler(registry)
        val loc = mockLocation()
        val codeLines = listOf(
            CodeLine(loc, listOf(ScannedNode(Material.DIAMOND_BLOCK, loc, emptyMap()))),
            CodeLine(loc, listOf(ScannedNode(Material.PAPER, loc, emptyMap()))),
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
        val compiler = ASTCompiler(registry)
        val startLoc = mockLocation(worldName = "test_world", x = 7, y = 5, z = 3)
        val codeLine = CodeLine(startLocation = startLoc, nodes = emptyList())
        val result = compiler.compile(listOf(codeLine))
        assertTrue(result.hasErrors)
        assertEquals(startLoc, result.errors[0].location)
    }

    @Test
    fun `compile with unregistered event block type produces error`() {
        val emptyRegistry = NodeRegistryImpl()
        val compiler = ASTCompiler(emptyRegistry)
        val loc = mockLocation()
        val codeLine = CodeLine(startLocation = loc, nodes = listOf(ScannedNode(Material.DIAMOND_BLOCK, loc, emptyMap())))
        val result = compiler.compile(listOf(codeLine))
        assertTrue(result.hasErrors)
        assertTrue(result.scripts.isEmpty())
    }

    @Test
    fun `serialize preserves eventType and eventNodeId`() {
        val serializer = ASTSerializer(registry)
        val script = CompiledScript(event = OnJoinEvent(), actions = emptyList(), sourceLocation = "world@0,5,0")
        val json = serializer.serialize(listOf(script))
        assertTrue(json.isNotBlank())
        assertTrue(json.trimStart().startsWith("["))
        assertTrue(json.contains("player_join"))
        assertTrue(json.contains("on_join"))
    }

    @Test
    fun `serialize preserves sourceLocation`() {
        val serializer = ASTSerializer(registry)
        val script = CompiledScript(event = OnJoinEvent(), actions = emptyList(), sourceLocation = "my_world@3,10,-5")
        val json = serializer.serialize(listOf(script))
        assertTrue(json.contains("my_world@3,10,-5"))
    }

    @Test
    fun `serialize empty script list produces empty JSON array`() {
        val serializer = ASTSerializer(registry)
        val json = serializer.serialize(emptyList())
        assertEquals("[]", json.trim())
    }

    @Test
    fun `deserialize invalid JSON returns error result`() {
        val serializer = ASTSerializer(registry)
        val result = serializer.deserialize("not valid json {{{")
        assertTrue(result.hasErrors)
        assertTrue(result.scripts.isEmpty())
        assertTrue(result.errors[0].contains("parse", ignoreCase = true))
    }

    @Test
    fun `deserialize empty JSON array returns empty result with no errors`() {
        val serializer = ASTSerializer(registry)
        val result = serializer.deserialize("[]")
        assertFalse(result.hasErrors)
        assertTrue(result.scripts.isEmpty())
    }

    @Test
    fun `serialize then deserialize reports error for unknown nodeId due to stub lookup`() {
        val serializer = ASTSerializer(registry)
        val script = CompiledScript(event = OnJoinEvent(), actions = listOf(SendMessageAction(mapOf("message" to "Hi"))), sourceLocation = "world@0,5,0")
        val json = serializer.serialize(listOf(script))
        val result = serializer.deserialize(json)
        assertTrue(result.hasErrors)
    }

    @Test
    fun `deserialize JSON with unknown eventNodeId reports descriptive error`() {
        val serializer = ASTSerializer(registry)
        val json = """[{"eventType":"player_join","eventNodeId":"nonexistent_event","sourceLocation":"world@0,5,0","actions":[]}]"""
        val result = serializer.deserialize(json)
        assertTrue(result.hasErrors)
        assertTrue(result.errors[0].contains("nonexistent_event"))
    }
}
