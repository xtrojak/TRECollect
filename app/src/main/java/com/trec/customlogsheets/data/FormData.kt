package com.trec.customlogsheets.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringWriter
import java.time.Instant

/**
 * Represents the value of a form field
 */
data class FormFieldValue(
    val fieldId: String,
    val value: String? = null, // For text, textarea, date, time, select
    val values: List<String>? = null, // For multiselect
    val gpsLatitude: Double? = null, // For GPS
    val gpsLongitude: Double? = null, // For GPS
    val photoFileName: String? = null, // For photo (just filename, not full path)
    val tableData: Map<String, Map<String, String>>? = null, // For table: Map<rowName, Map<columnName, value>>
    val dynamicData: List<Map<String, FormFieldValue>>? = null // For dynamic: List of instances, each instance is Map<subFieldId, FormFieldValue>
)

/**
 * Represents a complete form submission or draft
 */
data class FormData(
    val formId: String,
    val siteName: String,
    val isSubmitted: Boolean, // true = submitted, false = draft
    val createdAt: String? = null, // ISO 8601 UTC timestamp when form was first created (draft or submit)
    val submittedAt: String? = null, // ISO 8601 UTC timestamp when submitted (only set when actually submitted)
    val logsheetVersion: String, // Version of the logsheet config used (e.g., "1.0.0")
    val fieldValues: List<FormFieldValue>
) {
    /**
     * Serializes form data to XML
     */
    fun toXml(): String {
        val serializer = Xml.newSerializer()
        val writer = StringWriter()
        
        serializer.setOutput(writer)
        serializer.startDocument("UTF-8", true)
        serializer.startTag(null, "form")
        serializer.attribute(null, "formId", formId)
        serializer.attribute(null, "siteName", siteName)
        serializer.attribute(null, "isSubmitted", isSubmitted.toString())
        if (createdAt != null) {
            serializer.attribute(null, "createdAt", createdAt.toString())
        }
        if (submittedAt != null) {
            serializer.attribute(null, "submittedAt", submittedAt.toString())
        }
        serializer.attribute(null, "logsheetVersion", logsheetVersion)
        
        serializer.startTag(null, "fields")
        for (fieldValue in fieldValues) {
            serializer.startTag(null, "field")
            serializer.attribute(null, "id", fieldValue.fieldId)
            
            if (fieldValue.value != null) {
                serializer.attribute(null, "value", fieldValue.value)
            }
            
            if (fieldValue.values != null && fieldValue.values.isNotEmpty()) {
                serializer.attribute(null, "values", fieldValue.values.joinToString(","))
            }
            
            if (fieldValue.gpsLatitude != null && fieldValue.gpsLongitude != null) {
                serializer.attribute(null, "gpsLatitude", fieldValue.gpsLatitude.toString())
                serializer.attribute(null, "gpsLongitude", fieldValue.gpsLongitude.toString())
            }
            
            if (fieldValue.photoFileName != null) {
                serializer.attribute(null, "photoFileName", fieldValue.photoFileName)
            }
            
            if (fieldValue.tableData != null && fieldValue.tableData.isNotEmpty()) {
                // Serialize table data as JSON string
                val tableJson = org.json.JSONObject()
                for ((row, columns) in fieldValue.tableData) {
                    val rowJson = org.json.JSONObject()
                    for ((col, value) in columns) {
                        rowJson.put(col, value)
                    }
                    tableJson.put(row, rowJson)
                }
                serializer.attribute(null, "tableData", tableJson.toString())
            }
            
            if (fieldValue.dynamicData != null && fieldValue.dynamicData.isNotEmpty()) {
                // Serialize dynamic data: nested structure with instances, using instance numbers
                serializer.startTag(null, "dynamicInstances")
                for ((instanceIndex, instance) in fieldValue.dynamicData.withIndex()) {
                    serializer.startTag(null, "instance")
                    // Set number attribute - must be set after startTag but before child elements
                    serializer.attribute(null, "number", instanceIndex.toString())
                    for ((subFieldId, subFieldValue) in instance) {
                        serializer.startTag(null, "subField")
                        serializer.attribute(null, "id", subFieldId)
                        
                        if (subFieldValue.value != null) {
                            serializer.attribute(null, "value", subFieldValue.value)
                        }
                        if (subFieldValue.values != null && subFieldValue.values.isNotEmpty()) {
                            serializer.attribute(null, "values", subFieldValue.values.joinToString(","))
                        }
                        if (subFieldValue.gpsLatitude != null && subFieldValue.gpsLongitude != null) {
                            serializer.attribute(null, "gpsLatitude", subFieldValue.gpsLatitude.toString())
                            serializer.attribute(null, "gpsLongitude", subFieldValue.gpsLongitude.toString())
                        }
                        if (subFieldValue.photoFileName != null) {
                            serializer.attribute(null, "photoFileName", subFieldValue.photoFileName)
                        }
                        if (subFieldValue.tableData != null && subFieldValue.tableData.isNotEmpty()) {
                            val tableJson = org.json.JSONObject()
                            for ((row, columns) in subFieldValue.tableData) {
                                val rowJson = org.json.JSONObject()
                                for ((col, value) in columns) {
                                    rowJson.put(col, value)
                                }
                                tableJson.put(row, rowJson)
                            }
                            serializer.attribute(null, "tableData", tableJson.toString())
                        }
                        
                        serializer.endTag(null, "subField")
                    }
                    serializer.endTag(null, "instance")
                }
                serializer.endTag(null, "dynamicInstances")
            }
            
            serializer.endTag(null, "field")
        }
        serializer.endTag(null, "fields")
        serializer.endTag(null, "form")
        serializer.endDocument()
        
        val xmlString = writer.toString()
        // Log the XML for debugging (only for dynamic fields)
        if (fieldValues.any { it.dynamicData?.isNotEmpty() == true }) {
            android.util.Log.d("FormData", "Generated XML with dynamic data: ${xmlString.take(500)}...")
        }
        return xmlString
    }
    
    companion object {
        /**
         * Gets current time as ISO 8601 UTC string
         */
        fun getCurrentTimestamp(): String {
            return Instant.now().toString()
        }
        
        /**
         * Deserializes XML to form data
         */
        fun fromXml(xmlString: String): FormData? {
            return try {
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(xmlString.reader())
                
                var formId = ""
                var siteName = ""
                var isSubmitted = false
                var createdAt: String? = null
                var submittedAt: String? = null
                var logsheetVersion = ""
                val fieldValues = mutableListOf<FormFieldValue>()
                
                var eventType = parser.eventType
                var currentFieldId: String? = null
                var currentValue: String? = null
                var currentValues: List<String>? = null
                var currentGpsLat: Double? = null
                var currentGpsLon: Double? = null
                var currentPhotoFileName: String? = null
                var currentTableData: Map<String, Map<String, String>>? = null
                var currentDynamicData: List<Map<String, FormFieldValue>>? = null
                var inDynamicInstances = false
                var currentInstance: MutableMap<String, FormFieldValue>? = null
                var currentSubFieldId: String? = null
                var currentSubFieldValue: String? = null
                var currentSubFieldValues: List<String>? = null
                var currentSubFieldGpsLat: Double? = null
                var currentSubFieldGpsLon: Double? = null
                var currentSubFieldPhotoFileName: String? = null
                var currentSubFieldTableData: Map<String, Map<String, String>>? = null
                val dynamicInstancesList = mutableListOf<Map<String, FormFieldValue>>()
                
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            when (parser.name) {
                                "form" -> {
                                    formId = parser.getAttributeValue(null, "formId") ?: ""
                                    siteName = parser.getAttributeValue(null, "siteName") ?: ""
                                    isSubmitted = parser.getAttributeValue(null, "isSubmitted")?.toBoolean() ?: false
                                    logsheetVersion = parser.getAttributeValue(null, "logsheetVersion") ?: ""
                                    
                                    // Read createdAt - handle both ISO 8601 string and legacy Long format
                                    val createdAtAttr = parser.getAttributeValue(null, "createdAt")
                                    createdAt = if (createdAtAttr != null) {
                                        // Try to parse as ISO 8601 first, fall back to Long conversion if needed
                                        try {
                                            // If it's already a valid ISO 8601 string, use it
                                            Instant.parse(createdAtAttr).toString()
                                        } catch (e: Exception) {
                                            // Legacy format: convert Long to ISO 8601
                                            createdAtAttr.toLongOrNull()?.let { 
                                                Instant.ofEpochMilli(it).toString()
                                            }
                                        }
                                    } else {
                                        null
                                    }
                                    
                                    // Read submittedAt - handle both ISO 8601 string and legacy Long format
                                    val submittedAtAttr = parser.getAttributeValue(null, "submittedAt")
                                    submittedAt = if (submittedAtAttr != null) {
                                        // Try to parse as ISO 8601 first, fall back to Long conversion if needed
                                        try {
                                            // If it's already a valid ISO 8601 string, use it
                                            Instant.parse(submittedAtAttr).toString()
                                        } catch (e: Exception) {
                                            // Legacy format: convert Long to ISO 8601
                                            submittedAtAttr.toLongOrNull()?.let { 
                                                Instant.ofEpochMilli(it).toString()
                                            }
                                        }
                                    } else {
                                        null
                                    }
                                }
                                "field" -> {
                                    if (!inDynamicInstances) {
                                        currentFieldId = parser.getAttributeValue(null, "id")
                                        currentValue = parser.getAttributeValue(null, "value")
                                        val valuesStr = parser.getAttributeValue(null, "values")
                                        currentValues = valuesStr?.split(",")?.map { it.trim() }
                                        currentGpsLat = parser.getAttributeValue(null, "gpsLatitude")?.toDoubleOrNull()
                                        currentGpsLon = parser.getAttributeValue(null, "gpsLongitude")?.toDoubleOrNull()
                                        currentPhotoFileName = parser.getAttributeValue(null, "photoFileName")
                                        val tableDataStr = parser.getAttributeValue(null, "tableData")
                                        currentTableData = if (tableDataStr != null) {
                                            try {
                                                val tableJson = org.json.JSONObject(tableDataStr)
                                                val tableMap = mutableMapOf<String, Map<String, String>>()
                                                val keys = tableJson.keys()
                                                while (keys.hasNext()) {
                                                    val row = keys.next()
                                                    val rowJson = tableJson.getJSONObject(row)
                                                    val rowMap = mutableMapOf<String, String>()
                                                    val colKeys = rowJson.keys()
                                                    while (colKeys.hasNext()) {
                                                        val col = colKeys.next()
                                                        rowMap[col] = rowJson.getString(col)
                                                    }
                                                    tableMap[row] = rowMap
                                                }
                                                tableMap
                                            } catch (e: Exception) {
                                                android.util.Log.e("FormData", "Error parsing table data: ${e.message}", e)
                                                null
                                            }
                                        } else {
                                            null
                                        }
                                    }
                                }
                                "dynamicInstances" -> {
                                    inDynamicInstances = true
                                    dynamicInstancesList.clear()
                                }
                                "instance" -> {
                                    currentInstance = mutableMapOf()
                                }
                                "subField" -> {
                                    currentSubFieldId = parser.getAttributeValue(null, "id")
                                    currentSubFieldValue = parser.getAttributeValue(null, "value")
                                    val valuesStr = parser.getAttributeValue(null, "values")
                                    currentSubFieldValues = valuesStr?.split(",")?.map { it.trim() }
                                    currentSubFieldGpsLat = parser.getAttributeValue(null, "gpsLatitude")?.toDoubleOrNull()
                                    currentSubFieldGpsLon = parser.getAttributeValue(null, "gpsLongitude")?.toDoubleOrNull()
                                    currentSubFieldPhotoFileName = parser.getAttributeValue(null, "photoFileName")
                                    val tableDataStr = parser.getAttributeValue(null, "tableData")
                                    currentSubFieldTableData = if (tableDataStr != null) {
                                        try {
                                            val tableJson = org.json.JSONObject(tableDataStr)
                                            val tableMap = mutableMapOf<String, Map<String, String>>()
                                            val keys = tableJson.keys()
                                            while (keys.hasNext()) {
                                                val row = keys.next()
                                                val rowJson = tableJson.getJSONObject(row)
                                                val rowMap = mutableMapOf<String, String>()
                                                val colKeys = rowJson.keys()
                                                while (colKeys.hasNext()) {
                                                    val col = colKeys.next()
                                                    rowMap[col] = rowJson.getString(col)
                                                }
                                                tableMap[row] = rowMap
                                            }
                                            tableMap
                                        } catch (e: Exception) {
                                            android.util.Log.e("FormData", "Error parsing sub-field table data: ${e.message}", e)
                                            null
                                        }
                                    } else {
                                        null
                                    }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            when (parser.name) {
                                "subField" -> {
                                    if (currentSubFieldId != null && currentInstance != null) {
                                        currentInstance[currentSubFieldId] = FormFieldValue(
                                            fieldId = currentSubFieldId,
                                            value = currentSubFieldValue,
                                            values = currentSubFieldValues,
                                            gpsLatitude = currentSubFieldGpsLat,
                                            gpsLongitude = currentSubFieldGpsLon,
                                            photoFileName = currentSubFieldPhotoFileName,
                                            tableData = currentSubFieldTableData
                                        )
                                        // Reset for next sub-field
                                        currentSubFieldId = null
                                        currentSubFieldValue = null
                                        currentSubFieldValues = null
                                        currentSubFieldGpsLat = null
                                        currentSubFieldGpsLon = null
                                        currentSubFieldPhotoFileName = null
                                        currentSubFieldTableData = null
                                    }
                                }
                                "instance" -> {
                                    if (currentInstance != null && currentInstance.isNotEmpty()) {
                                        dynamicInstancesList.add(currentInstance)
                                        currentInstance = null
                                    }
                                }
                                "dynamicInstances" -> {
                                    currentDynamicData = dynamicInstancesList.toList()
                                    inDynamicInstances = false
                                }
                                "field" -> {
                                    if (currentFieldId != null && !inDynamicInstances) {
                                        fieldValues.add(
                                            FormFieldValue(
                                                fieldId = currentFieldId,
                                                value = currentValue,
                                                values = currentValues,
                                                gpsLatitude = currentGpsLat,
                                                gpsLongitude = currentGpsLon,
                                                photoFileName = currentPhotoFileName,
                                                tableData = currentTableData,
                                                dynamicData = currentDynamicData
                                            )
                                        )
                                        // Reset for next field
                                        currentFieldId = null
                                        currentValue = null
                                        currentValues = null
                                        currentGpsLat = null
                                        currentGpsLon = null
                                        currentPhotoFileName = null
                                        currentTableData = null
                                        currentDynamicData = null
                                    }
                                }
                            }
                        }
                    }
                    eventType = parser.next()
                }
                
                if (logsheetVersion.isEmpty()) {
                    android.util.Log.e("FormData", "Missing logsheetVersion in XML for formId: $formId")
                    null
                } else {
                    FormData(
                        formId = formId,
                        siteName = siteName,
                        isSubmitted = isSubmitted,
                        createdAt = createdAt,
                        submittedAt = submittedAt,
                        logsheetVersion = logsheetVersion,
                        fieldValues = fieldValues
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("FormData", "Error parsing XML: ${e.message}", e)
                null
            }
        }
    }
}

