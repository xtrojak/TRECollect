package com.trec.customlogsheets.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * Unit tests for FormData and FormFieldValue classes.
 * Tests timestamp generation and data class behavior.
 */
class FormDataTest {

    @Test
    fun `getCurrentTimestamp returns valid ISO 8601 string`() {
        val timestamp = FormData.getCurrentTimestamp()
        
        // Should be parseable as ISO 8601
        val instant = Instant.parse(timestamp)
        assertNotNull(instant)
        
        // Should be recent (within last 5 seconds)
        val now = Instant.now()
        val diff = java.time.Duration.between(instant, now).seconds
        assertTrue("Timestamp should be recent", diff >= 0 && diff <= 5)
    }

    @Test
    fun `getCurrentTimestamp returns different values on subsequent calls`() {
        val timestamp1 = FormData.getCurrentTimestamp()
        Thread.sleep(10) // Small delay to ensure different timestamp
        val timestamp2 = FormData.getCurrentTimestamp()
        
        assertNotEquals(timestamp1, timestamp2)
    }

    @Test
    fun `FormFieldValue with text value`() {
        val fieldValue = FormFieldValue(
            fieldId = "text_field",
            value = "Test value"
        )

        assertEquals("text_field", fieldValue.fieldId)
        assertEquals("Test value", fieldValue.value)
        assertNull(fieldValue.values)
        assertNull(fieldValue.gpsLatitude)
        assertNull(fieldValue.gpsLongitude)
        assertNull(fieldValue.photoFileName)
        assertNull(fieldValue.tableData)
        assertNull(fieldValue.dynamicData)
    }

    @Test
    fun `FormFieldValue with multiselect values`() {
        val fieldValue = FormFieldValue(
            fieldId = "multiselect_field",
            values = listOf("Option 1", "Option 2", "Option 3")
        )

        assertEquals("multiselect_field", fieldValue.fieldId)
        assertNull(fieldValue.value)
        assertEquals(3, fieldValue.values?.size)
        assertEquals("Option 1", fieldValue.values?.get(0))
        assertEquals("Option 2", fieldValue.values?.get(1))
        assertEquals("Option 3", fieldValue.values?.get(2))
    }

    @Test
    fun `FormFieldValue with GPS coordinates`() {
        val fieldValue = FormFieldValue(
            fieldId = "gps_field",
            gpsLatitude = 52.5200,
            gpsLongitude = 13.4050
        )

        assertEquals("gps_field", fieldValue.fieldId)
        assertNotNull(fieldValue.gpsLatitude)
        assertNotNull(fieldValue.gpsLongitude)
        assertEquals(52.5200, fieldValue.gpsLatitude!!, 0.0001)
        assertEquals(13.4050, fieldValue.gpsLongitude!!, 0.0001)
    }

    @Test
    fun `FormFieldValue with photo filename`() {
        val fieldValue = FormFieldValue(
            fieldId = "photo_field",
            photoFileName = "photo_123.jpg"
        )

        assertEquals("photo_field", fieldValue.fieldId)
        assertEquals("photo_123.jpg", fieldValue.photoFileName)
    }

    @Test
    fun `FormFieldValue with table data`() {
        val tableData = mapOf(
            "Row1" to mapOf("Column1" to "Value1", "Column2" to "Value2"),
            "Row2" to mapOf("Column1" to "Value3", "Column2" to "Value4")
        )
        val fieldValue = FormFieldValue(
            fieldId = "table_field",
            tableData = tableData
        )

        assertEquals("table_field", fieldValue.fieldId)
        assertNotNull(fieldValue.tableData)
        assertEquals(2, fieldValue.tableData?.size)
        assertEquals("Value1", fieldValue.tableData?.get("Row1")?.get("Column1"))
    }

    @Test
    fun `FormFieldValue with dynamic data`() {
        val dynamicData = listOf(
            mapOf(
                "subField1" to FormFieldValue("subField1", value = "Value1"),
                "subField2" to FormFieldValue("subField2", value = "Value2")
            ),
            mapOf(
                "subField1" to FormFieldValue("subField1", value = "Value3"),
                "subField2" to FormFieldValue("subField2", value = "Value4")
            )
        )
        val fieldValue = FormFieldValue(
            fieldId = "dynamic_field",
            dynamicData = dynamicData
        )

        assertEquals("dynamic_field", fieldValue.fieldId)
        assertNotNull(fieldValue.dynamicData)
        assertEquals(2, fieldValue.dynamicData?.size)
        assertEquals("Value1", fieldValue.dynamicData?.get(0)?.get("subField1")?.value)
    }

