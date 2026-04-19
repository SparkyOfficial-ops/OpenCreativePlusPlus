package com.opencreativeplus.plugin.logging

import com.mongodb.kotlin.client.coroutine.FindFlow
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.opencreativeplus.core.database.MongoConnectionManager
import io.mockk.*
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for ExecutionLogger — log creation, storage, retrieval, and the
 * 100-execution limit per plot.
 *
37.1, 37.2, 37.3, 37.5
 */
class ExecutionLoggerTest {

    private lateinit var database: MongoDatabase
    private lateinit var connectionManager: MongoConnectionManager
    private lateinit var collection: MongoCollection<Document>
    private lateinit var executionLogger: ExecutionLogger

    @BeforeEach
    fun setUp() {
        database = mockk()
        collection = mockk(relaxed = true)
        connectionManager = mockk(relaxed = true)

        // Make withRetry execute the block directly without real DB connection
        coEvery { connectionManager.withRetry<Any?>(block = any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = firstArg<suspend () -> Any?>()
            block()
        }

        every { database.getCollection("execution_logs", Document::class.java) } returns collection
        executionLogger = ExecutionLogger(database, connectionManager, collection)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // Log creation — correct fields (req 37.1, 37.2, 37.3)
    // -------------------------------------------------------------------------

    @Test
    fun `logExecution inserts document with all required fields`() = runBlocking {
        val plotId = UUID.randomUUID()
        val startTime = 1_000_000L
        val endTime = 1_000_250L
        val docSlot = slot<Document>()

        coEvery { collection.insertOne(capture(docSlot), any<com.mongodb.client.model.InsertOneOptions>()) } returns mockk(relaxed = true)

        executionLogger.logExecution(
            plotId = plotId,
            eventType = "player_join",
            actionsExecuted = listOf("SendMessageAction", "WaitAction"),
            status = ExecutionLogger.ExecutionStatus.SUCCESS,
            startTime = startTime,
            endTime = endTime
        )

        coVerify { collection.insertOne(any(), any<com.mongodb.client.model.InsertOneOptions>()) }
        assertTrue(docSlot.isCaptured, "insertOne should have been called")
        val doc = docSlot.captured

        // req 37.1 — start time and event type
        assertEquals(plotId.toString(), doc.getString("plot_id"))
        assertEquals("player_join", doc.getString("event_type"))
        assertEquals(startTime, doc.getLong("start_time"))

        // req 37.2 — action node types
        @Suppress("UNCHECKED_CAST")
        val actions = doc.get("actions") as List<String>
        assertEquals(listOf("SendMessageAction", "WaitAction"), actions)

        // req 37.3 — completion time and status
        assertEquals(endTime, doc.getLong("end_time"))
        assertEquals("SUCCESS", doc.getString("status"))
        assertEquals(250L, doc.getLong("duration_ms"))
        assertNotNull(doc.get("created_at"))
    }

    @Test
    fun `logExecution stores error message when status is ERROR`() = runBlocking {
        val docSlot = slot<Document>()
        coEvery { collection.insertOne(capture(docSlot), any<com.mongodb.client.model.InsertOneOptions>()) } returns mockk(relaxed = true)

        executionLogger.logExecution(
            plotId = UUID.randomUUID(),
            eventType = "player_interact",
            actionsExecuted = listOf("SendMessageAction"),
            status = ExecutionLogger.ExecutionStatus.ERROR,
            errorMessage = "NullPointerException in SendMessageAction"
        )

        coVerify { collection.insertOne(any(), any<com.mongodb.client.model.InsertOneOptions>()) }
        assertTrue(docSlot.isCaptured, "insertOne should have been called")
        assertEquals("ERROR", docSlot.captured.getString("status"))
        assertEquals("NullPointerException in SendMessageAction", docSlot.captured.getString("error_message"))
    }

    @Test
    fun `logExecution stores TERMINATED status`() = runBlocking {
        val docSlot = slot<Document>()
        coEvery { collection.insertOne(capture(docSlot), any<com.mongodb.client.model.InsertOneOptions>()) } returns mockk(relaxed = true)

        executionLogger.logExecution(
            plotId = UUID.randomUUID(),
            eventType = "player_join",
            actionsExecuted = emptyList(),
            status = ExecutionLogger.ExecutionStatus.TERMINATED,
            errorMessage = "Watchdog: operation limit exceeded"
        )

        coVerify { collection.insertOne(any(), any<com.mongodb.client.model.InsertOneOptions>()) }
        assertTrue(docSlot.isCaptured, "insertOne should have been called")
        assertEquals("TERMINATED", docSlot.captured.getString("status"))
    }

    @Test
    fun `logExecution duration_ms equals endTime minus startTime`() = runBlocking {
        val docSlot = slot<Document>()
        coEvery { collection.insertOne(capture(docSlot), any<com.mongodb.client.model.InsertOneOptions>()) } returns mockk(relaxed = true)

        executionLogger.logExecution(
            plotId = UUID.randomUUID(),
            eventType = "player_join",
            actionsExecuted = emptyList(),
            status = ExecutionLogger.ExecutionStatus.SUCCESS,
            startTime = 5_000L,
            endTime = 5_750L
        )

        coVerify { collection.insertOne(any(), any<com.mongodb.client.model.InsertOneOptions>()) }
        assertTrue(docSlot.isCaptured, "insertOne should have been called")
        assertEquals(750L, docSlot.captured.getLong("duration_ms"))
    }

    @Test
    fun `logExecution stores plot_id as string`() = runBlocking {
        val plotId = UUID.randomUUID()
        val docSlot = slot<Document>()
        coEvery { collection.insertOne(capture(docSlot), any<com.mongodb.client.model.InsertOneOptions>()) } returns mockk(relaxed = true)

        executionLogger.logExecution(
            plotId = plotId,
            eventType = "player_join",
            actionsExecuted = emptyList(),
            status = ExecutionLogger.ExecutionStatus.SUCCESS
        )

        coVerify { collection.insertOne(any(), any<com.mongodb.client.model.InsertOneOptions>()) }
        assertTrue(docSlot.isCaptured, "insertOne should have been called")
        assertEquals(plotId.toString(), docSlot.captured.getString("plot_id"))
    }

    @Test
    fun `logExecution stores empty actions list`() = runBlocking {
        val docSlot = slot<Document>()
        coEvery { collection.insertOne(capture(docSlot), any<com.mongodb.client.model.InsertOneOptions>()) } returns mockk(relaxed = true)

        executionLogger.logExecution(
            plotId = UUID.randomUUID(),
            eventType = "player_join",
            actionsExecuted = emptyList(),
            status = ExecutionLogger.ExecutionStatus.SUCCESS
        )

        coVerify { collection.insertOne(any(), any<com.mongodb.client.model.InsertOneOptions>()) }
        assertTrue(docSlot.isCaptured, "insertOne should have been called")
        @Suppress("UNCHECKED_CAST")
        val actions = docSlot.captured.get("actions") as List<String>
        assertTrue(actions.isEmpty())
    }


    // -------------------------------------------------------------------------
    // Log retrieval (req 37.4)
    // -------------------------------------------------------------------------

    @Test
    fun `getRecentLogs queries by plot_id and returns results`() = runBlocking {
        val plotId = UUID.randomUUID()
        val doc = Document("plot_id", plotId.toString()).append("event_type", "player_join")

        val findFlow = mockk<FindFlow<Document>>(relaxed = true)
        every { collection.find(Document("plot_id", plotId.toString())) } returns findFlow
        every { findFlow.sort(any()) } returns findFlow
        every { findFlow.limit(100) } returns findFlow
        coEvery { findFlow.collect(any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val collector = firstArg<FlowCollector<Document>>()
            collector.emit(doc)
        }

        val results = executionLogger.getRecentLogs(plotId)

        assertEquals(1, results.size)
        assertEquals("player_join", results.first().getString("event_type"))
    }

    @Test
    fun `getRecentLogs returns empty list when no logs exist`() = runBlocking {
        val plotId = UUID.randomUUID()

        val findFlow = mockk<FindFlow<Document>>(relaxed = true)
        every { collection.find(Document("plot_id", plotId.toString())) } returns findFlow
        every { findFlow.sort(any()) } returns findFlow
        every { findFlow.limit(100) } returns findFlow
        coEvery { findFlow.collect(any()) } returns Unit

        val results = executionLogger.getRecentLogs(plotId)
        assertTrue(results.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Limit to most recent 100 executions per plot (req 37.5)
    // -------------------------------------------------------------------------

    @Test
    fun `getRecentLogs applies limit of 100 by default`() = runBlocking {
        val plotId = UUID.randomUUID()

        val findFlow = mockk<FindFlow<Document>>(relaxed = true)
        every { collection.find(Document("plot_id", plotId.toString())) } returns findFlow
        every { findFlow.sort(any()) } returns findFlow
        val limitSlot = slot<Int>()
        every { findFlow.limit(capture(limitSlot)) } returns findFlow
        coEvery { findFlow.collect(any()) } returns Unit

        executionLogger.getRecentLogs(plotId)

        assertEquals(100, limitSlot.captured, "Default limit must be 100 (req 37.5)")
    }

    @Test
    fun `getRecentLogs passes custom limit to MongoDB query`() = runBlocking {
        val plotId = UUID.randomUUID()

        val findFlow = mockk<FindFlow<Document>>(relaxed = true)
        every { collection.find(Document("plot_id", plotId.toString())) } returns findFlow
        every { findFlow.sort(any()) } returns findFlow
        val limitSlot = slot<Int>()
        every { findFlow.limit(capture(limitSlot)) } returns findFlow
        coEvery { findFlow.collect(any()) } returns Unit

        executionLogger.getRecentLogs(plotId, limit = 25)

        assertEquals(25, limitSlot.captured)
    }

    @Test
    fun `getRecentLogs sorts results by start_time descending`() = runBlocking {
        val plotId = UUID.randomUUID()

        val findFlow = mockk<FindFlow<Document>>(relaxed = true)
        every { collection.find(Document("plot_id", plotId.toString())) } returns findFlow
        val sortSlot = slot<Document>()
        every { findFlow.sort(capture(sortSlot)) } returns findFlow
        every { findFlow.limit(any()) } returns findFlow
        coEvery { findFlow.collect(any()) } returns Unit

        executionLogger.getRecentLogs(plotId)

        // Sort document must request descending order (-1) on start_time
        assertEquals(-1, sortSlot.captured.getInteger("start_time"),
            "Logs must be sorted by start_time descending (req 37.5 — most recent first)")
    }
}
