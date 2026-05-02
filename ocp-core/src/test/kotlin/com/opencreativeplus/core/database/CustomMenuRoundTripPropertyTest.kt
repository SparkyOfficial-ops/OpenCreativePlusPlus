// Feature: ocp-plugin-fixes-and-completions, Property 6: CustomMenuDefinition round-trip
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.core.database

import com.mongodb.client.model.ReplaceOptions
import com.mongodb.kotlin.client.coroutine.FindFlow
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.*
import kotlinx.coroutines.flow.FlowCollector
import org.bson.Document
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Property-based tests for [PlotPersistence] custom menu round-trip.
 *
 * **Property 6: Round-trip CustomMenuDefinition**
 *
 * For any [CustomMenuData] with arbitrary `plotId`, `menuName`, and slot map,
 * after calling `plotPersistence.saveCustomMenu(plotId, menuData)` followed by
 * `plotPersistence.loadCustomMenus(plotId)`, the returned list must contain an
 * object equivalent to the original by `name` and `slots`.
 *
 * **Validates: Requirements 6.4**
 */
class CustomMenuRoundTripPropertyTest : FreeSpec({

    afterSpec { unmockkAll() }

    // -----------------------------------------------------------------------
    // Arbitraries
    // -----------------------------------------------------------------------

    /** Arbitrary non-empty strings safe for use as menu/slot names (no colons). */
    val arbSafeName: Arb<String> =
        Arb.string(1..24).filter { s ->
            s.isNotBlank() && ':' !in s && s.all { it.isLetterOrDigit() || it == '_' || it == '-' }
        }

    /** Arbitrary Material name (simplified — just a non-empty uppercase string). */
    val arbMaterialName: Arb<String> =
        Arb.string(1..16).map { s ->
            s.filter { it.isLetter() }.uppercase().ifEmpty { "STONE" }
        }.filter { it.isNotEmpty() }

    /** Arbitrary [CustomMenuSlotData]. */
    val arbSlotData: Arb<CustomMenuSlotData> = arbitrary {
        CustomMenuSlotData(
            displayName = Arb.string(0..32).bind(),
            clickScriptName = Arb.string(1..16).orNull(0.4).bind(),
            itemType = arbMaterialName.bind(),
            itemData = Arb.string(0..64).orNull(0.5).bind()
        )
    }

    /** Arbitrary slot map: 0–9 slots with integer keys 0..53. */
    val arbSlots: Arb<Map<Int, CustomMenuSlotData>> = arbitrary {
        val count = Arb.int(0..9).bind()
        val keys = (0..53).shuffled().take(count)
        keys.associateWith { arbSlotData.bind() }
    }

    /** Arbitrary [CustomMenuData]. */
    val arbCustomMenuData: Arb<CustomMenuData> = arbitrary {
        CustomMenuData(
            name = arbSafeName.bind(),
            slots = arbSlots.bind()
        )
    }

    // -----------------------------------------------------------------------
    // Infrastructure: in-memory MongoDB mock
    // -----------------------------------------------------------------------

    /**
     * Builds a [PlotPersistence] backed by an in-memory document store.
     *
     * The store is a [ConcurrentHashMap] keyed by document `_id`.
     * `replaceOne` with upsert stores/replaces the document.
     * `find(filter)` returns documents matching `plot_id == plotId`.
     */
    fun buildPersistence(): Pair<PlotPersistence, ConcurrentHashMap<String, Document>> {
        val store = ConcurrentHashMap<String, Document>()

        // --- MongoCollection mock ---
        val collection = mockk<MongoCollection<Document>>(relaxed = true)

        // Capture replaceOne calls and store the document
        coEvery {
            collection.replaceOne(any(), any<Document>(), any<ReplaceOptions>())
        } answers {
            val doc = secondArg<Document>()
            val id = doc.getString("_id") ?: error("Document missing _id")
            store[id] = Document(doc) // defensive copy
            mockk(relaxed = true)
        }

        // find(filter) — filter is Filters.eq("plot_id", plotId.toString())
        // We extract the plot_id value by rendering the filter to a BsonDocument.
        coEvery { collection.find(any<org.bson.conversions.Bson>()) } answers {
            val filterBson = firstArg<org.bson.conversions.Bson>()
            // Render filter to BsonDocument to extract the plot_id value
            val bsonDoc = filterBson.toBsonDocument(
                org.bson.BsonDocument::class.java,
                com.mongodb.MongoClientSettings.getDefaultCodecRegistry()
            )
            // Filters.eq("plot_id", value) produces {"plot_id": value}
            val plotIdValue = bsonDoc["plot_id"]?.asString()?.value

            val matching = if (plotIdValue != null) {
                store.values.filter { it.getString("plot_id") == plotIdValue }
            } else {
                store.values.toList()
            }

            val ff = mockk<FindFlow<Document>>(relaxed = true)
            coEvery { ff.collect(any()) } coAnswers {
                @Suppress("UNCHECKED_CAST")
                val collector = firstArg<FlowCollector<Document>>()
                matching.forEach { collector.emit(it) }
            }
            ff
        }

        // --- MongoDatabase mock ---
        val database = mockk<MongoDatabase>(relaxed = true)
        every { database.getCollection<Document>("custom_menus") } returns collection

        // --- MongoConnectionManager mock: withRetry just executes the block ---
        val connectionManager = mockk<MongoConnectionManager>(relaxed = true)
        coEvery { connectionManager.withRetry(block = any<suspend () -> Any?>()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = arg<suspend () -> Any?>(0)
            block()
        }
        coEvery { connectionManager.withRetry(any<Int>(), any<suspend () -> Any?>()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = arg<suspend () -> Any?>(1)
            block()
        }

        return PlotPersistence(database, connectionManager) to store
    }

    // -----------------------------------------------------------------------
    // Property 6: Round-trip CustomMenuData
    // -----------------------------------------------------------------------

    "Property 6: saveCustomMenu → loadCustomMenus returns equivalent object" - {

        // Validates: Requirements 6.4
        "for any CustomMenuData, save then load produces an object equal to the original" {
            // Feature: ocp-plugin-fixes-and-completions, Property 6: CustomMenuDefinition round-trip
            checkAll(
                PropTestConfig(iterations = 15),
                arbCustomMenuData
            ) { menuData ->
                val (persistence, _) = buildPersistence()
                val plotId = UUID.randomUUID()

                persistence.saveCustomMenu(plotId, menuData)
                val loaded = persistence.loadCustomMenus(plotId)

                val found = loaded.find { it.name == menuData.name }
                found shouldNotBe null
                found!!.name shouldBe menuData.name
                found.slots shouldBe menuData.slots
            }
        }

        // Validates: Requirements 6.3 (composite key — multiple menus per plot)
        "multiple menus for the same plot are all returned by loadCustomMenus" {
            // Feature: ocp-plugin-fixes-and-completions, Property 6: CustomMenuDefinition round-trip
            checkAll(
                PropTestConfig(iterations = 10),
                arbCustomMenuData,
                arbCustomMenuData
            ) { menu1, menu2 ->
                // Ensure distinct names to avoid overwriting each other
                if (menu1.name == menu2.name) return@checkAll

                val (persistence, _) = buildPersistence()
                val plotId = UUID.randomUUID()

                persistence.saveCustomMenu(plotId, menu1)
                persistence.saveCustomMenu(plotId, menu2)
                val loaded = persistence.loadCustomMenus(plotId)

                loaded.find { it.name == menu1.name }?.slots shouldBe menu1.slots
                loaded.find { it.name == menu2.name }?.slots shouldBe menu2.slots
            }
        }

        // Validates: Requirements 6.3 (upsert — saving same menu twice updates it)
        "saving the same menu twice (upsert) returns the latest version" {
            // Feature: ocp-plugin-fixes-and-completions, Property 6: CustomMenuDefinition round-trip
            checkAll(
                PropTestConfig(iterations = 10),
                arbSafeName,
                arbSlots,
                arbSlots
            ) { menuName, slots1, slots2 ->
                val (persistence, _) = buildPersistence()
                val plotId = UUID.randomUUID()

                val original = CustomMenuData(name = menuName, slots = slots1)
                val updated = CustomMenuData(name = menuName, slots = slots2)

                persistence.saveCustomMenu(plotId, original)
                persistence.saveCustomMenu(plotId, updated)
                val loaded = persistence.loadCustomMenus(plotId)

                // Only one entry for this menu name
                val matches = loaded.filter { it.name == menuName }
                matches.size shouldBe 1
                matches.single().slots shouldBe slots2
            }
        }

        // Validates: Requirements 6.3 (isolation — menus from different plots don't mix)
        "menus from different plots are isolated" {
            // Feature: ocp-plugin-fixes-and-completions, Property 6: CustomMenuDefinition round-trip
            checkAll(
                PropTestConfig(iterations = 10),
                arbCustomMenuData
            ) { menuData ->
                val (persistence, _) = buildPersistence()
                val plotIdA = UUID.randomUUID()
                val plotIdB = UUID.randomUUID()

                persistence.saveCustomMenu(plotIdA, menuData)
                val loadedForB = persistence.loadCustomMenus(plotIdB)

                loadedForB shouldBe emptyList()
            }
        }
    }
})
