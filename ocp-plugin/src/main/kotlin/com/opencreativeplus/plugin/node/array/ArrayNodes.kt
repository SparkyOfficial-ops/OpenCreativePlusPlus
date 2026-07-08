package com.opencreativeplus.plugin.node.array

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import com.opencreativeplus.api.node.IValue

/**
 * Creates an empty mutable list and stores it in localScope under params["var"].
 *
 * Params:
 *   - "var": String — name of the variable to store the new list in
 */
class CreateListNode(params: Map<String, Any>) : IAction {
    override val nodeId = "create_list"
    override val displayName = "Create List"

    private val varName: String = params["var"] as? String ?: error("var param required")

    override suspend fun execute(context: ExecutionContext) {
        context.localScope.set(varName, mutableListOf<Any?>())
    }
}

/**
 * Appends a value to the list stored in localScope under params["list"].
 * If the list is immutable, converts it to a mutable list before adding.
 * If params["value"] is a String that matches a localScope variable name, resolves it first.
 *
 * Params:
 *   - "list": String — name of the localScope variable holding the list
 *   - "value": Any — value to append (if String and exists in localScope, resolved to that variable's value)
 */
class AddToListNode(params: Map<String, Any>) : IAction {
    override val nodeId = "add_to_list"
    override val displayName = "Add To List"

    private val listVar: String = params["list"] as? String ?: error("list param required")
    private val rawValue: Any? = params["value"]

    override suspend fun execute(context: ExecutionContext) {
        val existing = context.localScope.get(listVar)
        val resolvedValue = if (rawValue is String && context.localScope.has(rawValue)) {
            context.localScope.get(rawValue)
        } else {
            rawValue
        }

        val mutableList: MutableList<Any?> = when (existing) {
            is MutableList<*> -> @Suppress("UNCHECKED_CAST") (existing as MutableList<Any?>)
            is List<*> -> existing.toMutableList()
            else -> mutableListOf()
        }

        mutableList.add(resolvedValue)
        context.localScope.set(listVar, mutableList)

        // Track heap growth for Watchdog memory limit. Req 29.1
        val valueBytes: Long = when (val v = resolvedValue) {
            is String -> (v.length * 2 + 40).toLong()
            else -> 64L
        }
        context.trackMemory(valueBytes + 8L)
    }
}

/**
 * Returns the size of the list stored in localScope under params["list"].
 * Returns 0 if the variable is absent or not a list.
 *
 * Params:
 *   - "list": String — name of the localScope variable holding the list
 */
class GetListSizeNode(params: Map<String, Any>) : IValue<Int> {
    override val nodeId = "get_list_size"
    override val displayName = "Get List Size"

    private val listVar: String = params["list"] as? String ?: error("list param required")

    override suspend fun compute(context: ExecutionContext): Int {
        val list = context.localScope.get(listVar) as? List<*> ?: return 0
        return list.size
    }
}

/**
 * Returns the element at params["index"] from the list in localScope under params["list"].
 * Returns null (without throwing) if the index is out of bounds or the list doesn't exist.
 *
 * Params:
 *   - "list": String — name of the localScope variable holding the list
 *   - "index": Int — zero-based index of the element to retrieve
 */
class GetListElementNode(params: Map<String, Any>) : IValue<Any?> {
    override val nodeId = "get_list_element"
    override val displayName = "Get List Element"

    private val listVar: String = params["list"] as? String ?: error("list param required")
    private val index: Int = params["index"] as? Int ?: 0

    override suspend fun compute(context: ExecutionContext): Any? {
        val list = context.localScope.get(listVar) as? List<*> ?: run {
            println("[OCP] GetListElementNode: variable '$listVar' is not a list")
            return null
        }
        val result = list.getOrNull(index)
        if (index < 0 || index >= list.size) {
            println("[OCP] GetListElementNode: index $index out of bounds for list '$listVar' (size=${list.size})")
        }
        return result
    }
}

/**
 * Parses a condition string of the form "<variable> <operator> <value>" into a predicate.
 *
 * Supported operators: ==, !=, >, <, >=, <=
 * Numeric operators (>, <, >=, <=) perform numeric comparison when the element is a Number.
 * == and != perform string comparison via toString().
 * Returns null and logs a warning if the format is invalid (not exactly 3 tokens).
 */
object ConditionStringParser {
    fun parse(condition: String): ((Any?) -> Boolean)? {
        val tokens = condition.trim().split("\\s+".toRegex())
        if (tokens.size != 3) {
            println("[OCP] ConditionStringParser: invalid condition format '$condition' (expected 3 tokens, got ${tokens.size})")
            return null
        }
        val (_, operator, value) = tokens
        return { element -> compare(element, operator, value) }
    }

    private fun compare(element: Any?, operator: String, value: String): Boolean {
        val numElement = (element as? Number)?.toDouble()
        val numValue = value.toDoubleOrNull()
        return when (operator) {
            "==" -> element?.toString() == value
            "!=" -> element?.toString() != value
            ">"  -> numElement != null && numValue != null && numElement > numValue
            "<"  -> numElement != null && numValue != null && numElement < numValue
            ">=" -> numElement != null && numValue != null && numElement >= numValue
            "<=" -> numElement != null && numValue != null && numElement <= numValue
            else -> false
        }
    }
}

/**
 * Returns a new list containing only elements matching the condition string in params["condition"].
 * If the condition is absent/blank or has an invalid format, returns the original list unchanged.
 *
 * Params:
 *   - "list": String — name of the localScope variable holding the list
 *   - "condition": String? — condition string in format "<variable> <operator> <value>"
 */
class FilterListNode(params: Map<String, Any>) : IValue<List<Any?>> {
    override val nodeId = "filter_list"
    override val displayName = "Filter List"

    private val listVar: String = params["list"] as? String ?: error("list param required")
    private val conditionStr: String? = params["condition"] as? String

    override suspend fun compute(context: ExecutionContext): List<Any?> {
        val list = context.localScope.get(listVar) as? List<*> ?: return emptyList()
        if (conditionStr.isNullOrBlank()) return list.toList()
        val predicate = ConditionStringParser.parse(conditionStr) ?: run {
            println("[OCP] FilterListNode: invalid condition format '$conditionStr'")
            return list.toList()
        }
        return list.filter { predicate(it) }
    }
}
