package com.opencreativeplus.plugin.compiler

import com.opencreativeplus.api.registry.NodeRegistry
import com.opencreativeplus.core.execution.CompiledScript
import com.opencreativeplus.plugin.scanner.CodeLine
import org.bukkit.Location

/**
 * Compiles a list of [CodeLine]s into [CompiledScript]s.
 *
 * Each CodeLine must start with an event block followed by zero or more action blocks.
 * Compilation errors are collected and returned in [CompilationResult] rather than thrown,
 * so all errors can be reported at once.
 *
 * Piston System (Requirements 2.3, 2.4, 2.5):
 * - [CodeLine.children] are compiled into [CompiledScript.conditionalBranches]
 * - The branch at index i corresponds to the condition node at actions[i-1] (offset by 1 for event)
 *
 5.1, 5.2, 5.3, 5.4, 5.5, 23.1, 23.2, 23.3, 23.4, 23.5
 */
class ASTCompiler(private val nodeRegistry: NodeRegistry) {

    /**
     * Compile all [codeLines] and return a [CompilationResult] containing
     * successfully compiled scripts and any errors encountered.
     5.1, 5.2, 5.3, 23.4
     */
    fun compile(codeLines: List<CodeLine>): CompilationResult {
        val errors = mutableListOf<CompilationError>()
        val scripts = mutableListOf<CompiledScript>()

        for (codeLine in codeLines) {
            // Collect error for empty lines (no blocks placed yet)
            if (codeLine.nodes.isEmpty()) {
                errors.add(CompilationError(codeLine.startLocation, "Code line has no blocks"))
                continue
            }
            try {
                scripts.add(compileCodeLine(codeLine))
            } catch (e: CompilationException) {
                errors.add(CompilationError(codeLine.startLocation, e.message ?: "Unknown error"))
            }
        }

        return CompilationResult(scripts, errors)
    }

    /**
     * Compile a single [CodeLine] into a [CompiledScript].
     * Throws [CompilationException] on any error.
     *
     * [CodeLine.children] are compiled into [CompiledScript.conditionalBranches]:
     * child[i] → conditionalBranches[actionIndex] where actionIndex is the index of the
     * corresponding condition node in the actions list (0-based, after the event node).
     *
     5.1, 5.2, 23.1, 23.2, 23.3, 2.3, 2.4, 2.5
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

        // Compile child branches into conditionalBranches (Piston System, Req 2.3, 2.4, 2.5)
        // CodeLine.children[i] corresponds to the (i+1)-th action node (index i in actions list,
        // since actions[0] is the first action after the event node).
        val conditionalBranches = mutableMapOf<Int, List<com.opencreativeplus.api.node.IAction>>()
        for ((childIndex, childCodeLine) in codeLine.children.withIndex()) {
            if (childIndex < actions.size) {
                val childActions = compileChildActions(childCodeLine)
                conditionalBranches[childIndex] = childActions
            }
        }

        val locationStr = locationString(codeLine.startLocation)
        return CompiledScript(
            event = event,
            actions = actions,
            sourceLocation = locationStr,
            conditionalBranches = conditionalBranches
        )
    }

    /**
     * Compile all nodes in a child [CodeLine] as actions (no event node expected).
     * Used for piston-scoped child branches (Req 2.3).
     */
    private fun compileChildActions(childCodeLine: CodeLine): List<com.opencreativeplus.api.node.IAction> {
        val childActions = mutableListOf<com.opencreativeplus.api.node.IAction>()
        for (node in childCodeLine.nodes) {
            val actionFactory = nodeRegistry.getActionFactory(node.blockType) ?: continue
            try {
                childActions.add(actionFactory(node.parameters))
            } catch (e: Exception) {
                // Skip invalid child nodes — don't fail the whole compilation
            }
        }
        return childActions
    }

    private fun locationString(loc: Location): String =
        "${loc.world?.name ?: "unknown"}@${loc.blockX},${loc.blockY},${loc.blockZ}"
}
