package com.opencreativeplus.api.model

import java.util.UUID
import kotlin.reflect.KClass

enum class VariableScopeType { LOCAL, PLOT, SAVED }

data class VariableChange(
    val plotId: UUID,
    val name: String,
    val newValue: Any?,
    val scope: VariableScopeType
)

data class ParamSpec(val name: String, val type: KClass<*>)

data class NodeMetadata(
    val nodeId: String,
    val displayName: String,
    val description: String,
    val requiredParams: List<ParamSpec>
)

enum class ItemVariableType {
    PLAYER_REFERENCE,
    LOCATION_REFERENCE,
    ENTITY_REFERENCE,
    NUMBER_REFERENCE,
    TEXT_REFERENCE
}

data class ItemVariableRef(val name: String, val type: ItemVariableType)
