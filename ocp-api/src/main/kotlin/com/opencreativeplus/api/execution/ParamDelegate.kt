package com.opencreativeplus.api.execution

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/**
 * Thrown when a parameter value cannot be cast to the expected type.
 */
class ParameterTypeMismatchException(
    paramName: String,
    expected: KClass<*>,
    actual: KClass<*>
) : RuntimeException(
    "Parameter '$paramName': expected ${expected.simpleName}, got ${actual.simpleName}"
)

/**
 * A read-only property delegate that resolves a typed parameter from an [ExecutionContext].
 *
 * Lookup order: [ExecutionContext.eventData] first, then [ExecutionContext.localScope].
 *
 * @param context The execution context to read from.
 * @param name    The parameter name to look up.
 * @param type    The expected Kotlin class of the value.
 * @param optional When true, returns null instead of throwing when the key is absent.
 */
class ParamDelegate<T : Any>(
    private val context: ExecutionContext,
    private val name: String,
    private val type: KClass<T>,
    private val optional: Boolean = false
) : ReadOnlyProperty<Any?, T?> {

    override fun getValue(thisRef: Any?, property: KProperty<*>): T? {
        val raw: Any? = context.eventData[name] ?: context.localScope.get(name)

        if (raw == null) {
            if (optional) return null
            throw ParameterTypeMismatchException(name, type, Nothing::class)
        }

        @Suppress("UNCHECKED_CAST")
        return if (type.isInstance(raw)) raw as T
        else throw ParameterTypeMismatchException(name, type, raw::class)
    }
}

// ---------------------------------------------------------------------------
// Inline extension factories
// ---------------------------------------------------------------------------

inline fun <reified T : Any> ExecutionContext.param(name: String): ParamDelegate<T> =
    ParamDelegate(this, name, T::class, optional = false)

inline fun <reified T : Any> ExecutionContext.optionalParam(name: String): ParamDelegate<T> =
    ParamDelegate(this, name, T::class, optional = true)

// ---------------------------------------------------------------------------
// Convenience delegates
// ---------------------------------------------------------------------------

fun ExecutionContext.stringParam(name: String) = param<String>(name)
fun ExecutionContext.intParam(name: String) = param<Int>(name)
fun ExecutionContext.doubleParam(name: String) = param<Double>(name)
fun ExecutionContext.booleanParam(name: String) = param<Boolean>(name)
fun ExecutionContext.locationParam(name: String) = param<org.bukkit.Location>(name)
fun ExecutionContext.playerParam(name: String) = param<org.bukkit.entity.Player>(name)
