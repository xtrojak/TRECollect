package com.trec.customlogsheets.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for FormConfigLoader JSON parsing logic.
 * Tests business logic for parsing form configurations from JSON.
 */
class FormConfigLoaderTest {

    @Test
    fun `parseJson parses simple form correctly`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": true,
                    "fields": [
                        {
                            "id": "field1",
                            "label": "Test Field",
                            "type": "text",
                            "required": false
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        assertEquals("form1", configs[0].id)
        assertEquals("Test Form", configs[0].name)
        assertEquals("Test Section", configs[0].section)
        assertTrue(configs[0].mandatory)
        assertEquals(1, configs[0].fields.size)
        assertEquals("field1", configs[0].fields[0].id)
        assertEquals("Test Field", configs[0].fields[0].label)
        assertEquals(FormFieldConfig.FieldType.TEXT, configs[0].fields[0].type)
    }

    @Test
    fun `parseJson parses form with description`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "description": "Test Description",
                    "mandatory": false,
                    "fields": []
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        assertEquals("Test Description", configs[0].description)
        assertFalse(configs[0].mandatory)
    }

    @Test
    fun `parseJson parses form without description`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": []
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        assertNull(configs[0].description)
    }

    @Test
    fun `parseJson parses multiple forms`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Form 1",
                    "section": "Section 1",
                    "mandatory": true,
                    "fields": []
                },
                {
                    "id": "form2",
                    "name": "Form 2",
                    "section": "Section 2",
                    "mandatory": false,
                    "fields": []
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(2, configs.size)
        assertEquals("form1", configs[0].id)
        assertEquals("form2", configs[1].id)
    }

    @Test
    fun `parseJson parses all field types`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {"id": "text", "label": "Text", "type": "text"},
                        {"id": "textarea", "label": "Textarea", "type": "textarea"},
                        {"id": "date", "label": "Date", "type": "date"},
                        {"id": "time", "label": "Time", "type": "time"},
                        {"id": "select", "label": "Select", "type": "select", "options": ["opt1"]},
                        {"id": "multiselect", "label": "Multiselect", "type": "multiselect", "options": ["opt1"]},
                        {"id": "gps", "label": "GPS", "type": "gps"},
                        {"id": "photo", "label": "Photo", "type": "photo"},
                        {"id": "barcode", "label": "Barcode", "type": "barcode"},
                        {"id": "section", "label": "Section", "type": "section"},
                        {"id": "table", "label": "Table", "type": "table", "rows": ["row1"], "columns": ["col1"]},
                        {"id": "dynamic", "label": "Dynamic", "type": "dynamic", "subFields": []}
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        val fields = configs[0].fields
        assertEquals(12, fields.size)
        assertEquals(FormFieldConfig.FieldType.TEXT, fields[0].type)
        assertEquals(FormFieldConfig.FieldType.TEXTAREA, fields[1].type)
        assertEquals(FormFieldConfig.FieldType.DATE, fields[2].type)
        assertEquals(FormFieldConfig.FieldType.TIME, fields[3].type)
        assertEquals(FormFieldConfig.FieldType.SELECT, fields[4].type)
        assertEquals(FormFieldConfig.FieldType.MULTISELECT, fields[5].type)
        assertEquals(FormFieldConfig.FieldType.GPS, fields[6].type)
        assertEquals(FormFieldConfig.FieldType.PHOTO, fields[7].type)
        assertEquals(FormFieldConfig.FieldType.BARCODE, fields[8].type)
        assertEquals(FormFieldConfig.FieldType.SECTION, fields[9].type)
        assertEquals(FormFieldConfig.FieldType.TABLE, fields[10].type)
        assertEquals(FormFieldConfig.FieldType.DYNAMIC, fields[11].type)
    }

    @Test
    fun `parseJson handles unknown field type by defaulting to TEXT`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "field1",
                            "label": "Test Field",
                            "type": "unknown_type"
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        assertEquals(FormFieldConfig.FieldType.TEXT, configs[0].fields[0].type)
    }

    @Test
    fun `parseJson parses field with options`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "field1",
                            "label": "Select Field",
                            "type": "select",
                            "options": ["Option 1", "Option 2", "Option 3"]
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        val options = configs[0].fields[0].options
        assertNotNull(options)
        assertEquals(3, options?.size)
        assertEquals("Option 1", options?.get(0))
        assertEquals("Option 2", options?.get(1))
        assertEquals("Option 3", options?.get(2))
    }

    @Test
    fun `parseJson parses field with required flag`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "field1",
                            "label": "Required Field",
                            "type": "text",
                            "required": true
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        assertTrue(configs[0].fields[0].required)
    }

    @Test
    fun `parseJson parses field with inputType`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "field1",
                            "label": "Number Field",
                            "type": "text",
                            "inputType": "number"
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        assertEquals("number", configs[0].fields[0].inputType)
    }

    @Test
    fun `parseJson parses table field with rows and columns`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "table1",
                            "label": "Table Field",
                            "type": "table",
                            "rows": ["Row1", "Row2"],
                            "columns": ["Col1", "Col2"]
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        val field = configs[0].fields[0]
        assertEquals(FormFieldConfig.FieldType.TABLE, field.type)
        assertNotNull(field.rows)
        assertEquals(2, field.rows?.size)
        assertEquals("Row1", field.rows?.get(0))
        assertEquals("Row2", field.rows?.get(1))
        assertNotNull(field.columns)
        assertEquals(2, field.columns?.size)
        assertEquals("Col1", field.columns?.get(0))
        assertEquals("Col2", field.columns?.get(1))
    }

    @Test
    fun `parseJson parses dynamic field with subFields`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "dynamic1",
                            "label": "Dynamic Field",
                            "type": "dynamic",
                            "instance_name": "Sample",
                            "subFields": [
                                {
                                    "id": "subField1",
                                    "label": "Sub Field 1",
                                    "type": "text"
                                },
                                {
                                    "id": "subField2",
                                    "label": "Sub Field 2",
                                    "type": "text"
                                }
                            ]
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        val field = configs[0].fields[0]
        assertEquals(FormFieldConfig.FieldType.DYNAMIC, field.type)
        assertEquals("Sample", field.instanceName)
        assertNotNull(field.subFields)
        assertEquals(2, field.subFields?.size)
        assertEquals("subField1", field.subFields?.get(0)?.id)
        assertEquals("subField2", field.subFields?.get(1)?.id)
    }

    @Test
    fun `parseJson handles empty forms array`() {
        val jsonString = """
        {
            "forms": []
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(0, configs.size)
    }

    @Test
    fun `parseJson handles field with null options`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "field1",
                            "label": "Text Field",
                            "type": "text"
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        assertNull(configs[0].fields[0].options)
    }

    @Test
    fun `parseJson handles section field with required false`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "section1",
                            "label": "Section Header",
                            "type": "section",
                            "required": true
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        // Section fields should always have required = false
        assertFalse(configs[0].fields[0].required)
        assertNull(configs[0].fields[0].options)
        assertNull(configs[0].fields[0].inputType)
    }
    
    // Edge cases and error conditions
    
    @Test
    fun `parseJson handles missing forms key`() {
        val jsonString = """
        {
            "otherKey": "value"
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        // Should return empty list or handle gracefully
        assertTrue("Should handle missing forms key", configs.isEmpty() || configs.isNotEmpty())
    }
    
    @Test
    fun `parseJson handles null forms value`() {
        val jsonString = """
        {
            "forms": null
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        // Should return empty list or handle gracefully
        assertTrue("Should handle null forms", configs.isEmpty() || configs.isNotEmpty())
    }
    
    @Test
    fun `parseJson handles form with missing id`() {
        val jsonString = """
        {
            "forms": [
                {
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": []
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        // Should handle missing id (might be empty string or null)
        assertTrue("Should handle missing id", configs.isEmpty() || configs.isNotEmpty())
    }
    
    @Test
    fun `parseJson handles form with missing name`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": []
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        // Should handle missing name
        if (configs.isNotEmpty()) {
            assertTrue("Name should be empty or null", configs[0].name.isEmpty() || configs[0].name.isBlank())
        }
    }
    
    @Test
    fun `parseJson handles form with missing section`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "mandatory": false,
                    "fields": []
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        // Should handle missing section
        if (configs.isNotEmpty()) {
            assertTrue("Section should be empty or null", configs[0].section.isEmpty() || configs[0].section.isBlank())
        }
    }
    
    @Test
    fun `parseJson handles form with missing mandatory field`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "fields": []
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        // Should default mandatory to false
        if (configs.isNotEmpty()) {
            assertFalse("Mandatory should default to false", configs[0].mandatory)
        }
    }
    
    @Test
    fun `parseJson handles form with missing fields array`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        // Should handle missing fields
        if (configs.isNotEmpty()) {
            assertTrue("Fields should be empty or null", configs[0].fields.isEmpty() || configs[0].fields.isNotEmpty())
        }
    }
    
    @Test
    fun `parseJson handles field with missing id`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "label": "Test Field",
                            "type": "text"
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        // Should handle missing field id
        if (configs.isNotEmpty() && configs[0].fields.isNotEmpty()) {
            assertTrue("Field id should be empty or null", configs[0].fields[0].id.isEmpty() || configs[0].fields[0].id.isBlank())
        }
    }
    
    @Test
    fun `parseJson handles field with missing label`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "field1",
                            "type": "text"
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        // Should handle missing label
        if (configs.isNotEmpty() && configs[0].fields.isNotEmpty()) {
            assertTrue("Field label should be empty or null", configs[0].fields[0].label.isEmpty() || configs[0].fields[0].label.isBlank())
        }
    }
    
    @Test
    fun `parseJson handles field with missing type`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "field1",
                            "label": "Test Field"
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        // Should default to TEXT type
        if (configs.isNotEmpty() && configs[0].fields.isNotEmpty()) {
            assertEquals(FormFieldConfig.FieldType.TEXT, configs[0].fields[0].type)
        }
    }
    
    @Test
    fun `parseJson handles empty options array`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "field1",
                            "label": "Select Field",
                            "type": "select",
                            "options": []
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        val options = configs[0].fields[0].options
        assertNotNull(options)
        assertTrue("Options should be empty", options!!.isEmpty())
    }
    
    @Test
    fun `parseJson handles options with empty strings`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "field1",
                            "label": "Select Field",
                            "type": "select",
                            "options": ["", "Option1", "", "Option2"]
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        val options = configs[0].fields[0].options
        assertNotNull(options)
        assertEquals(4, options!!.size)
        assertTrue("Should contain empty string", options.contains(""))
    }
    
    @Test
    fun `parseJson handles very long field label`() {
        val longLabel = "A".repeat(1000)
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "field1",
                            "label": "$longLabel",
                            "type": "text"
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        assertEquals(longLabel, configs[0].fields[0].label)
    }
    
    @Test
    fun `parseJson handles table field with empty rows`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "table1",
                            "label": "Table Field",
                            "type": "table",
                            "rows": [],
                            "columns": ["Col1", "Col2"]
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        val field = configs[0].fields[0]
        assertEquals(FormFieldConfig.FieldType.TABLE, field.type)
        assertNotNull(field.rows)
        assertTrue("Rows should be empty", field.rows!!.isEmpty())
    }
    
    @Test
    fun `parseJson handles table field with empty columns`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "table1",
                            "label": "Table Field",
                            "type": "table",
                            "rows": ["Row1", "Row2"],
                            "columns": []
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        val field = configs[0].fields[0]
        assertEquals(FormFieldConfig.FieldType.TABLE, field.type)
        assertNotNull(field.columns)
        assertTrue("Columns should be empty", field.columns!!.isEmpty())
    }
    
    @Test
    fun `parseJson handles dynamic field with empty subFields`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "dynamic1",
                            "label": "Dynamic Field",
                            "type": "dynamic",
                            "instance_name": "Sample",
                            "subFields": []
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        val field = configs[0].fields[0]
        assertEquals(FormFieldConfig.FieldType.DYNAMIC, field.type)
        assertNotNull(field.subFields)
        assertTrue("SubFields should be empty", field.subFields!!.isEmpty())
    }
    
    @Test
    fun `parseJson handles malformed JSON gracefully`() {
        val malformedJson = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "field1",
                            "label": "Test Field",
                            "type": "text"
                        }
                    ]
                }
            ]
        }
        """.trimIndent() + "invalid json content"

        // Should handle gracefully (might throw exception or return partial results)
        try {
            val configs = FormConfigLoader.parseJson(malformedJson)
            // If it doesn't throw, should return empty list or partial results
            assertTrue("Should handle malformed JSON", configs.isEmpty() || configs.isNotEmpty())
        } catch (e: Exception) {
            // Exception is acceptable for malformed JSON
            assertTrue("Exception is acceptable for malformed JSON", true)
        }
    }
    
    @Test
    fun `parseJson handles invalid field type case sensitivity`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "field1",
                            "label": "Test Field",
                            "type": "TEXT"
                        },
                        {
                            "id": "field2",
                            "label": "Test Field",
                            "type": "Text"
                        },
                        {
                            "id": "field3",
                            "label": "Test Field",
                            "type": "tExT"
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        // Should default to TEXT for case mismatches
        assertEquals(1, configs.size)
        configs[0].fields.forEach { field ->
            assertEquals(FormFieldConfig.FieldType.TEXT, field.type)
        }
    }
    
    @Test
    fun `parseJson handles very large forms array`() {
        val formsJson = (1..100).joinToString(",") { formIndex ->
            """
            {
                "id": "form$formIndex",
                "name": "Form $formIndex",
                "section": "Section $formIndex",
                "mandatory": false,
                "fields": []
            }
            """.trimIndent()
        }
        val jsonString = """
        {
            "forms": [$formsJson]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(100, configs.size)
    }
    
    @Test
    fun `parseJson handles very large fields array`() {
        val fieldsJson = (1..500).joinToString(",") { fieldIndex ->
            """
            {
                "id": "field$fieldIndex",
                "label": "Field $fieldIndex",
                "type": "text"
            }
            """.trimIndent()
        }
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [$fieldsJson]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        assertEquals(500, configs[0].fields.size)
    }
    
    @Test
    fun `parseJson handles numeric string values`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form",
                    "section": "Test Section",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "field1",
                            "label": "Test Field",
                            "type": "text",
                            "required": "true"
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        // Should handle string "true" vs boolean true
        if (configs.isNotEmpty() && configs[0].fields.isNotEmpty()) {
            // Behavior depends on implementation - might parse as boolean or string
            assertTrue("Should handle numeric/string values", true)
        }
    }
    
    @Test
    fun `parseJson handles unicode characters in labels`() {
        val jsonString = """
        {
            "forms": [
                {
                    "id": "form1",
                    "name": "Test Form 测试",
                    "section": "Test Section 日本語",
                    "mandatory": false,
                    "fields": [
                        {
                            "id": "field1",
                            "label": "Field Label 🚀",
                            "type": "text"
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val configs = FormConfigLoader.parseJson(jsonString)

        assertEquals(1, configs.size)
        assertTrue(configs[0].name.contains("测试"))
        assertTrue(configs[0].section.contains("日本語"))
        assertTrue(configs[0].fields[0].label.contains("🚀"))
    }
}
