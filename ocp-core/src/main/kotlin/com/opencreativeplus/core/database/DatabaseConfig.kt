package com.opencreativeplus.core.database

/**
 * Database configuration loaded from config.yml
 */
data class DatabaseConfig(
    val connectionString: String = "mongodb://localhost:27017",
    val databaseName: String = "opencreative_plus",
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 1000
)
