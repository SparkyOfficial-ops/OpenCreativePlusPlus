package com.opencreativeplus.api.node

import com.opencreativeplus.api.execution.ExecutionContext

/
 * Interface for condition nodes that evaluate to true or false.
 * Conditions are used for branching logic in scripts.
 */
interface ICondition : INode {
    /
     * Evaluate this condition within the given execution context.
     *
     * @param context The execution context containing variables and state
     * @return true if the condition is met, false otherwise
     */
    suspend fun evaluate(context: ExecutionContext): Boolean
}
