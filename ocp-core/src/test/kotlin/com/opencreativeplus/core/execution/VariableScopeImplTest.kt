package com.opencreativeplus.core.execution

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/
 * Unit tests for VariableScopeImpl.
 * Tests thread-safe variable storage and retrieval.
 */
class VariableScopeImplTest {
    
    private lateinit var scope: VariableScopeImpl
    
    @BeforeEach
    fun setup() {
        scope = VariableScopeImpl()
    }
    
    @Test
    fun `test set and get variable`() {
        // Given: A variable name and value
        val name = "testVar"
        val value = "testValue"
        
        // When: Setting the variable
        scope.set(name, value)
        
        // Then: Getting the variable returns the value
        assertEquals(value, scope.get(name))
    }
    
    @Test
    fun `test get non-existent variable returns null`() {
        // Given: A non-existent variable name
        val name = "nonExistent"
        
        // When/Then: Getting the variable returns null
        assertNull(scope.get(name))
    }
    
    @Test
    fun `test has returns true for existing variable`() {
        // Given: A variable that exists
        scope.set("testVar", "value")
        
        // When/Then: has returns true
        assertTrue(scope.has("testVar"))
    }
    
    @Test
    fun `test has returns false for non-existent variable`() {
        // Given: A non-existent variable
        // When/Then: has returns false
        assertFalse(scope.has("nonExistent"))
    }
    
    @Test
    fun `test clear removes all variables`() {
        // Given: Multiple variables
        scope.set("var1", "value1")
        scope.set("var2", "value2")
        scope.set("var3", "value3")
        
        // When: Clearing the scope
        scope.clear()
        
        // Then: All variables are removed
        assertFalse(scope.has("var1"))
        assertFalse(scope.has("var2"))
        assertFalse(scope.has("var3"))
        assertNull(scope.get("var1"))
    }
    
    @Test
    fun `test set overwrites existing variable`() {
        // Given: A variable with an initial value
        scope.set("testVar", "initialValue")
        
        // When: Setting the same variable with a new value
        scope.set("testVar", "newValue")
        
        // Then: The new value is returned
        assertEquals("newValue", scope.get("testVar"))
    }
    
    @Test
    fun `test toMap returns all variables`() {
        // Given: Multiple variables
        scope.set("var1", "value1")
        scope.set("var2", 42)
        scope.set("var3", true)
        
        // When: Converting to map
        val map = scope.toMap()
        
        // Then: Map contains all variables
        assertEquals(3, map.size)
        assertEquals("value1", map["var1"])
        assertEquals(42, map["var2"])
        assertEquals(true, map["var3"])
    }
    
    @Test
    fun `test toMap returns empty map for empty scope`() {
        // Given: An empty scope
        // When: Converting to map
        val map = scope.toMap()
        
        // Then: Map is empty
        assertTrue(map.isEmpty())
    }
    
    @Test
    fun `test toMap returns snapshot not live view`() {
        // Given: A scope with variables
        scope.set("var1", "value1")
        val map = scope.toMap()
        
        // When: Modifying the scope after getting the map
        scope.set("var2", "value2")
        
        // Then: The map is not affected
        assertEquals(1, map.size)
        assertFalse(map.containsKey("var2"))
    }
    
    @Test
    fun `test supports different value types`() {
        // Given: Variables of different types
        scope.set("stringVar", "text")
        scope.set("intVar", 123)
        scope.set("doubleVar", 45.67)
        scope.set("boolVar", true)
        scope.set("listVar", listOf(1, 2, 3))
        scope.set("mapVar", mapOf("key" to "value"))
        
        // When/Then: All types are stored and retrieved correctly
        assertEquals("text", scope.get("stringVar"))
        assertEquals(123, scope.get("intVar"))
        assertEquals(45.67, scope.get("doubleVar"))
        assertEquals(true, scope.get("boolVar"))
        assertEquals(listOf(1, 2, 3), scope.get("listVar"))
        assertEquals(mapOf("key" to "value"), scope.get("mapVar"))
    }
}
