package com.opencreativeplus.core.logging

import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bson.Document
import kotlin.time.Duration.Companion.seconds

/**
 * Buffers execution log events and writes them to MongoDB in batches via insertMany.
 * Flushes automatically when the buffer reaches BATCH_SIZE, or every 60 seconds via a timer.
 * Call flush() on plugin shutdown to guarantee all buffered logs are persisted.
 *
 * Bug 7 fix: replaces per-event insertOne with batched insertMany.
 */
class BufferedExecutionLogger(
    private val collection: MongoCollection<Document>,
    scope: CoroutineScope? = null
) {
    companion object {
        const val BATCH_SIZE = 100
        val FLUSH_INTERVAL = 60.seconds
    }

    private val buffer = ArrayDeque<Document>()
    private val mutex = Mutex()

    init {
        scope?.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL)
                flush()
            }
        }
    }

    /**
     * Adds [event] to the buffer. Flushes immediately if buffer reaches BATCH_SIZE.
     */
    suspend fun log(event: Document) {
        mutex.withLock {
            buffer.add(event)
            if (buffer.size >= BATCH_SIZE) {
                flushUnderLock()
            }
        }
    }

    /**
     * Writes all buffered events to MongoDB via insertMany and clears the buffer.
     * Safe to call from onDisable.
     */
    suspend fun flush() {
        mutex.withLock {
            flushUnderLock()
        }
    }

    /** Must be called while holding [mutex]. */
    private suspend fun flushUnderLock() {
        if (buffer.isEmpty()) return
        val batch = buffer.toList()
        buffer.clear()
        collection.insertMany(batch)
    }
}
