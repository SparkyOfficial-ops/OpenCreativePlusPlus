package com.opencreativeplus.plugin.logging

import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.opencreativeplus.core.database.MongoConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bson.Document
import java.util.UUID

/**
 * Logs script execution events to the MongoDB execution_logs collection.
 *
 * Requirements: 37.1, 37.2, 37.3, 37.4, 37.5
 */
class ExecutionLogger(
    private val database: MongoDatabase,
    private val connectionManager: MongoConnectionManager
) {
    private val collection = database.getCollection<Document>("execution_logs")

    enum class ExecutionStatus { SUCCESS, ERROR, TERMINATED }

    suspend fun logExecution(
        plotId: UUID,
        eventType: String,
        actionsExecuted: List<String>,
        status: ExecutionStatus,
        errorMessage: String? = null,
        startTime: Long = System.currentTimeMillis(),
        endTime: Long = System.currentTimeMillis()
    ) {
        val doc = Document().apply {
            put("plot_id", plotId.toString())
            put("event_type", eventType)
            put("actions", actionsExecuted)
            put("status", status.name)
            put("error_message", errorMessage)
            put("start_time", startTime)
            put("end_time", endTime)
            put("duration_ms", endTime - startTime)
            put("created_at", java.util.Date(startTime))
        }
        withContext(Dispatchers.IO) {
            connectionManager.withRetry { collection.insertOne(doc) }
        }
    }

    suspend fun getRecentLogs(plotId: UUID, limit: Int = 100): List<Document> {
        return withContext(Dispatchers.IO) {
            connectionManager.withRetry {
                val results = mutableListOf<Document>()
                collection
                    .find(Document("plot_id", plotId.toString()))
                    .sort(Document("start_time", -1))
                    .limit(limit)
                    .collect { results.add(it) }
                results
            }
        }
    }
}
