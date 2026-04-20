package com.opencreativeplus.core.execution

import com.mongodb.kotlin.client.coroutine.FindFlow
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.forAll
import io.mockk.coAnswers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.bson.Document
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based test for Bug 4 fix:
 * For any number of concurrent getSavedScope calls for the same plotId,
 * MongoDB is queried at most once.
 *
 * Property 7 (design.md): For any two (or more) concurrent calls to getSavedScope
 * for the same plotId, the fixed VariableManager SHALL execute exactly one DB query.
 */
class VariableManagerConcurrencyPropertyTest : StringSpec({

    /**
     * Helper: create a FindFlow mock that emits no documents (empty result).
     */
    fun emptyFindFlow(): FindFlow<Document> {
        val ff = mockk<FindFlow<Document>>(relaxed = true)
        coEvery { ff.collect(any()) } coAnswers { /* emit nothing */ }
        return ff
    }

    "for any number of concurrent getSavedScope calls (2..50), DB is queried at most once" {
        forAll(Arb.int(2..50)) { concurrency ->
            val database = mockk<MongoDatabase>()
            val collection = mockk<MongoCollection<Document>>(relaxed = true)
            every { database.getCollection<Document>("plot_variables") } returns collection

            val dbCallCount = AtomicInteger(0)
            val plotId = UUID.randomUUID()

            coEvery { collection.find(Document("_id", plotId.toString())) } coAnswers {
                dbCallCount.incrementAndGet()
                emptyFindFlow()
            }

            val manager = VariableManager(database)

            // Launch `concurrency` coroutines all calling getSavedScope simultaneously
            val results = runBlocking {
                (1..concurrency).map {
                    async(Dispatchers.Default) { manager.getSavedScope(plotId) }
                }.awaitAll()
            }

            // All results must be the same instance
            val first = results.first()
            val allSame = results.all { it === first }

            // DB must have been called exactly once
            allSame && dbCallCount.get() == 1
        }
    }

    "getSavedScope for different plotIds each triggers exactly one DB query" {
        forAll(Arb.int(1..20)) { plotCount ->
            val database = mockk<MongoDatabase>()
            val collection = mockk<MongoCollection<Document>>(relaxed = true)
            every { database.getCollection<Document>("plot_variables") } returns collection

            val plotIds = (1..plotCount).map { UUID.randomUUID() }
            val dbCallCount = AtomicInteger(0)

            plotIds.forEach { id ->
                coEvery { collection.find(Document("_id", id.toString())) } coAnswers {
                    dbCallCount.incrementAndGet()
                    emptyFindFlow()
                }
            }

            val manager = VariableManager(database)

            // Load each plot scope twice concurrently
            runBlocking {
                plotIds.flatMap { id ->
                    listOf(
                        async(Dispatchers.Default) { manager.getSavedScope(id) },
                        async(Dispatchers.Default) { manager.getSavedScope(id) }
                    )
                }.awaitAll()
            }

            // Each plotId should have triggered exactly 1 DB call
            dbCallCount.get() == plotCount
        }
    }
})
