package com.opencreativeplus.core.execution

import com.mongodb.client.model.ReplaceOptions
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.opencreativeplus.api.execution.VariableScope
import com.opencreativeplus.api.model.VariableChange
import com.opencreativeplus.api.model.VariableScopeType
import com.opencreativeplus.core.serialization.EntityVariableCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bson.Document
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
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
    private val scopeMutexes = ConcurrentHashMap<UUID, Mutex>()

    /**
     * Per-plot execution mutexes.
     *
     * Serializes concurrent script executions that touch the same plot's plotScope.
     * Without this, two players triggering events simultaneously can interleave
     * Read-Modify-Write operations on the same global variable (race condition).
     *
     * Usage in ExecutionEngine: acquire before executing a script, release after.
     * Note: local-scope operations are inherently safe (isolated per execution).
     * Note: for plots with many concurrent scripts this adds latency — acceptable
     * trade-off vs. data corruption on shared variables like player balance.
     */
    private val executionMutexes = ConcurrentHashMap<UUID, Mutex>()

    /**
     * Returns the per-plot execution [Mutex] for [plotId].
     * Used by ExecutionEngine to serialize script executions on the same plot.
     */
    fun getExecutionMutex(plotId: UUID): Mutex =
        executionMutexes.getOrPut(plotId) { Mutex() }

    /** Shared flow that emits every variable change across all plots. */
    private val _changes = MutableSharedFlow<VariableChange>(extraBufferCapacity = 64)

    /**
     * Returns a [Flow] of [VariableChange] events for the given plot.
     * ReactiveGUI instances subscribe to this to react to variable mutations.
     *
     * s: 11.1, 11.2
     */
    fun changes(plotId: UUID): Flow<VariableChange> =
        _changes.asSharedFlow().filter { it.plotId == plotId }

    /**
     * Emit a variable change event. Call this whenever a plot/saved variable is mutated.
     *
     * @param plotId  The plot whose variable changed
     * @param name    Variable name
     * @param value   New value
     * @param scope   Which scope the variable belongs to
     */
    suspend fun emitChange(plotId: UUID, name: String, value: Any?, scope: VariableScopeType) {
        _changes.emit(VariableChange(plotId, name, value, scope))
    }

    /**
     * Resolves a variable name to its storage key.
     *
     * - Names starting with `%player%_` are player-scoped: stored as `"$playerName::$varName"`,
     *   where `varName` is the name with the `%player%_` prefix removed.
     * - All other names are plot-scoped globals: stored as-is.
     * - If [playerName] is `null` and [name] starts with `%player%_`, falls back to the raw name.
     *
     * @param name       The variable name as declared in the script.
     * @param playerName The name of the player in context, or `null` if not player-scoped.
     * @return The resolved storage key.
     */
    fun resolveVariableKey(name: String, playerName: String?): String =
        if (name.startsWith("%player%_") && playerName != null)
            "${playerName}::${name.removePrefix("%player%_")}"
        else name

    /**
     * Create a new local scope for a single script execution.
     * Local scopes are not shared and are cleared after execution.
     */
    fun createLocalScope(): VariableScope {
        return VariableScopeImpl()
    }
    
    /**
     * Get the plot scope for a specific plot.
     * Plot scopes are shared across all players on the plot but not persisted.
     * 
     * @param plotId The plot UUID
     * @return The plot scope
     */
    fun getPlotScope(plotId: UUID): VariableScope {
        return plotScopes.getOrPut(plotId) { VariableScopeImpl() }
    }
    
    /**
     * Get the saved scope for a specific plot.
     * Saved scopes persist across server restarts.
     * Loads from database on first access.
     *
     * Uses double-checked locking with a per-plot Mutex to prevent race conditions:
     * concurrent callers for the same plotId will only trigger one DB load.
     *
     * @param plotId The plot UUID
     * @return The saved scope
     */
    suspend fun getSavedScope(plotId: UUID): VariableScope {
        // Fast path: already cached
        savedScopes[plotId]?.let { return it }
        // Slow path: acquire per-plot mutex and double-check
        val mutex = scopeMutexes.getOrPut(plotId) { Mutex() }
        return mutex.withLock {
            savedScopes.getOrPut(plotId) { loadSavedScope(plotId) }
        }
    }
    
    /**
     * Save plot variables to MongoDB.
     * Uses write-behind caching strategy with upsert.
     * 
     * @param plotId The plot UUID
     */
    suspend fun savePlotVariables(plotId: UUID) {
        val scope = savedScopes[plotId] ?: return
        
        val collection = database.getCollection<Document>("plot_variables")
        // gameready-enhancements Req 2.1, 2.2: PlayerVariable/EntityVariable are
        // encoded as { "__type": ..., "uuid": ... } documents before persisting.
        val variablesDoc = Document(
            (scope as VariableScopeImpl).toMap().mapValues { (_, v) -> EntityVariableCodec.encode(v) }
        )
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
    
    /**
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
            // gameready-enhancements Req 2.1, 2.2: decode PlayerVariable/EntityVariable
            // documents back into their UUID wrappers; other values pass through.
            EntityVariableCodec.decode(value)?.let { scope.set(key, it) }
        }
        
        return scope
    }
    
    /**
     * Clear the plot scope for a specific plot.
     * Does not affect saved scope.
     * 
     * @param plotId The plot UUID
     */
    fun clearPlotScope(plotId: UUID) {
        plotScopes[plotId]?.clear()
    }
    
    /**
     * Remove plot scope from memory.
     * Used when a plot is unloaded.
     * 
     * @param plotId The plot UUID
     */
    fun removePlotScope(plotId: UUID) {
        plotScopes.remove(plotId)
    }
    
    /**
     * Remove saved scope from memory cache.
     * Does not delete from database.
     * 
     * @param plotId The plot UUID
     */
    fun removeSavedScope(plotId: UUID) {
        savedScopes.remove(plotId)
    }
}
