package com.opencreativeplus.plugin.compiler

import com.opencreativeplus.core.execution.CompiledScript

/
 * Result of compiling a set of CodeLines.
 5.1, 5.2, 5.3, 23.4
 */
data class CompilationResult(
    val scripts: List<CompiledScript>,
    val errors: List<CompilationError>
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
}
