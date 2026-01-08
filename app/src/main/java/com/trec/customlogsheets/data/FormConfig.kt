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
    val inputType: String? = null, // For text fields: "text", "number", etc.
    val rows: List<String>? = null, // For table: row names
    val columns: List<String>? = null // For table: column names
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
        BARCODE,
        SECTION, // Section header (display only, not collected)
        TABLE // Table with rows and columns
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
    private var cachedConfigs: List<FormConfig>? = null
    
    fun loadFromAssets(context: android.content.Context): List<FormConfig> {
        // Cache the configs to avoid reloading on every call
        if (cachedConfigs != null) {
            return cachedConfigs!!
        }
        
        return try {
            val inputStream = context.assets.open("forms_config.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val configs = parseJson(jsonString)
            android.util.Log.d("FormConfigLoader", "Loaded ${configs.size} forms: ${configs.map { it.id }}")
            cachedConfigs = configs
            configs
        } catch (e: Exception) {
            android.util.Log.e("FormConfigLoader", "Error loading form config: ${e.message}", e)
            e.printStackTrace()
            emptyList()
        }
    }
    
    fun clearCache() {
        cachedConfigs = null
    }
    
    private fun parseJson(jsonString: String): List<FormConfig> {
        val forms = mutableListOf<FormConfig>()
        
        try {
            val jsonObject = JSONObject(jsonString)
            val formsArray = jsonObject.getJSONArray("forms")
            
            for (i in 0 until formsArray.length()) {
                try {
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
                } catch (e: Exception) {
                    android.util.Log.e("FormConfigLoader", "Error parsing form at index $i: ${e.message}", e)
                    // Continue with next form
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FormConfigLoader", "Error parsing JSON: ${e.message}", e)
            throw e
        }
        
        return forms
    }
    
    private fun parseFields(fieldsArray: JSONArray): List<FormFieldConfig> {
        val fields = mutableListOf<FormFieldConfig>()
        
        try {
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
                    "section" -> FormFieldConfig.FieldType.SECTION
                    "table" -> FormFieldConfig.FieldType.TABLE
                    else -> {
                        android.util.Log.w("FormConfigLoader", "Unknown field type: $typeString, defaulting to TEXT")
                        FormFieldConfig.FieldType.TEXT
                    }
                }
                
                val options = if (fieldObj.has("options") && !fieldObj.isNull("options")) {
                    val optionsArray = fieldObj.getJSONArray("options")
                    (0 until optionsArray.length()).map { optionsArray.getString(it) }
                } else {
                    null
                }
                
                val rows = if (fieldObj.has("rows") && !fieldObj.isNull("rows")) {
                    val rowsArray = fieldObj.getJSONArray("rows")
                    (0 until rowsArray.length()).map { rowsArray.getString(it) }
                } else {
                    null
                }
                
                val columns = if (fieldObj.has("columns") && !fieldObj.isNull("columns")) {
                    val columnsArray = fieldObj.getJSONArray("columns")
                    (0 until columnsArray.length()).map { columnsArray.getString(it) }
                } else {
                    null
                }
                
                // Section headers don't need options, inputType, or required flag
                fields.add(
                    FormFieldConfig(
                        id = fieldObj.getString("id"),
                        label = fieldObj.getString("label"),
                        type = fieldType,
                        required = if (fieldType == FormFieldConfig.FieldType.SECTION) false else fieldObj.optBoolean("required", false),
                        options = if (fieldType == FormFieldConfig.FieldType.SECTION) null else options,
                        inputType = if (fieldType == FormFieldConfig.FieldType.SECTION) null else fieldObj.optString("inputType", null),
                        rows = if (fieldType == FormFieldConfig.FieldType.TABLE) rows else null,
                        columns = if (fieldType == FormFieldConfig.FieldType.TABLE) columns else null
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("FormConfigLoader", "Error parsing fields: ${e.message}", e)
            throw e
        }
        
        return fields
    }
}

