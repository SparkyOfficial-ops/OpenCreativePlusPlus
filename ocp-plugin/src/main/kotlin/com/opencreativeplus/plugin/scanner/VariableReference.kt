package com.opencreativeplus.plugin.scanner

/**
 * Represents a variable reference parsed from sign text (e.g. "$varname").
 * Requirements: 19.4
 */
data class VariableReference(val name: String)
