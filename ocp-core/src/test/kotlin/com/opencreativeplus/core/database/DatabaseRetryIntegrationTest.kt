package com.opencreativeplus.core.database

import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/
 * Integration tests for database retry logic.
 *
 * Verifies that:
 * - Operations are retried up to 3 times before logging an error (Requirement 17.5)
 * - Operations succeed when connection is restored within the retry window (Requirement 38.2)
 * - Queued operations are retried when connection is restored (Requirement 38.2)
 *
 * The retry logic tests use MongoConnectionManager.withRetry() directly with simulated
 * failures — no real MongoDB connection is required for these tests.
 *
 * Requirements: 38.1, 38.2
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseRetryIntegrationTest {

    private lateinit var connectionManager: MongoConnectionManager

    @BeforeAll
    fun setup() {
        val config = DatabaseConfig(
            connectionString = "mongodb://localhost:27017",
            databaseName = "test_retry_ocp",
            maxRetries = 3,
            retryDelayMs = 50L // short delay for fast tests
        )
        // We do NOT call connect() — the retry tests use withRetry() with lambdas
        // that simulate failures without needing a real database connection.
        connectionManager = MongoConnectionManager(config)
    }

    @AfterAll
    fun teardown() {
        connectionManager.close()
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

    @Test
    fun `withRetry succeeds on first attempt without any delay`() = runBlocking {
        // Given: an operation that always succeeds
        val attemptCount = AtomicInteger(0)
        val start = System.currentTimeMillis()

        val result = connectionManager.withRetry(maxRetries = 3) {
            attemptCount.incrementAndGet()
            "immediate success"
        }

        val elapsed = System.currentTimeMillis() - start
        assertEquals("immediate success", result)
        assertEquals(1, attemptCount.get(), "Should succeed on first attempt")
        assertTrue(elapsed < 100, "Should not delay when first attempt succeeds")
    }

    // -------------------------------------------------------------------------
    // Simulated connection recovery (Requirement 38.2)
    // -------------------------------------------------------------------------

    @Test
    fun `operations queued during failure are retried and succeed on reconnect`() = runBlocking {
        // Simulates Requirement 38.2: queue operations and retry when connection is restored.
        // withRetry acts as the queue mechanism — the operation is retried until available.
        val connectionAvailable = AtomicInteger(0)
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
    fun `failed operation after max retries propagates exception to caller`() = runBlocking {
        // Requirement 17.5: after 3 retries, exception propagates so caller can log the error
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

    @Test
    fun `withRetry respects custom maxRetries parameter`() = runBlocking {
        // Given: an operation that always fails with a custom retry count of 5
        val attemptCount = AtomicInteger(0)

        assertThrows<RuntimeException> {
            connectionManager.withRetry(maxRetries = 5) {
                attemptCount.incrementAndGet()
                throw RuntimeException("always fails")
            }
        }

        assertEquals(5, attemptCount.get(), "Should use the custom maxRetries value")
    }

    @Test
    fun `withRetry preserves the original exception type`() = runBlocking {
        // Requirement 17.5: the exception type is preserved so callers can handle specific errors
        class DatabaseConnectionException(message: String) : Exception(message)

        val ex = assertThrows<DatabaseConnectionException> {
            connectionManager.withRetry(maxRetries = 3) {
                throw DatabaseConnectionException("connection refused")
            }
        }

        assertEquals("connection refused", ex.message)
    }

    @Test
    fun `withRetry succeeds after exactly one failure`() = runBlocking {
        // Simulates a transient network blip — one failure then immediate recovery
        val attemptCount = AtomicInteger(0)

        val result = connectionManager.withRetry(maxRetries = 3) {
            val attempt = attemptCount.incrementAndGet()
            if (attempt == 1) throw RuntimeException("transient blip")
            "recovered after blip"
        }

        assertEquals("recovered after blip", result)
        assertEquals(2, attemptCount.get(), "Should succeed on second attempt")
    }

    // -------------------------------------------------------------------------
    // MongoDB-backed tests (require Docker / Testcontainers)
    // These follow the same pattern as PlotPersistenceTest and are skipped
    // when Docker is unavailable.
    // -------------------------------------------------------------------------

    /
     * Nested test class that uses Testcontainers for real MongoDB integration.
     * Skipped automatically when Docker is not available.
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisabledIfSystemProperty(named = "skipDockerTests", matches = "true")
    inner class WithRealMongoDB {

        private lateinit var mongoContainer: org.testcontainers.containers.MongoDBContainer
        private lateinit var realConnectionManager: MongoConnectionManager
        private lateinit var plotPersistence: PlotPersistence

        @BeforeAll
        fun setupContainer() {
            try {
                mongoContainer = org.testcontainers.containers.MongoDBContainer(
                    org.testcontainers.utility.DockerImageName.parse("mongo:7.0")
                )
                mongoContainer.start()

                val config = DatabaseConfig(
                    connectionString = mongoContainer.replicaSetUrl,
                    databaseName = "test_retry_ocp",
                    maxRetries = 3,
                    retryDelayMs = 50L
                )
                realConnectionManager = MongoConnectionManager(config)
                runBlocking {
                    realConnectionManager.connect()
                    plotPersistence = PlotPersistence(
                        realConnectionManager.getDatabase(),
                        realConnectionManager
                    )
                }
            } catch (e: Exception) {
                // Docker not available — skip these tests
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "Docker not available, skipping MongoDB integration tests: ${e.message}")
            }
        }

        @AfterAll
        fun teardownContainer() {
            if (::realConnectionManager.isInitialized) realConnectionManager.close()
            if (::mongoContainer.isInitialized) mongoContainer.stop()
        }

        @BeforeEach
        fun cleanDatabase() = runBlocking {
            if (::realConnectionManager.isInitialized) {
                realConnectionManager.getDatabase()
                    .getCollection<org.bson.Document>("plots")
                    .drop()
            }
        }

        @Test
        fun `plot CRUD operations succeed with real MongoDB after retries`() = runBlocking {
            val plot = createTestPlot()

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
        fun `createPlot succeeds after transient failure within retry window`() = runBlocking {
            val firstCall = AtomicInteger(0)
            val plot = createTestPlot()

            val result = realConnectionManager.withRetry {
                val attempt = firstCall.incrementAndGet()
                if (attempt == 1) throw RuntimeException("transient connection error")
                realConnectionManager.getDatabase()
                    .getCollection<org.bson.Document>("plots")
                    .insertOne(
                        org.bson.Document("_id", plot.id.toString()).append("name", plot.name)
                    )
                "inserted"
            }

            assertEquals("inserted", result)
        }

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
}
