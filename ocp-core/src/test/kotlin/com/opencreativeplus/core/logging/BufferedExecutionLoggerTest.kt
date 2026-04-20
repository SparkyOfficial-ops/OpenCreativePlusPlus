package com.opencreativeplus.core.logging

import com.mongodb.client.model.InsertManyOptions
import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Unit tests for BufferedExecutionLogger.
 *
 * 7.6 — Fix check: 200 events are written via insertMany in batches of ≤ 100.
 * 7.7 — Preservation: flush() on onDisable writes all buffered logs.
 */
class BufferedExecutionLoggerTest {

    private lateinit var collection: MongoCollection<Document>
    private val capturedBatches = mutableListOf<List<Document>>()

    @BeforeEach
    fun setUp() {
        collection = mockk()
        capturedBatches.clear()

        coEvery { collection.insertMany(any<List<Document>>(), any<InsertManyOptions>()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            capturedBatches.add(firstArg<List<Document>>().toList())
            mockk(relaxed = true)
        }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun doc(id: Int) = Document("id", id)

    // -------------------------------------------------------------------------
    // 7.6 — Fix check: 200 events → insertMany in batches ≤ 100
    // -------------------------------------------------------------------------

    @Test
    fun `200 events are flushed via insertMany in batches of at most 100`() = runTest {
        // No timer scope — we rely on auto-flush at BATCH_SIZE boundary
        val logger = BufferedExecutionLogger(collection, scope = null)

        repeat(200) { i -> logger.log(doc(i)) }

        // Each batch must be ≤ BATCH_SIZE
        assertTrue(capturedBatches.isNotEmpty(), "insertMany should have been called at least once")
        capturedBatches.forEach { batch ->
            assertTrue(batch.size <= BufferedExecutionLogger.BATCH_SIZE,
                "Batch size ${batch.size} exceeds BATCH_SIZE ${BufferedExecutionLogger.BATCH_SIZE}")
        }

        // All 200 documents must have been written
        val totalWritten = capturedBatches.sumOf { it.size }
        assertTrue(totalWritten == 200, "Expected 200 documents written, got $totalWritten")

        coVerify(atLeast = 2) { collection.insertMany(any<List<Document>>(), any<InsertManyOptions>()) }
    }

    @Test
    fun `auto-flush triggers exactly at BATCH_SIZE boundary`() = runTest {
        val logger = BufferedExecutionLogger(collection, scope = null)

        // Log exactly BATCH_SIZE - 1 events → no flush yet
        repeat(BufferedExecutionLogger.BATCH_SIZE - 1) { i -> logger.log(doc(i)) }
        assertTrue(capturedBatches.isEmpty(), "Should not flush before reaching BATCH_SIZE")

        // One more event triggers flush
        logger.log(doc(BufferedExecutionLogger.BATCH_SIZE))
        assertTrue(capturedBatches.size == 1, "Should flush exactly once at BATCH_SIZE")
        assertTrue(capturedBatches[0].size == BufferedExecutionLogger.BATCH_SIZE)
    }

    // -------------------------------------------------------------------------
    // 7.7 — Preservation: flush() on onDisable writes all buffered logs
    // -------------------------------------------------------------------------

    @Test
    fun `flush() writes all buffered documents that have not yet been auto-flushed`() = runTest {
        val logger = BufferedExecutionLogger(collection, scope = null)

        // Log fewer than BATCH_SIZE so auto-flush hasn't triggered
        val count = 42
        repeat(count) { i -> logger.log(doc(i)) }
        assertTrue(capturedBatches.isEmpty(), "No auto-flush expected below BATCH_SIZE")

        // Simulate onDisable
        logger.flush()

        assertTrue(capturedBatches.size == 1, "flush() should produce exactly one insertMany call")
        assertTrue(capturedBatches[0].size == count,
            "All $count buffered documents should be written on flush()")
    }

    @Test
    fun `flush() on empty buffer does not call insertMany`() = runTest {
        val logger = BufferedExecutionLogger(collection, scope = null)

        logger.flush()

        coVerify(exactly = 0) { collection.insertMany(any<List<Document>>(), any<InsertManyOptions>()) }
    }

    @Test
    fun `flush() after auto-flush writes only remaining documents`() = runTest {
        val logger = BufferedExecutionLogger(collection, scope = null)

        // Fill one full batch (auto-flushed) + 30 extra
        repeat(BufferedExecutionLogger.BATCH_SIZE + 30) { i -> logger.log(doc(i)) }

        val batchesBeforeFlush = capturedBatches.size
        assertTrue(batchesBeforeFlush >= 1, "Auto-flush should have occurred")

        logger.flush()

        val lastBatch = capturedBatches.last()
        assertTrue(lastBatch.size == 30,
            "flush() should write the 30 remaining documents, got ${lastBatch.size}")
    }
}
