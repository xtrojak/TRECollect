package com.trec.customlogsheets.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Configuration for a form field
 */
data class FormFieldConfig(
    val id: String,
    val label: String,
    val type: FieldType,
    val required: Boolean = false,
    val options: List<String>? = null, // For select/multiselect
    val inputType: String? = null // For text fields: "text", "number", etc.
) {
    enum class FieldType {
        TEXT,
        TEXTAREA,
        DATE,
        TIME,
        SELECT,
        MULTISELECT,
        GPS,
        PHOTO,
        BARCODE
    }
}

/**
 * Configuration for a complete form
 */
data class FormConfig(
    val id: String,
    val name: String,
    val section: String,
    val description: String?,
    val mandatory: Boolean,
    val fields: List<FormFieldConfig>
)

/**
 * Loader for form configurations from JSON
 */
object FormConfigLoader {
    fun loadFromAssets(context: android.content.Context): List<FormConfig> {
        return try {
            val inputStream = context.assets.open("forms_config.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            parseJson(jsonString)
        } catch (e: Exception) {
            android.util.Log.e("FormConfigLoader", "Error loading form config: ${e.message}", e)
            emptyList()
        }
    }
    
    private fun parseJson(jsonString: String): List<FormConfig> {
        val jsonObject = JSONObject(jsonString)
        val formsArray = jsonObject.getJSONArray("forms")
        val forms = mutableListOf<FormConfig>()
        
        for (i in 0 until formsArray.length()) {
            val formObj = formsArray.getJSONObject(i)
            val fields = parseFields(formObj.getJSONArray("fields"))
            
            forms.add(
                FormConfig(
                    id = formObj.getString("id"),
                    name = formObj.getString("name"),
                    section = formObj.getString("section"),
                    description = formObj.optString("description", null),
                    mandatory = formObj.optBoolean("mandatory", false),
                    fields = fields
                )
            )
        }
        
        return forms
    }
    
    private fun parseFields(fieldsArray: JSONArray): List<FormFieldConfig> {
        val fields = mutableListOf<FormFieldConfig>()
        
        for (i in 0 until fieldsArray.length()) {
            val fieldObj = fieldsArray.getJSONObject(i)
            val typeString = fieldObj.getString("type")
            val fieldType = when (typeString.lowercase()) {
                "text" -> FormFieldConfig.FieldType.TEXT
                "textarea" -> FormFieldConfig.FieldType.TEXTAREA
                "date" -> FormFieldConfig.FieldType.DATE
                "time" -> FormFieldConfig.FieldType.TIME
                "select" -> FormFieldConfig.FieldType.SELECT
                "multiselect" -> FormFieldConfig.FieldType.MULTISELECT
                "gps" -> FormFieldConfig.FieldType.GPS
                "photo" -> FormFieldConfig.FieldType.PHOTO
                "barcode" -> FormFieldConfig.FieldType.BARCODE
                else -> FormFieldConfig.FieldType.TEXT
            }
            
            val options = if (fieldObj.has("options")) {
                val optionsArray = fieldObj.getJSONArray("options")
                (0 until optionsArray.length()).map { optionsArray.getString(it) }
            } else {
                null
            }
            
            fields.add(
                FormFieldConfig(
                    id = fieldObj.getString("id"),
                    label = fieldObj.getString("label"),
                    type = fieldType,
                    required = fieldObj.optBoolean("required", false),
                    options = options,
                    inputType = fieldObj.optString("inputType", null)
                )
            )
        }
        
        return fields
    }
}

