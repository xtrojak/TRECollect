package com.trec.trecollect.ui

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for default value parsing logic.
 * Tests the parsing of multiselect default values (comma-separated and JSON array formats).
 */
class DefaultValueTest {

    @Test
    fun `parseMultiSelectDefault parses comma-separated values`() {
        val defaultValue = "option1,option2,option3"
        val result = parseMultiSelectDefaultForTest(defaultValue)
        
        assertEquals(3, result.size)
        assertEquals("option1", result[0])
        assertEquals("option2", result[1])
        assertEquals("option3", result[2])
    }

    @Test
    fun `parseMultiSelectDefault parses comma-separated values with spaces`() {
        val defaultValue = "option1, option2 , option3"
        val result = parseMultiSelectDefaultForTest(defaultValue)
        
        assertEquals(3, result.size)
        assertEquals("option1", result[0])
        assertEquals("option2", result[1])
        assertEquals("option3", result[2])
    }

    @Test
    fun `parseMultiSelectDefault parses JSON array format`() {
        val defaultValue = """["option1","option2","option3"]"""
        val result = parseMultiSelectDefaultForTest(defaultValue)
        
        assertEquals(3, result.size)
        assertEquals("option1", result[0])
        assertEquals("option2", result[1])
        assertEquals("option3", result[2])
    }

    @Test
    fun `parseMultiSelectDefault parses JSON array with single value`() {
        val defaultValue = """["option1"]"""
        val result = parseMultiSelectDefaultForTest(defaultValue)
        
        assertEquals(1, result.size)
        assertEquals("option1", result[0])
    }

    @Test
    fun `parseMultiSelectDefault parses empty JSON array`() {
        val defaultValue = "[]"
        val result = parseMultiSelectDefaultForTest(defaultValue)
        
        assertEquals(0, result.size)
    }

    @Test
    fun `parseMultiSelectDefault handles empty string`() {
        val defaultValue = ""
        val result = parseMultiSelectDefaultForTest(defaultValue)
        
        assertEquals(0, result.size)
    }

    @Test
    fun `parseMultiSelectDefault handles comma-separated with empty values`() {
        val defaultValue = "option1,,option3"
        val result = parseMultiSelectDefaultForTest(defaultValue)
        
        // Empty values should be filtered out
        assertEquals(2, result.size)
        assertEquals("option1", result[0])
        assertEquals("option3", result[1])
    }

    @Test
    fun `parseMultiSelectDefault falls back to comma-separated when JSON parsing fails`() {
        // Invalid JSON that should fall back to comma-separated parsing
        val defaultValue = "option1,option2"
        val result = parseMultiSelectDefaultForTest(defaultValue)
        
        assertEquals(2, result.size)
        assertEquals("option1", result[0])
        assertEquals("option2", result[1])
    }

    @Test
    fun `parseMultiSelectDefault handles single value`() {
        val defaultValue = "option1"
        val result = parseMultiSelectDefaultForTest(defaultValue)
        
        assertEquals(1, result.size)
        assertEquals("option1", result[0])
    }
}

/**
 * Helper function to test the parseMultiSelectDefault logic.
 * This mirrors the implementation in FormEditActivity.
 */
private fun parseMultiSelectDefaultForTest(defaultValue: String): List<String> {
    return try {
        // Try parsing as JSON array first
        val jsonArray = org.json.JSONArray(defaultValue)
        (0 until jsonArray.length()).mapNotNull { jsonArray.optString(it).takeIf { it.isNotEmpty() } }
    } catch (e: Exception) {
        // If not JSON, treat as comma-separated values
        defaultValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
