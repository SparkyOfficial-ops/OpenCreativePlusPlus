package com.opencreativeplus.core.execution

import com.mongodb.client.model.ReplaceOptions
import com.mongodb.kotlin.client.coroutine.FindFlow
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for VariableManager.
 * Tests variable resolution order, scope isolation, and persistence.
 * 
 9.4, 9.5, 9.6
 */
class VariableManagerTest {
    
    private lateinit var database: MongoDatabase
    private lateinit var collection: MongoCollection<Document>
    private lateinit var variableManager: VariableManager

    /** Helper: create a FindFlow mock that emits [docs]. */
    private fun findFlowOf(vararg docs: Document): FindFlow<Document> {
        val ff = mockk<FindFlow<Document>>(relaxed = true)
        // FindFlow extends Flow<T>, so we stub collect() to emit the documents
        coEvery { ff.collect(any()) } coAnswers {
            val collector = firstArg<kotlinx.coroutines.flow.FlowCollector<Document>>()
            docs.forEach { collector.emit(it) }
        }
        return ff
    }
    
    @BeforeEach
    fun setup() {
        database = mockk()
        collection = mockk(relaxed = true)
        every { database.getCollection<Document>("plot_variables") } returns collection
        
        variableManager = VariableManager(database)
    }
    
    @Test
    fun `test createLocalScope returns new instance each time`() {
        // When: Creating multiple local scopes
        val scope1 = variableManager.createLocalScope()
        val scope2 = variableManager.createLocalScope()
        
        // Then: Each scope is a separate instance
        assertNotSame(scope1, scope2)
    }
    
    @Test
    fun `test getPlotScope returns same instance for same plot`() {
        // Given: A plot ID
        val plotId = UUID.randomUUID()
        
        // When: Getting plot scope multiple times
        val scope1 = variableManager.getPlotScope(plotId)
        val scope2 = variableManager.getPlotScope(plotId)
        
        // Then: Same instance is returned
        assertSame(scope1, scope2)
    }
    
    @Test
    fun `test getPlotScope returns different instances for different plots`() {
        // Given: Two different plot IDs
        val plotId1 = UUID.randomUUID()
        val plotId2 = UUID.randomUUID()
        
        // When: Getting plot scopes
        val scope1 = variableManager.getPlotScope(plotId1)
        val scope2 = variableManager.getPlotScope(plotId2)
        
        // Then: Different instances are returned
        assertNotSame(scope1, scope2)
    }
    
    @Test
    fun `test getSavedScope loads from database on first access`() = runBlocking {
        // Given: A plot ID and database document
        val plotId = UUID.randomUUID()
        val document = Document().apply {
            put("_id", plotId.toString())
            put("variables", Document().apply {
                put("savedVar", "savedValue")
            })
            put("updated_at", System.currentTimeMillis())
        }
        
        coEvery { collection.find(Document("_id", plotId.toString())) } returns findFlowOf(document)
        
        // When: Getting saved scope
        val scope = variableManager.getSavedScope(plotId)
        
        // Then: Scope contains loaded variables
        assertEquals("savedValue", scope.get("savedVar"))
        
        // Verify: Database was queried
        coVerify(exactly = 1) { collection.find(Document("_id", plotId.toString())) }
    }
    
    @Test
    fun `test getSavedScope returns cached instance on subsequent access`() = runBlocking {
        // Given: A plot ID
        val plotId = UUID.randomUUID()
        coEvery { collection.find(Document("_id", plotId.toString())) } returns findFlowOf()
        
        // When: Getting saved scope multiple times
        val scope1 = variableManager.getSavedScope(plotId)
        val scope2 = variableManager.getSavedScope(plotId)
        
        // Then: Same instance is returned
        assertSame(scope1, scope2)
        
        // Verify: Database was queried only once
        coVerify(exactly = 1) { collection.find(Document("_id", plotId.toString())) }
    }
    
    @Test
    fun `test getSavedScope creates empty scope when no database document exists`() = runBlocking {
        // Given: A plot ID with no database document
        val plotId = UUID.randomUUID()
        coEvery { collection.find(Document("_id", plotId.toString())) } returns findFlowOf()
        
        // When: Getting saved scope
        val scope = variableManager.getSavedScope(plotId)
        
        // Then: Scope is empty but not null
        assertNotNull(scope)
        assertEquals(null, scope.get("anyVar"))
    }
    
