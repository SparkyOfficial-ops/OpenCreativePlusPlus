package com.opencreativeplus.core.execution

import com.mongodb.client.model.ReplaceOptions
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.opencreativeplus.api.execution.VariableScope
import kotlinx.coroutines.flow.firstOrNull
import org.bson.Document
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/
 * Manages variable scopes at three levels: local, plot, and saved.
 * 
 * Variable resolution order: local → plot → saved
 * 
 * - Local scope: exists only during a single script execution
 * - Plot scope: shared across all players on a plot (not persisted)
 * - Saved scope: persists across server restarts (stored in MongoDB)
 * 
 9.1, 9.2, 9.3, 9.4, 9.5, 9.6
 */
class VariableManager(private val database: MongoDatabase) {
    
    private val plotScopes = ConcurrentHashMap<UUID, VariableScope>()
    private val savedScopes = ConcurrentHashMap<UUID, VariableScope>()
    
    /
     * Create a new local scope for a single script execution.
     * Local scopes are not shared and are cleared after execution.
     */
    fun createLocalScope(): VariableScope {
        return VariableScopeImpl()
    }
    
    /
     * Get the plot scope for a specific plot.
     * Plot scopes are shared across all players on the plot but not persisted.
     * 
     * @param plotId The plot UUID
     * @return The plot scope
     */
    fun getPlotScope(plotId: UUID): VariableScope {
        return plotScopes.getOrPut(plotId) { VariableScopeImpl() }
    }
    
    /
     * Get the saved scope for a specific plot.
     * Saved scopes persist across server restarts.
     * Loads from database on first access.
     * 
     * @param plotId The plot UUID
     * @return The saved scope
     */
    suspend fun getSavedScope(plotId: UUID): VariableScope {
        return savedScopes.getOrPut(plotId) {
            loadSavedScope(plotId)
        }
    }
    
    /
     * Save plot variables to MongoDB.
     * Uses write-behind caching strategy with upsert.
     * 
     * @param plotId The plot UUID
     */
    suspend fun savePlotVariables(plotId: UUID) {
        val scope = savedScopes[plotId] ?: return
        
        val collection = database.getCollection<Document>("plot_variables")
        val variablesDoc = Document((scope as VariableScopeImpl).toMap())
        val document = Document().apply {
            put("_id", plotId.toString())
            put("variables", variablesDoc)
            put("updated_at", System.currentTimeMillis())
        }
        
        collection.replaceOne(
            Document("_id", plotId.toString()),
            document,
            ReplaceOptions().upsert(true)
        )
    }
    
    /
     * Load saved scope variables from MongoDB.
     * 
     * @param plotId The plot UUID
     * @return A VariableScope with loaded variables
     */
    private suspend fun loadSavedScope(plotId: UUID): VariableScope {
        val collection = database.getCollection<Document>("plot_variables")
        val document = collection.find(Document("_id", plotId.toString())).firstOrNull()
        
        val scope = VariableScopeImpl()
        document?.get("variables", Document::class.java)?.forEach { key, value ->
            scope.set(key, value)
        }
        
        return scope
    }
    
    /
     * Clear the plot scope for a specific plot.
     * Does not affect saved scope.
     * 
     * @param plotId The plot UUID
     */
    fun clearPlotScope(plotId: UUID) {
        plotScopes[plotId]?.clear()
    }
    
    /
     * Remove plot scope from memory.
     * Used when a plot is unloaded.
     * 
     * @param plotId The plot UUID
     */
    fun removePlotScope(plotId: UUID) {
        plotScopes.remove(plotId)
    }
    
    /
     * Remove saved scope from memory cache.
     * Does not delete from database.
     * 
     * @param plotId The plot UUID
     */
    fun removeSavedScope(plotId: UUID) {
        savedScopes.remove(plotId)
    }
}
