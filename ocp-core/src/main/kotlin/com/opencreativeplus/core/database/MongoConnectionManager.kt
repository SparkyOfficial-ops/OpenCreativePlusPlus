package com.opencreativeplus.core.database

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.delay
import org.bson.UuidRepresentation
import java.util.concurrent.TimeUnit

/**
 * MongoDB connection manager with connection pooling and retry logic.
 * Implements retry mechanism for failed database operations (up to 3 attempts).
 * 
 * Requirements: 17.1, 17.5
 */
class MongoConnectionManager(private val config: DatabaseConfig) {
    
    private var client: MongoClient? = null
    private var database: MongoDatabase? = null
    
    /**
     * Connect to MongoDB with connection pooling.
     * Loads configuration from DatabaseConfig.
     */
    suspend fun connect() {
        val settings = MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(config.connectionString))
            .uuidRepresentation(UuidRepresentation.STANDARD)
            .applyToConnectionPoolSettings { builder ->
                builder.maxSize(100)
                    .minSize(10)
                    .maxWaitTime(5000, TimeUnit.MILLISECONDS)
                    .maxConnectionIdleTime(60000, TimeUnit.MILLISECONDS)
            }
            .applyToSocketSettings { builder ->
                builder.connectTimeout(5000, TimeUnit.MILLISECONDS)
                    .readTimeout(10000, TimeUnit.MILLISECONDS)
            }
            .build()
        
        client = MongoClient.create(settings)
        database = client!!.getDatabase(config.databaseName)
    }
    
    /**
     * Get the MongoDB database instance.
     * @throws IllegalStateException if not connected
     */
    fun getDatabase(): MongoDatabase {
        return database ?: throw IllegalStateException("Database not connected. Call connect() first.")
    }
    
    /**
     * Close the MongoDB connection and release resources.
     */
    fun close() {
        client?.close()
        client = null
        database = null
    }
    
    /**
     * Execute a database operation with retry logic.
     * Retries up to maxRetries times with exponential backoff on failure.
     * 
     * @param maxRetries Maximum number of retry attempts (default from config)
     * @param block The database operation to execute
     * @return Result of the operation
     * @throws Exception if all retry attempts fail
     */
    suspend fun <T> withRetry(
        maxRetries: Int = config.maxRetries,
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    // Exponential backoff: 1s, 2s, 3s
                    val delayMs = config.retryDelayMs * (attempt + 1)
                    delay(delayMs)
                }
            }
        }
        
        // All retries failed, throw the last exception
        throw lastException!!
    }
    
    /**
     * Check if the connection is active.
     */
    fun isConnected(): Boolean {
        return client != null && database != null
    }
}