    @Test
    fun `FormData with draft status`() {
        val formData = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = false,
            fieldValues = emptyList()
        )

        assertEquals("form1", formData.formId)
        assertEquals("Test Site", formData.siteName)
        assertFalse(formData.isSubmitted)
        assertNull(formData.submittedAt)
        assertTrue(formData.fieldValues.isEmpty())
    }

    @Test
    fun `FormData with submitted status`() {
        val timestamp = FormData.getCurrentTimestamp()
        val formData = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = true,
            createdAt = timestamp,
            submittedAt = timestamp,
            fieldValues = listOf(
                FormFieldValue("field1", value = "value1")
            )
        )

        assertEquals("form1", formData.formId)
        assertEquals("Test Site", formData.siteName)
        assertTrue(formData.isSubmitted)
        assertNotNull(formData.submittedAt)
        assertEquals(1, formData.fieldValues.size)
    }

    @Test
    fun `FormData equality works correctly`() {
        val timestamp = FormData.getCurrentTimestamp()
        val formData1 = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = true,
            createdAt = timestamp,
            submittedAt = timestamp,
            fieldValues = listOf(FormFieldValue("field1", value = "value1"))
        )
        val formData2 = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = true,
            createdAt = timestamp,
            submittedAt = timestamp,
            fieldValues = listOf(FormFieldValue("field1", value = "value1"))
        )

        assertEquals(formData1, formData2)
        assertEquals(formData1.hashCode(), formData2.hashCode())
    }

    @Test
    fun `FormFieldValue equality works correctly`() {
        val fieldValue1 = FormFieldValue(
            fieldId = "field1",
            value = "value1"
        )
        val fieldValue2 = FormFieldValue(
            fieldId = "field1",
            value = "value1"
        )

        assertEquals(fieldValue1, fieldValue2)
        assertEquals(fieldValue1.hashCode(), fieldValue2.hashCode())
    }

    @Test
    fun `FormFieldValue with empty text value`() {
        val fieldValue = FormFieldValue(
            fieldId = "text_field",
            value = ""
        )

        assertEquals("text_field", fieldValue.fieldId)
        assertEquals("", fieldValue.value)
    }

    @Test
    fun `FormFieldValue with null text value`() {
        val fieldValue = FormFieldValue(
            fieldId = "text_field",
            value = null
        )

        assertEquals("text_field", fieldValue.fieldId)
        assertNull(fieldValue.value)
    }

    @Test
    fun `FormFieldValue with empty multiselect values`() {
        val fieldValue = FormFieldValue(
            fieldId = "multiselect_field",
            values = emptyList()
        )

        assertEquals("multiselect_field", fieldValue.fieldId)
        assertNotNull(fieldValue.values)
        assertTrue(fieldValue.values!!.isEmpty())
    }

    @Test
    fun `FormFieldValue with single multiselect value`() {
        val fieldValue = FormFieldValue(
            fieldId = "multiselect_field",
            values = listOf("Single Option")
        )

        assertEquals("multiselect_field", fieldValue.fieldId)
        assertEquals(1, fieldValue.values?.size)
        assertEquals("Single Option", fieldValue.values?.get(0))
    }

    @Test
    fun `FormFieldValue with GPS coordinates at zero`() {
        val fieldValue = FormFieldValue(
            fieldId = "gps_field",
            gpsLatitude = 0.0,
            gpsLongitude = 0.0
        )

        assertNotNull(fieldValue.gpsLatitude)
        assertNotNull(fieldValue.gpsLongitude)
        assertEquals(0.0, fieldValue.gpsLatitude!!, 0.0001)
        assertEquals(0.0, fieldValue.gpsLongitude!!, 0.0001)
    }

    @Test
    fun `FormFieldValue with GPS coordinates at negative values`() {
        val fieldValue = FormFieldValue(
            fieldId = "gps_field",
            gpsLatitude = -90.0,
            gpsLongitude = -180.0
        )

        assertNotNull(fieldValue.gpsLatitude)
        assertNotNull(fieldValue.gpsLongitude)
        assertEquals(-90.0, fieldValue.gpsLatitude!!, 0.0001)
        assertEquals(-180.0, fieldValue.gpsLongitude!!, 0.0001)
    }

    @Test
    fun `FormFieldValue with empty table data`() {
        val fieldValue = FormFieldValue(
            fieldId = "table_field",
            tableData = emptyMap()
        )

        assertEquals("table_field", fieldValue.fieldId)
        assertNotNull(fieldValue.tableData)
        assertTrue(fieldValue.tableData!!.isEmpty())
    }

    @Test
    fun `FormFieldValue with single row table data`() {
        val tableData = mapOf(
            "Row1" to mapOf("Column1" to "Value1")
        )
        val fieldValue = FormFieldValue(
            fieldId = "table_field",
            tableData = tableData
        )

        assertEquals(1, fieldValue.tableData?.size)
        assertEquals("Value1", fieldValue.tableData?.get("Row1")?.get("Column1"))
    }

    @Test
    fun `FormFieldValue with empty dynamic data`() {
        val fieldValue = FormFieldValue(
            fieldId = "dynamic_field",
            dynamicData = emptyList()
        )

        assertEquals("dynamic_field", fieldValue.fieldId)
        assertNotNull(fieldValue.dynamicData)
        assertTrue(fieldValue.dynamicData!!.isEmpty())
    }

    @Test
    fun `FormFieldValue with single instance dynamic data`() {
        val dynamicData = listOf(
            mapOf(
                "subField1" to FormFieldValue("subField1", value = "Value1")
            )
        )
        val fieldValue = FormFieldValue(
            fieldId = "dynamic_field",
            dynamicData = dynamicData
        )

        assertEquals(1, fieldValue.dynamicData?.size)
        assertEquals("Value1", fieldValue.dynamicData?.get(0)?.get("subField1")?.value)
    }

    @Test
    fun `FormData with null createdAt`() {
        val formData = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = false,
            createdAt = null,
            fieldValues = emptyList()
        )

        assertNull(formData.createdAt)
        assertFalse(formData.isSubmitted)
    }

    @Test
    fun `FormData with null submittedAt for draft`() {
        val formData = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = false,
            submittedAt = null,
            fieldValues = emptyList()
        )

        assertFalse(formData.isSubmitted)
        assertNull(formData.submittedAt)
    }

    @Test
    fun `FormData with multiple field values`() {
        val formData = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = false,
            fieldValues = listOf(
                FormFieldValue("field1", value = "value1"),
                FormFieldValue("field2", value = "value2"),
                FormFieldValue("field3", value = "value3")
            )
        )

        assertEquals(3, formData.fieldValues.size)
        assertEquals("value1", formData.fieldValues[0].value)
        assertEquals("value2", formData.fieldValues[1].value)
        assertEquals("value3", formData.fieldValues[2].value)
    }

    @Test
    fun `FormData with mixed field types`() {
        val formData = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = true,
            fieldValues = listOf(
                FormFieldValue("text_field", value = "text value"),
                FormFieldValue("multiselect_field", values = listOf("opt1", "opt2")),
                FormFieldValue("gps_field", gpsLatitude = 52.5, gpsLongitude = 13.4),
                FormFieldValue("photo_field", photoFileName = "photo.jpg")
            )
        )

        assertEquals(4, formData.fieldValues.size)
        assertEquals("text value", formData.fieldValues[0].value)
        assertEquals(2, formData.fieldValues[1].values?.size)
        assertNotNull(formData.fieldValues[2].gpsLatitude)
        assertEquals("photo.jpg", formData.fieldValues[3].photoFileName)
    }

    @Test
    fun `FormFieldValue with different fieldIds are not equal`() {
        val fieldValue1 = FormFieldValue(
            fieldId = "field1",
            value = "value1"
        )
        val fieldValue2 = FormFieldValue(
            fieldId = "field2",
            value = "value1"
        )

        assertNotEquals(fieldValue1, fieldValue2)
    }

    @Test
    fun `FormFieldValue with different values are not equal`() {
        val fieldValue1 = FormFieldValue(
            fieldId = "field1",
            value = "value1"
        )
        val fieldValue2 = FormFieldValue(
            fieldId = "field1",
            value = "value2"
        )

        assertNotEquals(fieldValue1, fieldValue2)
    }

    @Test
    fun `FormData with different formIds are not equal`() {
        val timestamp = FormData.getCurrentTimestamp()
        val formData1 = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = true,
            createdAt = timestamp,
            fieldValues = emptyList()
        )
        val formData2 = FormData(
            formId = "form2",
            siteName = "Test Site",
            isSubmitted = true,
            createdAt = timestamp,
            fieldValues = emptyList()
        )

        assertNotEquals(formData1, formData2)
    }

    @Test
    fun `FormData with different siteNames are not equal`() {
        val timestamp = FormData.getCurrentTimestamp()
        val formData1 = FormData(
            formId = "form1",
            siteName = "Site 1",
            isSubmitted = true,
            createdAt = timestamp,
            fieldValues = emptyList()
        )
        val formData2 = FormData(
            formId = "form1",
            siteName = "Site 2",
            isSubmitted = true,
            createdAt = timestamp,
            fieldValues = emptyList()
        )

        assertNotEquals(formData1, formData2)
    }

    @Test
    fun `FormData with different isSubmitted are not equal`() {
        val timestamp = FormData.getCurrentTimestamp()
        val formData1 = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = false,
            createdAt = timestamp,
            fieldValues = emptyList()
        )
        val formData2 = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = true,
            createdAt = timestamp,
            fieldValues = emptyList()
        )

        assertNotEquals(formData1, formData2)
    }

    @Test
    fun `getCurrentTimestamp format is consistent`() {
        val timestamp1 = FormData.getCurrentTimestamp()
        val timestamp2 = FormData.getCurrentTimestamp()

        // Both should be valid ISO 8601 format
        val instant1 = Instant.parse(timestamp1)
        val instant2 = Instant.parse(timestamp2)

        assertNotNull(instant1)
        assertNotNull(instant2)
        // Format should be consistent (both end with 'Z' or have timezone)
        assertTrue(timestamp1.contains("T"))
        assertTrue(timestamp2.contains("T"))
    }
    
    // Edge cases and error conditions
    
    @Test
    fun `FormData with very long formId`() {
        val longFormId = "A".repeat(500)
        val formData = FormData(
            formId = longFormId,
            siteName = "Test Site",
            isSubmitted = false,
            fieldValues = emptyList()
        )

        assertEquals(longFormId, formData.formId)
    }
    
    @Test
    fun `FormData with very long siteName`() {
        val longSiteName = "B".repeat(500)
        val formData = FormData(
            formId = "form1",
            siteName = longSiteName,
            isSubmitted = false,
            fieldValues = emptyList()
        )

        assertEquals(longSiteName, formData.siteName)
    }
    
    @Test
    fun `FormData with special characters in formId`() {
        val specialFormId = "form!@#$%^&*()_+-=[]{}|;':\",./<>?"
        val formData = FormData(
            formId = specialFormId,
            siteName = "Test Site",
            isSubmitted = false,
            fieldValues = emptyList()
        )

        assertEquals(specialFormId, formData.formId)
    }
    
    @Test
    fun `FormData with empty formId`() {
        val formData = FormData(
            formId = "",
            siteName = "Test Site",
            isSubmitted = false,
            fieldValues = emptyList()
        )

        assertEquals("", formData.formId)
    }
    
    @Test
    fun `FormData with empty siteName`() {
        val formData = FormData(
            formId = "form1",
            siteName = "",
            isSubmitted = false,
            fieldValues = emptyList()
        )

        assertEquals("", formData.siteName)
    }
    
    @Test
    fun `FormData with very large fieldValues list`() {
        val largeFieldList = (1..1000).map { index ->
            FormFieldValue(
                fieldId = "field$index",
                value = "value$index"
            )
        }
        val formData = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = false,
            fieldValues = largeFieldList
        )

        assertEquals(1000, formData.fieldValues.size)
    }
    
    @Test
    fun `FormData with submittedAt but isSubmitted is false`() {
        // Edge case: submittedAt set but isSubmitted is false
        val timestamp = FormData.getCurrentTimestamp()
        val formData = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = false,
            submittedAt = timestamp,
            fieldValues = emptyList()
        )

        assertFalse(formData.isSubmitted)
        assertNotNull(formData.submittedAt) // Can be set even if isSubmitted is false
    }
    
    @Test
    fun `FormData with isSubmitted true but submittedAt is null`() {
        // Edge case: isSubmitted true but submittedAt is null
        val formData = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = true,
            submittedAt = null,
            fieldValues = emptyList()
        )

        assertTrue(formData.isSubmitted)
        assertNull(formData.submittedAt) // Can be null even if isSubmitted is true
    }
    
    @Test
    fun `FormFieldValue with very long text value`() {
        val longValue = "A".repeat(10000)
        val fieldValue = FormFieldValue(
            fieldId = "text_field",
            value = longValue
        )

        assertEquals(longValue, fieldValue.value)
        assertEquals(10000, fieldValue.value!!.length)
    }
    
    @Test
    fun `FormFieldValue with GPS coordinates at boundaries`() {
        // Test GPS coordinate boundaries
        val maxLat = FormFieldValue(
            fieldId = "gps",
            gpsLatitude = 90.0,
            gpsLongitude = 0.0
        )
        val minLat = FormFieldValue(
            fieldId = "gps",
            gpsLatitude = -90.0,
            gpsLongitude = 0.0
        )
        val maxLon = FormFieldValue(
            fieldId = "gps",
            gpsLatitude = 0.0,
            gpsLongitude = 180.0
        )
        val minLon = FormFieldValue(
            fieldId = "gps",
            gpsLatitude = 0.0,
            gpsLongitude = -180.0
        )

        assertEquals(90.0, maxLat.gpsLatitude!!, 0.0001)
        assertEquals(-90.0, minLat.gpsLatitude!!, 0.0001)
        assertEquals(180.0, maxLon.gpsLongitude!!, 0.0001)
        assertEquals(-180.0, minLon.gpsLongitude!!, 0.0001)
    }
    
    @Test
    fun `FormFieldValue with GPS coordinates beyond boundaries`() {
        // Test GPS coordinates beyond valid range (should still be stored)
        val beyondMax = FormFieldValue(
            fieldId = "gps",
            gpsLatitude = 91.0,
            gpsLongitude = 181.0
        )
        val beyondMin = FormFieldValue(
            fieldId = "gps",
            gpsLatitude = -91.0,
            gpsLongitude = -181.0
        )

        assertEquals(91.0, beyondMax.gpsLatitude!!, 0.0001)
        assertEquals(181.0, beyondMax.gpsLongitude!!, 0.0001)
        assertEquals(-91.0, beyondMin.gpsLatitude!!, 0.0001)
        assertEquals(-181.0, beyondMin.gpsLongitude!!, 0.0001)
    }
    
    @Test
    fun `FormFieldValue with GPS latitude only`() {
        // Edge case: only latitude set, longitude is null
        val fieldValue = FormFieldValue(
            fieldId = "gps",
            gpsLatitude = 40.7128,
            gpsLongitude = null
        )

        assertNotNull(fieldValue.gpsLatitude)
        assertNull(fieldValue.gpsLongitude)
    }
    
    @Test
    fun `FormFieldValue with GPS longitude only`() {
        // Edge case: only longitude set, latitude is null
        val fieldValue = FormFieldValue(
            fieldId = "gps",
            gpsLatitude = null,
            gpsLongitude = -74.0060
        )

        assertNull(fieldValue.gpsLatitude)
        assertNotNull(fieldValue.gpsLongitude)
    }
    
    @Test
    fun `FormFieldValue with very large multiselect values list`() {
        val largeValuesList = (1..1000).map { "Option $it" }
        val fieldValue = FormFieldValue(
            fieldId = "multiselect",
            values = largeValuesList
        )

        assertEquals(1000, fieldValue.values!!.size)
    }
    
    @Test
    fun `FormFieldValue with empty string in multiselect values`() {
        val fieldValue = FormFieldValue(
            fieldId = "multiselect",
            values = listOf("", "value1", "", "value2")
        )

        assertEquals(4, fieldValue.values!!.size)
        assertTrue(fieldValue.values!!.contains(""))
    }
    
    @Test
    fun `FormFieldValue with very long photo filename`() {
        val longFilename = "A".repeat(500) + ".jpg"
        val fieldValue = FormFieldValue(
            fieldId = "photo",
            photoFileName = longFilename
        )

        assertEquals(longFilename, fieldValue.photoFileName)
    }
    
    @Test
    fun `FormFieldValue with special characters in photo filename`() {
        val specialFilename = "photo!@#$%^&*()_+-=[]{}|;':\",./<>?.jpg"
        val fieldValue = FormFieldValue(
            fieldId = "photo",
            photoFileName = specialFilename
        )

        assertEquals(specialFilename, fieldValue.photoFileName)
    }
    
    @Test
    fun `FormFieldValue with very large table data`() {
        val largeTableData = (1..100).associate { rowIndex ->
            "row$rowIndex" to (1..50).associate { colIndex ->
                "col$colIndex" to "value_${rowIndex}_$colIndex"
            }
        }
        val fieldValue = FormFieldValue(
            fieldId = "table",
            tableData = largeTableData
        )

        assertEquals(100, fieldValue.tableData!!.size)
        assertEquals(50, fieldValue.tableData!!["row1"]!!.size)
    }
    
    @Test
    fun `FormFieldValue with empty table row`() {
        val tableData = mapOf(
            "row1" to emptyMap<String, String>(),
            "row2" to mapOf("col1" to "value1")
        )
        val fieldValue = FormFieldValue(
            fieldId = "table",
            tableData = tableData
        )

        assertEquals(2, fieldValue.tableData!!.size)
        assertTrue(fieldValue.tableData!!["row1"]!!.isEmpty())
    }
    
    @Test
    fun `FormFieldValue with very large dynamic data`() {
        val largeDynamicData = (1..100).map { instanceIndex ->
            (1..20).associate { fieldIndex ->
                "subField$fieldIndex" to FormFieldValue(
                    fieldId = "subField$fieldIndex",
                    value = "value_${instanceIndex}_$fieldIndex"
                )
            }
        }
        val fieldValue = FormFieldValue(
            fieldId = "dynamic",
            dynamicData = largeDynamicData
        )

        assertEquals(100, fieldValue.dynamicData!!.size)
        assertEquals(20, fieldValue.dynamicData!![0].size)
    }
    
    @Test
    fun `FormFieldValue with empty dynamic instance`() {
        val dynamicData = listOf(
            emptyMap<String, FormFieldValue>(),
            mapOf("subField1" to FormFieldValue("subField1", value = "value1"))
        )
        val fieldValue = FormFieldValue(
            fieldId = "dynamic",
            dynamicData = dynamicData
        )

        assertEquals(2, fieldValue.dynamicData!!.size)
        assertTrue(fieldValue.dynamicData!![0].isEmpty())
    }
    
    @Test
    fun `FormData with duplicate fieldIds`() {
        // Edge case: multiple fields with same ID (should be allowed in data structure)
        val formData = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = false,
            fieldValues = listOf(
                FormFieldValue("field1", value = "value1"),
                FormFieldValue("field1", value = "value2"),
                FormFieldValue("field1", value = "value3")
            )
        )

        assertEquals(3, formData.fieldValues.size)
        assertEquals("value1", formData.fieldValues[0].value)
        assertEquals("value2", formData.fieldValues[1].value)
        assertEquals("value3", formData.fieldValues[2].value)
    }
    
    @Test
    fun `FormData with null fieldId in FormFieldValue`() {
        // Note: fieldId is not nullable in FormFieldValue, but test empty string
        val formData = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = false,
            fieldValues = listOf(
                FormFieldValue("", value = "value1")
            )
        )

        assertEquals("", formData.fieldValues[0].fieldId)
    }
    
    @Test
    fun `FormData equality with different field order`() {
        val timestamp = FormData.getCurrentTimestamp()
        val formData1 = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = true,
            createdAt = timestamp,
            fieldValues = listOf(
                FormFieldValue("field1", value = "value1"),
                FormFieldValue("field2", value = "value2")
            )
        )
        val formData2 = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = true,
            createdAt = timestamp,
            fieldValues = listOf(
                FormFieldValue("field2", value = "value2"),
                FormFieldValue("field1", value = "value1")
            )
        )

        // Should not be equal because field order matters in lists
        assertNotEquals(formData1, formData2)
    }
    
    @Test
    fun `FormData with createdAt before submittedAt`() {
        val createdAt = "2024-01-01T00:00:00Z"
        val submittedAt = "2024-01-02T00:00:00Z"
        val formData = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = true,
            createdAt = createdAt,
            submittedAt = submittedAt,
            fieldValues = emptyList()
        )

        assertNotNull(formData.createdAt)
        assertNotNull(formData.submittedAt)
        // Both should be valid ISO 8601
        assertTrue(formData.createdAt!!.contains("2024-01-01"))
        assertTrue(formData.submittedAt!!.contains("2024-01-02"))
    }
    
    @Test
    fun `FormData with submittedAt before createdAt`() {
        // Edge case: submittedAt before createdAt (invalid but should be handled)
        val createdAt = "2024-01-02T00:00:00Z"
        val submittedAt = "2024-01-01T00:00:00Z"
        val formData = FormData(
            formId = "form1",
            siteName = "Test Site",
            isSubmitted = true,
            createdAt = createdAt,
            submittedAt = submittedAt,
            fieldValues = emptyList()
        )

        // Data structure should accept this (validation would be in business logic)
        assertNotNull(formData.createdAt)
        assertNotNull(formData.submittedAt)
    }
}
