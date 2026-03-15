package com.opencreativeplus.plugin.compiler

/**
 * Thrown internally during compilation of a single CodeLine.
 * Requirements: 5.3, 23.1, 23.2, 23.3
 */
class CompilationException(message: String) : Exception(message)
