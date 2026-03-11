package com.opencreativeplus.core.database

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for MongoConnectionManager.
 * Tests connection retry logic with simulated failures.
 * 
 * Requirements: 17.5, 17.6, 38.2
 */
class MongoConnectionManagerTest {
    
    private lateinit var config: DatabaseConfig
    private lateinit var connectionManager: MongoConnectionManager
    
    @BeforeEach
    fun setup() {
        config = DatabaseConfig(
            connectionString = "mongodb://localhost:27017",
            databaseName = "test_db",
            maxRetries = 3,
            retryDelayMs = 100 // Short delay for testing
        )
        connectionManager = MongoConnectionManager(config)
    }
    
    @AfterEach
    fun teardown() {
        connectionManager.close()
    }
    
    @Test
    fun `test withRetry succeeds on first attempt`() = runBlocking {
        // Given: A successful operation
        var executionCount = 0
        val operation: suspend () -> String = {
            executionCount++
            "success"
        }
        
        // When: Executing with retry
        val result = connectionManager.withRetry(block = operation)
        
        // Then: Operation succeeds on first attempt
        assertEquals("success", result)
        assertEquals(1, executionCount)
    }
    
    @Test
    fun `test withRetry succeeds after one failure`() = runBlocking {
        // Given: An operation that fails once then succeeds
        var executionCount = 0
        val operation: suspend () -> String = {
            executionCount++
            if (executionCount == 1) {
                throw RuntimeException("Simulated failure")
            }
            "success"
        }
        
        // When: Executing with retry
        val result = connectionManager.withRetry(block = operation)
        
        // Then: Operation succeeds after retry
        assertEquals("success", result)
        assertEquals(2, executionCount)
    }
    
    @Test
    fun `test withRetry succeeds after two failures`() = runBlocking {
        // Given: An operation that fails twice then succeeds
        var executionCount = 0
        val operation: suspend () -> String = {
            executionCount++
            if (executionCount <= 2) {
                throw RuntimeException("Simulated failure $executionCount")
            }
            "success"
        }
        
        // When: Executing with retry
        val result = connectionManager.withRetry(block = operation)
        
        // Then: Operation succeeds after two retries
        assertEquals("success", result)
        assertEquals(3, executionCount)
    }
    
    @Test
    fun `test withRetry fails after max retries`() = runBlocking {
        // Given: An operation that always fails
        var executionCount = 0
        val operation: suspend () -> String = {
            executionCount++
            throw RuntimeException("Simulated failure $executionCount")
        }
        
        // When/Then: Executing with retry throws exception after max retries
        val exception = assertThrows<RuntimeException> {
            connectionManager.withRetry(block = operation)
        }
        
        // Verify: All retry attempts were made
        assertEquals(3, executionCount)
        assertEquals("Simulated failure 3", exception.message)
    }
    
    @Test
    fun `test withRetry respects custom maxRetries`() = runBlocking {
        // Given: An operation that always fails and custom retry count
        var executionCount = 0
        val operation: suspend () -> String = {
            executionCount++
            throw RuntimeException("Simulated failure")
        }
        
        // When/Then: Executing with custom retry count
        assertThrows<RuntimeException> {
            connectionManager.withRetry(maxRetries = 5, block = operation)
        }
        
        // Verify: Custom retry count was used
        assertEquals(5, executionCount)
    }
    
    @Test
    fun `test withRetry applies exponential backoff`() = runBlocking {
        // Given: An operation that tracks timing
        val executionTimes = mutableListOf<Long>()
        val operation: suspend () -> String = {
            executionTimes.add(System.currentTimeMillis())
            if (executionTimes.size < 3) {
                throw RuntimeException("Simulated failure")
            }
            "success"
        }
        
        // When: Executing with retry
        connectionManager.withRetry(block = operation)
        
        // Then: Verify exponential backoff (delays should increase)
        assertTrue(executionTimes.size == 3)
        val delay1 = executionTimes[1] - executionTimes[0]
        val delay2 = executionTimes[2] - executionTimes[1]
        
        // First delay should be ~100ms, second ~200ms (with some tolerance)
        assertTrue(delay1 >= 80, "First delay was $delay1ms, expected ~100ms")
        assertTrue(delay2 >= 180, "Second delay was $delay2ms, expected ~200ms")
        assertTrue(delay2 > delay1, "Second delay should be longer than first")
    }
    
    @Test
    fun `test isConnected returns false initially`() {
        // Given: A new connection manager
        // When/Then: isConnected should return false
        assertFalse(connectionManager.isConnected())
    }
    
    @Test
    fun `test getDatabase throws when not connected`() {
        // Given: A connection manager that hasn't connected
        // When/Then: getDatabase should throw IllegalStateException
        val exception = assertThrows<IllegalStateException> {
            connectionManager.getDatabase()
        }
        
        assertTrue(exception.message!!.contains("not connected"))
    }
    
    @Test
    fun `test close clears connection state`() = runBlocking {
        // Note: This test doesn't actually connect to MongoDB
        // It just verifies the close method can be called safely
        
        // Given: A connection manager
        // When: Closing the connection
        connectionManager.close()
        
        // Then: isConnected should return false
        assertFalse(connectionManager.isConnected())
    }
    
    @Test
    fun `test withRetry preserves exception type`() = runBlocking {
        // Given: An operation that throws a specific exception type
        class CustomException(message: String) : Exception(message)
        
        val operation: suspend () -> String = {
            throw CustomException("Custom error")
        }
        
        // When/Then: The original exception type is preserved
        val exception = assertThrows<CustomException> {
            connectionManager.withRetry(block = operation)
        }
        
        assertEquals("Custom error", exception.message)
    }
}
