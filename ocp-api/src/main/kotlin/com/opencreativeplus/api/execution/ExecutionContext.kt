package com.opencreativeplus.api.execution

import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/
 * Execution context for script execution.
 * Contains all runtime state including variables, player reference, and event data.
 */
interface ExecutionContext {
    /
     * The UUID of the plot where this script is executing
     */
    val plotId: UUID
    
    /
     * The player associated with this execution (may be null for non-player events)
     */
    val player: Player?
    
    /
     * Event data passed from the triggering Minecraft event
     */
    val eventData: Map<String, Any>
    
    /
     * Local scope variables (cleared after execution completes)
     */
    val localScope: VariableScope
    
    /
     * Plot scope variables (shared across all players on the plot)
     */
    val plotScope: VariableScope
    
    /
     * Saved scope variables (persisted across server restarts)
     */
    val savedScope: VariableScope
    
    /
     * Counter for operations performed (used by watchdog)
     */
    val operationCount: AtomicInteger
    
    /
     * Execute a block of code on the Bukkit main thread.
     * Used for operations that require sync context (e.g., teleportation, world manipulation).
     *
     * @param block The code to execute on the main thread
     * @return The result of the block execution
     */
    suspend fun <T> syncContext(block: () -> T): T
}