    @Test
    fun `test savePlotVariables persists to database`() = runBlocking {
        // Given: A plot with saved scope variables
        val plotId = UUID.randomUUID()
        coEvery { collection.find(Document("_id", plotId.toString())) } returns findFlowOf()
        
        val scope = variableManager.getSavedScope(plotId)
        scope.set("var1", "value1")
        scope.set("var2", 42)
        
        // When: Saving plot variables
        variableManager.savePlotVariables(plotId)
        
        // Then: Database replaceOne was called with correct document
        coVerify {
            collection.replaceOne(
                Document("_id", plotId.toString()),
                match { doc ->
                    doc.getString("_id") == plotId.toString() &&
                    doc.get("variables", Document::class.java)?.getString("var1") == "value1" &&
                    doc.get("variables", Document::class.java)?.getInteger("var2") == 42 &&
                    doc.getLong("updated_at") != null
                },
                any<ReplaceOptions>()
            )
        }
    }
    
    @Test
    fun `test savePlotVariables does nothing when scope not loaded`() = runBlocking {
        // Given: A plot ID without loaded saved scope
        val plotId = UUID.randomUUID()
        
        // When: Saving plot variables
        variableManager.savePlotVariables(plotId)
        
        // Then: No database operation is performed
        coVerify(exactly = 0) { collection.replaceOne(any(), any(), any<ReplaceOptions>()) }
    }
    
    @Test
    fun `test clearPlotScope clears variables but keeps scope instance`() {
        // Given: A plot with variables
        val plotId = UUID.randomUUID()
        val scope = variableManager.getPlotScope(plotId)
        scope.set("var1", "value1")
        scope.set("var2", "value2")
        
        // When: Clearing plot scope
        variableManager.clearPlotScope(plotId)
        
        // Then: Variables are cleared
        assertEquals(null, scope.get("var1"))
        assertEquals(null, scope.get("var2"))
        
        // And: Same scope instance is still returned
        assertSame(scope, variableManager.getPlotScope(plotId))
    }
    
    @Test
    fun `test removePlotScope removes scope from memory`() {
        // Given: A plot with a scope
        val plotId = UUID.randomUUID()
        val scope1 = variableManager.getPlotScope(plotId)
        scope1.set("var1", "value1")
        
        // When: Removing plot scope
        variableManager.removePlotScope(plotId)
        
        // Then: Getting plot scope returns a new instance
        val scope2 = variableManager.getPlotScope(plotId)
        assertNotSame(scope1, scope2)
        assertEquals(null, scope2.get("var1"))
    }
    
    @Test
    fun `test removeSavedScope removes scope from cache`() = runBlocking {
        // Given: A plot with a loaded saved scope
        val plotId = UUID.randomUUID()
        coEvery { collection.find(Document("_id", plotId.toString())) } returns findFlowOf()
        
        val scope1 = variableManager.getSavedScope(plotId)
        scope1.set("var1", "value1")
        
        // When: Removing saved scope
        variableManager.removeSavedScope(plotId)
        
        // Then: Getting saved scope loads from database again
        val scope2 = variableManager.getSavedScope(plotId)
        assertNotSame(scope1, scope2)
        
        // Verify: Database was queried twice (once for each getSavedScope)
        coVerify(exactly = 2) { collection.find(Document("_id", plotId.toString())) }
    }
    
    @Test
    fun `test scope isolation between different plots`() {
        // Given: Two different plots
        val plotId1 = UUID.randomUUID()
        val plotId2 = UUID.randomUUID()
        
        // When: Setting variables in different plot scopes
        val scope1 = variableManager.getPlotScope(plotId1)
        val scope2 = variableManager.getPlotScope(plotId2)
        
        scope1.set("sharedVar", "plot1Value")
        scope2.set("sharedVar", "plot2Value")
        
        // Then: Variables are isolated
        assertEquals("plot1Value", scope1.get("sharedVar"))
        assertEquals("plot2Value", scope2.get("sharedVar"))
    }
    
    @Test
    fun `test local scope isolation`() {
        // Given: Two local scopes
        val scope1 = variableManager.createLocalScope()
        val scope2 = variableManager.createLocalScope()
        
        // When: Setting variables in both scopes
        scope1.set("var", "value1")
        scope2.set("var", "value2")
        
        // Then: Variables are isolated
        assertEquals("value1", scope1.get("var"))
        assertEquals("value2", scope2.get("var"))
    }

    // -------------------------------------------------------------------------
    // Bug 4 fix-check: 10 concurrent getSavedScope calls → exactly 1 DB query
    // -------------------------------------------------------------------------

