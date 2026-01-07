package com.trec.customlogsheets.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.StringWriter

/**
 * Represents the value of a form field
 */
data class FormFieldValue(
    val fieldId: String,
    val value: String? = null, // For text, textarea, date, time, select
    val values: List<String>? = null, // For multiselect
    val gpsLatitude: Double? = null, // For GPS
    val gpsLongitude: Double? = null, // For GPS
    val photoFileName: String? = null // For photo (just filename, not full path)
)

/**
 * Represents a complete form submission or draft
 */
data class FormData(
    val formId: String,
    val siteName: String,
    val isSubmitted: Boolean, // true = submitted, false = draft
    val submittedAt: Long? = null, // Timestamp when submitted
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
        if (submittedAt != null) {
            serializer.attribute(null, "submittedAt", submittedAt.toString())
        }
        
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
            
            serializer.endTag(null, "field")
        }
        serializer.endTag(null, "fields")
        serializer.endTag(null, "form")
        serializer.endDocument()
        
        return writer.toString()
    }
    
    companion object {
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
                var submittedAt: Long? = null
                val fieldValues = mutableListOf<FormFieldValue>()
                
                var eventType = parser.eventType
                var currentFieldId: String? = null
                var currentValue: String? = null
                var currentValues: List<String>? = null
                var currentGpsLat: Double? = null
                var currentGpsLon: Double? = null
                var currentPhotoFileName: String? = null
                
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            when (parser.name) {
                                "form" -> {
                                    formId = parser.getAttributeValue(null, "formId") ?: ""
                                    siteName = parser.getAttributeValue(null, "siteName") ?: ""
                                    isSubmitted = parser.getAttributeValue(null, "isSubmitted")?.toBoolean() ?: false
                                    submittedAt = parser.getAttributeValue(null, "submittedAt")?.toLongOrNull()
                                }
                                "field" -> {
                                    currentFieldId = parser.getAttributeValue(null, "id")
                                    currentValue = parser.getAttributeValue(null, "value")
                                    val valuesStr = parser.getAttributeValue(null, "values")
                                    currentValues = valuesStr?.split(",")?.map { it.trim() }
                                    currentGpsLat = parser.getAttributeValue(null, "gpsLatitude")?.toDoubleOrNull()
                                    currentGpsLon = parser.getAttributeValue(null, "gpsLongitude")?.toDoubleOrNull()
                                    currentPhotoFileName = parser.getAttributeValue(null, "photoFileName")
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name == "field" && currentFieldId != null) {
                                fieldValues.add(
                                    FormFieldValue(
                                        fieldId = currentFieldId,
                                        value = currentValue,
                                        values = currentValues,
                                        gpsLatitude = currentGpsLat,
                                        gpsLongitude = currentGpsLon,
                                        photoFileName = currentPhotoFileName
                                    )
                                )
                                // Reset for next field
                                currentFieldId = null
                                currentValue = null
                                currentValues = null
                                currentGpsLat = null
                                currentGpsLon = null
                                currentPhotoFileName = null
                            }
                        }
                    }
                    eventType = parser.next()
                }
                
                FormData(
                    formId = formId,
                    siteName = siteName,
                    isSubmitted = isSubmitted,
                    submittedAt = submittedAt,
                    fieldValues = fieldValues
                )
            } catch (e: Exception) {
                android.util.Log.e("FormData", "Error parsing XML: ${e.message}", e)
                null
            }
        }
    }
}

