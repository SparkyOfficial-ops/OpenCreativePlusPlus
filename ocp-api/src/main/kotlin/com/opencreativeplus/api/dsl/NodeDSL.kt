package com.opencreativeplus.api.dsl

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.execution.ParameterTypeMismatchException
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.ICondition
import com.opencreativeplus.api.registry.NodeRegistry
import org.bukkit.Material
import kotlin.reflect.KClass

@DslMarker
annotation class NodeDslMarker

/
 * Entry point for OCP plugin API. Provides access to the node registry.
 */
interface OcpPluginAPI {
    val nodeRegistry: NodeRegistry
}

@NodeDslMarker
class ActionBuilder(private val registry: NodeRegistry, private val material: Material) {

    private var nodeName: String = material.name
    private var nodeDescription: String = ""
    @PublishedApi internal val requiredParams = mutableListOf<Pair<String, KClass<*>>>()
    private var executionBlock: (suspend ExecutionContext.() -> Unit)? = null

    fun name(n: String) { nodeName = n }
    fun description(d: String) { nodeDescription = d }

    inline fun <reified T> requires(paramName: String) {
        requiredParams.add(paramName to T::class)
    }

    fun execute(block: suspend ExecutionContext.() -> Unit) { executionBlock = block }

    fun build() {
        val block = requireNotNull(executionBlock) {
            "ActionBuilder for ${material.name}: executionBlock must be set via execute { }"
        }
        val capturedParams = requiredParams.toList()
        val capturedName = nodeName

        registry.registerAction(material) { params ->
            object : IAction {
                override val nodeId: String = material.name
                override val displayName: String = capturedName

                override suspend fun execute(context: ExecutionContext) {
                    for ((paramName, paramType) in capturedParams) {
                        val value = params[paramName]
                            ?: throw ParameterTypeMismatchException(paramName, paramType, Nothing::class)
                        if (!paramType.isInstance(value)) {
                            throw ParameterTypeMismatchException(paramName, paramType, value::class)
                        }
                    }
                    context.block()
                }
            }
        }
    }
}

@NodeDslMarker
class ConditionBuilder(private val registry: NodeRegistry, private val material: Material) {

    private var nodeName: String = material.name
    private var nodeDescription: String = ""
    @PublishedApi internal val requiredParams = mutableListOf<Pair<String, KClass<*>>>()
    private var evaluationBlock: (suspend ExecutionContext.() -> Boolean)? = null

    fun name(n: String) { nodeName = n }
    fun description(d: String) { nodeDescription = d }

    inline fun <reified T> requires(paramName: String) {
        requiredParams.add(paramName to T::class)
    }

    fun evaluate(block: suspend ExecutionContext.() -> Boolean) { evaluationBlock = block }

    fun build() {
        val block = requireNotNull(evaluationBlock) {
            "ConditionBuilder for ${material.name}: evaluationBlock must be set via evaluate { }"
        }
        val capturedParams = requiredParams.toList()
        val capturedName = nodeName

        registry.registerCondition(material) { params ->
            object : ICondition {
                override val nodeId: String = material.name
                override val displayName: String = capturedName

                override suspend fun evaluate(context: ExecutionContext): Boolean {
                    for ((paramName, paramType) in capturedParams) {
                        val value = params[paramName]
                            ?: throw ParameterTypeMismatchException(paramName, paramType, Nothing::class)
                        if (!paramType.isInstance(value)) {
                            throw ParameterTypeMismatchException(paramName, paramType, value::class)
                        }
                    }
                    return context.block()
                }
            }
        }
    }
}

class NodeDSL(private val registry: NodeRegistry) {

    fun registerAction(material: Material, init: ActionBuilder.() -> Unit) {
        ActionBuilder(registry, material).apply(init).build()
    }

    fun registerCondition(material: Material, init: ConditionBuilder.() -> Unit) {
        ConditionBuilder(registry, material).apply(init).build()
    }
}

fun OcpPluginAPI.nodes(init: NodeDSL.() -> Unit) {
    NodeDSL(nodeRegistry).apply(init)
}
