package com.opencreativeplus.plugin.compiler

import com.opencreativeplus.api.registry.NodeRegistry
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.plugin.scanner.CodeLine
import org.bukkit.Location

/
 * Compiles a list of [CodeLine]s into [CompiledScript]s.
 *
 * Each CodeLine must start with an event block followed by zero or more action blocks.
 * Compilation errors are collected and returned in [CompilationResult] rather than thrown,
 * so all errors can be reported at once.
 *
 5.1, 5.2, 5.3, 5.4, 5.5, 23.1, 23.2, 23.3, 23.4, 23.5
 */
class ASTCompiler(private val nodeRegistry: NodeRegistry) {

    /
     * Compile all [codeLines] and return a [CompilationResult] containing
     * successfully compiled scripts and any errors encountered.
     5.1, 5.2, 5.3, 23.4
     */
    fun compile(codeLines: List<CodeLine>): CompilationResult {
        val errors = mutableListOf<CompilationError>()
        val scripts = mutableListOf<CompiledScript>()

        for (codeLine in codeLines) {
            try {
                scripts.add(compileCodeLine(codeLine))
            } catch (e: CompilationException) {
                errors.add(CompilationError(codeLine.startLocation, e.message ?: "Unknown error"))
            }
        }

        return CompilationResult(scripts, errors)
    }

    /
     * Compile a single [CodeLine] into a [CompiledScript].
     * Throws [CompilationException] on any error.
     5.1, 5.2, 23.1, 23.2, 23.3
     */
    private fun compileCodeLine(codeLine: CodeLine): CompiledScript {
        if (codeLine.nodes.isEmpty()) {
            throw CompilationException("Code line at ${locationString(codeLine.startLocation)} has no blocks")
        }

        // First node must be an event block (req 5.1, 23.2)
        val eventNode = codeLine.nodes.first()
        val eventFactory = nodeRegistry.getEventFactory(eventNode.blockType)
            ?: throw CompilationException(
                "First block must be an event trigger, found ${eventNode.blockType} at ${locationString(eventNode.location)}"
            )

        val event = eventFactory()

        // Compile remaining nodes as actions (req 5.2, 23.1, 23.3)
        val actions = mutableListOf<com.opencreativeplus.api.node.IAction>()
        for (i in 1 until codeLine.nodes.size) {
            val node = codeLine.nodes[i]
            val actionFactory = nodeRegistry.getActionFactory(node.blockType)
                ?: throw CompilationException(
                    "Unknown action block: ${node.blockType} at ${locationString(node.location)}"
                )
            try {
                actions.add(actionFactory(node.parameters))
            } catch (e: Exception) {
                throw CompilationException(
                    "Invalid parameters for ${node.blockType} at ${locationString(node.location)}: ${e.message}"
                )
            }
        }

        val locationStr = locationString(codeLine.startLocation)
        return CompiledScript(event = event, actions = actions, sourceLocation = locationStr)
    }

    private fun locationString(loc: Location): String =
        "${loc.world?.name ?: "unknown"}@${loc.blockX},${loc.blockY},${loc.blockZ}"
}
