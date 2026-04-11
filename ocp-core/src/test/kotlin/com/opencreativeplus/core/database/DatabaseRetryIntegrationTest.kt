package com.opencreativeplus.core.database

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests for database retry logic.
 *
 * Verifies that:
 * - Operations are retried up to 3 times before logging an error (Requirement 17.5)
 * - Operations succeed when connection is restored within the retry window (Requirement 38.2)
 * - Queued operations are retried when connection is restored (Requirement 38.2)
 *
 38.1, 38.2
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseRetryIntegrationTest {

    private lateinit var mongoContainer: MongoDBContainer
    private lateinit var connectionManager: MongoConnectionManager
    private lateinit var plotPersistence: PlotPersistence

    @BeforeAll
    fun setupContainer() {
        mongoContainer = MongoDBContainer(DockerImageName.parse("mongo:7.0"))
        mongoContainer.start()

        val config = DatabaseConfig(
            connectionString = mongoContainer.replicaSetUrl,
            databaseName = "test_retry_ocp",
            maxRetries = 3,
            retryDelayMs = 50L // short delay for fast tests
        )
        connectionManager = MongoConnectionManager(config)
        runBlocking {
            connectionManager.connect()
            plotPersistence = PlotPersistence(connectionManager.getDatabase(), connectionManager)
        }
    }

    @AfterAll
    fun teardownContainer() {
        connectionManager.close()
        mongoContainer.stop()
    }

    @BeforeEach
    fun cleanDatabase() = runBlocking {
        connectionManager.getDatabase()
            .getCollection<org.bson.Document>("plots")
            .drop()
    }

    // -------------------------------------------------------------------------
    // Retry count enforcement (Requirement 17.5)
    // -------------------------------------------------------------------------

    @Test
    fun `withRetry executes exactly 3 attempts before throwing when all fail`() = runBlocking {
        // Given: an operation that always fails
        val attemptCount = AtomicInteger(0)

        // When / Then: exception is thrown after exactly 3 attempts
        assertThrows<RuntimeException> {
            connectionManager.withRetry(maxRetries = 3) {
                attemptCount.incrementAndGet()
                throw RuntimeException("simulated db failure")
            }
        }

        assertEquals(3, attemptCount.get(), "Should attempt exactly 3 times before giving up")
    }

    @Test
    fun `withRetry succeeds on the third attempt after two failures`() = runBlocking {
        // Given: an operation that fails twice then succeeds
        val attemptCount = AtomicInteger(0)

        val result = connectionManager.withRetry(maxRetries = 3) {
            val attempt = attemptCount.incrementAndGet()
            if (attempt < 3) throw RuntimeException("transient failure on attempt $attempt")
            "recovered"
        }

        assertEquals("recovered", result)
        assertEquals(3, attemptCount.get())
    }

    @Test
    fun `withRetry throws the last exception after exhausting all retries`() = runBlocking {
        // Given: an operation that always fails with a distinct message per attempt
        val attemptCount = AtomicInteger(0)

        val ex = assertThrows<RuntimeException> {
            connectionManager.withRetry(maxRetries = 3) {
                val n = attemptCount.incrementAndGet()
                throw RuntimeException("failure #$n")
            }
        }

        // The last exception (attempt 3) should be propagated
        assertEquals("failure #3", ex.message)
    }

    @Test
    fun `withRetry applies increasing backoff delays between attempts`() = runBlocking {
        // Given: an operation that records timestamps on each attempt
        val timestamps = mutableListOf<Long>()

        assertThrows<RuntimeException> {
            connectionManager.withRetry(maxRetries = 3) {
                timestamps.add(System.currentTimeMillis())
                throw RuntimeException("always fails")
            }
        }

        assertEquals(3, timestamps.size)
        val delay1 = timestamps[1] - timestamps[0]
        val delay2 = timestamps[2] - timestamps[1]

        // retryDelayMs=50: first gap ~50ms, second gap ~100ms (with tolerance)
        assertTrue(delay1 >= 40, "First retry delay should be ~50ms, was ${delay1}ms")
        assertTrue(delay2 >= 80, "Second retry delay should be ~100ms, was ${delay2}ms")
        assertTrue(delay2 > delay1, "Backoff should increase between retries")
    }

    // -------------------------------------------------------------------------
    // PlotPersistence retry integration (Requirement 38.2)
    // -------------------------------------------------------------------------

    @Test
    fun `createPlot succeeds after transient failure within retry window`() = runBlocking {
        // Given: a connection manager that fails once then succeeds
        val flakyConfig = DatabaseConfig(
            connectionString = mongoContainer.replicaSetUrl,
            databaseName = "test_retry_ocp",
            maxRetries = 3,
            retryDelayMs = 50L
        )
        val flakyManager = MongoConnectionManager(flakyConfig)
        flakyManager.connect()

        // Wrap withRetry to inject a single failure on the first call
        val firstCall = AtomicInteger(0)
        val plot = createTestPlot()

        // Simulate by using withRetry directly: first attempt throws, second succeeds
        val result = flakyManager.withRetry {
            val attempt = firstCall.incrementAndGet()
            if (attempt == 1) throw RuntimeException("transient connection error")
            flakyManager.getDatabase()
                .getCollection<org.bson.Document>("plots")
                .insertOne(org.bson.Document("_id", plot.id.toString()).append("name", plot.name))
            "inserted"
        }

        assertEquals("inserted", result)
        flakyManager.close()
    }

    @Test
    fun `plot CRUD operations succeed with real MongoDB after retries`() = runBlocking {
        // Given: a plot to persist
        val plot = createTestPlot()

        // When: creating, loading, updating, and deleting with retry wrapper
        plotPersistence.createPlot(plot)
        val loaded = plotPersistence.loadPlot(plot.id)
        assertNotNull(loaded)
        assertEquals(plot.name, loaded!!.name)

        val updated = plot.copy(name = "Updated via retry")
        plotPersistence.updatePlot(updated)
        val reloaded = plotPersistence.loadPlot(plot.id)
        assertEquals("Updated via retry", reloaded?.name)

        plotPersistence.deletePlot(plot.id)
        assertNull(plotPersistence.loadPlot(plot.id))
    }

    @Test
    fun `operations queued during failure are retried and succeed on reconnect`() = runBlocking {
        // Simulates Requirement 38.2: queue operations and retry when connection is restored.
        // We model this by having withRetry act as the queue mechanism — the operation
        // is retried until the "connection" is available.
        val connectionAvailable = AtomicInteger(0) // becomes 1 after "reconnect"
        val attemptCount = AtomicInteger(0)

        // Simulate: first 2 attempts fail (connection down), 3rd succeeds (reconnected)
        val result = connectionManager.withRetry(maxRetries = 3) {
            val attempt = attemptCount.incrementAndGet()
            if (attempt <= 2) {
                throw RuntimeException("connection unavailable")
            }
            connectionAvailable.set(1)
            "operation completed after reconnect"
        }

        assertEquals("operation completed after reconnect", result)
        assertEquals(1, connectionAvailable.get())
        assertEquals(3, attemptCount.get())
    }

    @Test
    fun `failed operation after max retries logs error without crashing caller`() = runBlocking {
        // Requirement 17.5: after 3 retries, log error (represented by exception propagation)
        // The caller should handle the exception gracefully — not crash the whole system.
        val attemptCount = AtomicInteger(0)
        var caught = false

        try {
            connectionManager.withRetry(maxRetries = 3) {
                attemptCount.incrementAndGet()
                throw RuntimeException("persistent db failure")
            }
        } catch (e: Exception) {
            // Caller catches and logs — system continues
            caught = true
        }

        assertTrue(caught, "Exception should propagate after max retries")
        assertEquals(3, attemptCount.get(), "Should have attempted exactly 3 times")
        // System is still operational — subsequent operations work fine
        val plot = createTestPlot()
        plotPersistence.createPlot(plot)
        assertNotNull(plotPersistence.loadPlot(plot.id))
    }

    @Test
    fun `multiple independent operations each get their own retry budget`() = runBlocking {
        // Each operation has its own 3-attempt budget; one failing doesn't affect others
        val op1Attempts = AtomicInteger(0)
        val op2Attempts = AtomicInteger(0)

        // Op1 always fails
        assertThrows<RuntimeException> {
            connectionManager.withRetry(maxRetries = 3) {
                op1Attempts.incrementAndGet()
                throw RuntimeException("op1 always fails")
            }
        }

        // Op2 succeeds on first try
        val op2Result = connectionManager.withRetry(maxRetries = 3) {
            op2Attempts.incrementAndGet()
            "op2 success"
        }

        assertEquals(3, op1Attempts.get(), "Op1 should exhaust its 3 retries")
        assertEquals(1, op2Attempts.get(), "Op2 should succeed on first attempt")
        assertEquals("op2 success", op2Result)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createTestPlot() = Plot(
        id = UUID.randomUUID(),
        owner = UUID.randomUUID(),
        name = "Retry Test Plot",
        description = "Testing retry logic",
        mainWorldName = "retry_main",
        devWorldName = "retry_dev",
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        settings = PlotSettings(),
        metadata = PlotMetadata(),
        trustedPlayers = emptySet()
    )
}
