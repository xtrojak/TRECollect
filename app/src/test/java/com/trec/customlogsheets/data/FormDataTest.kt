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
}
