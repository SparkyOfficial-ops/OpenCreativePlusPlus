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
 * Loop Body (Requirements 2.1, 2.4 — ocp-plugin-fixes-and-completions):
 * - For loop nodes ("foreach", "repeat"), the corresponding child CodeLine is compiled as
 *   the "body" parameter and the child is excluded from conditionalBranches.
 *
 5.1, 5.2, 5.3, 5.4, 5.5, 23.1, 23.2, 23.3, 23.4, 23.5
 */
class ASTCompiler(private val nodeRegistry: NodeRegistry) {

    companion object {
        private val LOOP_NODE_IDS = setOf("foreach", "repeat")
    }

    /**
     * Compile all [codeLines] and return a [CompilationResult] containing
     * successfully compiled scripts and any errors encountered.
     5.1, 5.2, 5.3, 23.4
     */
    fun compile(codeLines: List<CodeLine>): CompilationResult {
        val errors = mutableListOf<CompilationError>()
        val scripts = mutableListOf<CompiledScript>()

        for (codeLine in codeLines) {
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
     * [CodeLine.children] are compiled into either:
     * - "body" param of the corresponding loop node ("foreach"/"repeat"), or
     * - [CompiledScript.conditionalBranches] for condition/piston nodes.
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

        // Compile remaining nodes as actions (req 5.2, 23.1, 23.3).
        // Track which child indices are consumed as loop bodies.
        val actions = mutableListOf<com.opencreativeplus.api.node.IAction>()
        val actionLocations = mutableListOf<Location>()
        val actionParams = mutableListOf<Map<String, Any>>()
        val loopBodyChildIndices = mutableSetOf<Int>()

        for (i in 1 until codeLine.nodes.size) {
            val node = codeLine.nodes[i]
            val actionIndex = i - 1 // 0-based index in the actions list

            val actionFactory = node.nodeId?.let { nodeRegistry.getActionFactoryById(it) }
                ?: nodeRegistry.getActionFactory(node.blockType)
                ?: throw CompilationException(
                    "Unknown action block: ${node.blockType}${node.nodeId?.let { " (nodeId=$it)" } ?: ""} at ${locationString(node.location)}"
                )

            try {
                // Create the action with base params first to discover its nodeId
                val baseAction = actionFactory(node.parameters)

                if (baseAction.nodeId in LOOP_NODE_IDS) {
                    // Compile the corresponding child CodeLine as the loop body (Req 2.1, 2.4)
                    val bodyActions = codeLine.children.getOrNull(actionIndex)
                        ?.let { compileChildActions(it) }
                        ?: emptyList()
                    loopBodyChildIndices.add(actionIndex)
                    // Recreate the node with "body" injected into params
                    actions.add(actionFactory(node.parameters + mapOf("body" to bodyActions)))
                } else {
                    actions.add(baseAction)
                }
                // Trace Mode metadata: source block location + scanned params (s: 14.2, 14.3)
                actionLocations.add(node.location)
                actionParams.add(node.parameters)
            } catch (e: CompilationException) {
                throw e
            } catch (e: Exception) {
                throw CompilationException(
                    "Invalid parameters for ${node.blockType} at ${locationString(node.location)}: ${e.message}"
                )
            }
        }

        // Compile remaining child branches into conditionalBranches (Piston System, Req 2.3, 2.4, 2.5).
        // Children consumed as loop bodies are excluded.
        val conditionalBranches = mutableMapOf<Int, List<com.opencreativeplus.api.node.IAction>>()
        val elseBranches = mutableMapOf<Int, List<com.opencreativeplus.api.node.IAction>>()
        for ((childIndex, childCodeLine) in codeLine.children.withIndex()) {
            if (childIndex < actions.size && childIndex !in loopBodyChildIndices) {
                conditionalBranches[childIndex] = compileChildActions(childCodeLine)
                // Compile else-branch if present (Req 4.3, 4.4)
                if (childCodeLine.elseActions.isNotEmpty()) {
                    val elseCodeLine = CodeLine(
                        startLocation = childCodeLine.startLocation,
                        nodes = childCodeLine.elseActions
                    )
                    elseBranches[childIndex] = compileChildActions(elseCodeLine)
                }
            }
        }

        val locationStr = locationString(codeLine.startLocation)
        return CompiledScript(
            event = event,
            actions = actions,
            sourceLocation = locationStr,
            conditionalBranches = conditionalBranches,
            elseBranches = elseBranches,
            actionLocations = actionLocations,
            actionParams = actionParams
        )
    }

    /**
     * Compile all nodes in a child [CodeLine] as actions (no event node expected).
     * Used for loop bodies and piston-scoped child branches (Req 2.1, 2.3).
     */
    private fun compileChildActions(childCodeLine: CodeLine): List<com.opencreativeplus.api.node.IAction> {
        val childActions = mutableListOf<com.opencreativeplus.api.node.IAction>()
        for (node in childCodeLine.nodes) {
            val actionFactory = node.nodeId?.let { nodeRegistry.getActionFactoryById(it) }
                ?: nodeRegistry.getActionFactory(node.blockType)
                ?: continue
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