    @Test
    fun `test getSavedScope with 10 concurrent calls performs exactly 1 DB query`() = runBlocking {
        // Given: A plot ID and a slow DB load (simulated with delay)
        val plotId = UUID.randomUUID()
        val dbCallCount = AtomicInteger(0)

        coEvery { collection.find(Document("_id", plotId.toString())) } coAnswers {
            dbCallCount.incrementAndGet()
            findFlowOf()
        }

        // When: 10 coroutines call getSavedScope concurrently
        val results = (1..10).map {
            async(Dispatchers.Default) {
                variableManager.getSavedScope(plotId)
            }
        }.awaitAll()

        // Then: All coroutines received the same scope instance
        val first = results.first()
        results.forEach { assertSame(first, it) }

        // And: DB was queried exactly once
        assertEquals(1, dbCallCount.get(), "Expected exactly 1 DB query, got ${dbCallCount.get()}")
    }

    // -------------------------------------------------------------------------
    // Bug 4 preservation: cached plotId → 0 additional DB queries
    // -------------------------------------------------------------------------

    @Test
    fun `test getSavedScope for already-cached plotId makes no additional DB queries`() = runBlocking {
        // Given: A plot scope already loaded
        val plotId = UUID.randomUUID()
        coEvery { collection.find(Document("_id", plotId.toString())) } returns findFlowOf()

        val scope1 = variableManager.getSavedScope(plotId)

        // When: Calling getSavedScope again for the same plotId
        val scope2 = variableManager.getSavedScope(plotId)
        val scope3 = variableManager.getSavedScope(plotId)

        // Then: Same instance returned every time
        assertSame(scope1, scope2)
        assertSame(scope1, scope3)

        // And: DB was queried only once (for the initial load)
        coVerify(exactly = 1) { collection.find(Document("_id", plotId.toString())) }
    }

    // -------------------------------------------------------------------------
    // Task 6.4: plot-scoped variable persists via savePlotVariables / getSavedScope
    // -------------------------------------------------------------------------

    @Test
    fun `test plot-scoped variable is saved and reloaded via getSavedScope`() = runBlocking {
        // Given: a plot with a saved scope variable
        val plotId = UUID.randomUUID()
        val savedDoc = Document().apply {
            put("_id", plotId.toString())
            put("variables", Document().apply {
                put("globalScore", 42)
                put("mapName", "lobby")
            })
            put("updated_at", System.currentTimeMillis())
        }

        // First call: DB returns the document
        coEvery { collection.find(Document("_id", plotId.toString())) } returns findFlowOf(savedDoc)

        // When: loading the saved scope
        val scope = variableManager.getSavedScope(plotId)

        // Then: plot-scoped variables are present
        assertEquals(42, scope.get("globalScore"))
        assertEquals("lobby", scope.get("mapName"))
    }

    @Test
    fun `test savePlotVariables persists plot-scoped variable and getSavedScope reloads it`() = runBlocking {
        // Given: a plot with a saved scope
        val plotId = UUID.randomUUID()
        coEvery { collection.find(Document("_id", plotId.toString())) } returns findFlowOf()

        val scope = variableManager.getSavedScope(plotId)
        scope.set("globalScore", 100)
        scope.set("mapName", "arena")

        // When: saving
        variableManager.savePlotVariables(plotId)

        // Then: replaceOne was called with the correct variables
        coVerify {
            collection.replaceOne(
                Document("_id", plotId.toString()),
                match { doc ->
                    val vars = doc.get("variables", Document::class.java)
                    vars?.getInteger("globalScore") == 100 &&
                    vars?.getString("mapName") == "arena"
                },
                any<com.mongodb.client.model.ReplaceOptions>()
            )
        }
    }

    @Test
    fun `test resolveVariableKey returns raw name for non-prefixed variable`() {
        // Given: a global variable name without %player%_ prefix
        val vm = variableManager

        // When: resolving with any player name
        val key = vm.resolveVariableKey("globalScore", "Alice")

        // Then: key is the raw name (plot-scoped)
        assertEquals("globalScore", key)
    }

    @Test
    fun `test resolveVariableKey returns player-scoped key for prefixed variable`() {
        // Given: a player-scoped variable name
        val vm = variableManager

        // When: resolving for player "Alice"
        val key = vm.resolveVariableKey("%player%_score", "Alice")

        // Then: key is "Alice::score"
        assertEquals("Alice::score", key)
    }

    @Test
    fun `test resolveVariableKey returns raw name when playerName is null`() {
        // Given: a player-scoped variable name but no player context
        val vm = variableManager

        // When: resolving with null playerName
        val key = vm.resolveVariableKey("%player%_score", null)

        // Then: key falls back to raw name
        assertEquals("%player%_score", key)
    }
}
