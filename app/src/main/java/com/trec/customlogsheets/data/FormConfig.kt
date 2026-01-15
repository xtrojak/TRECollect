package com.trec.customlogsheets.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents an option with an image and optional label
 */
data class ImageOption(
    val value: String, // The value stored when selected
    val imagePath: String, // Path to image in assets (e.g., "images/option1.png")
    val label: String? = null // Optional text label to display with the image
)

/**
 * Configuration for a form field
 */
data class FormFieldConfig(
    val id: String,
    val label: String,
    val type: FieldType,
    val required: Boolean = false,
    val options: List<String>? = null, // For select/multiselect (text-based)
    val imageOptions: List<ImageOption>? = null, // For select_image/multiselect_image (image-based)
    val inputType: String? = null, // For text fields: "text", "number", etc.
    val rows: List<String>? = null, // For table: row names
    val columns: List<String>? = null, // For table: column names
    val subFields: List<FormFieldConfig>? = null, // For dynamic: sub-widgets to repeat
    val instanceName: String? = null // For dynamic: custom name for instances (e.g., "Sample" instead of "Instance")
) {
    enum class FieldType {
        TEXT,
        TEXTAREA,
        DATE,
        TIME,
        SELECT,
        MULTISELECT,
        SELECT_IMAGE, // Single select with images
        MULTISELECT_IMAGE, // Multi-select with images
        GPS,
        PHOTO,
        BARCODE,
        SECTION, // Section header (display only, not collected)
        TABLE, // Table with rows and columns
        DYNAMIC // Dynamic/repeatable widget with sub-fields
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
    private var cachedTeam: String? = null
    private var cachedSubteam: String? = null
    
    fun loadFromAssets(context: android.content.Context, team: String? = null, subteam: String? = null): List<FormConfig> {
        // Get team/subteam from SettingsPreferences if not provided
        val actualTeam = team ?: SettingsPreferences(context).getSamplingTeam()
        val actualSubteam = subteam ?: SettingsPreferences(context).getSamplingSubteam()
        
        // Cache the configs to avoid reloading on every call (check if team/subteam changed)
        if (cachedConfigs != null && cachedTeam == actualTeam && cachedSubteam == actualSubteam) {
            return cachedConfigs!!
        }
        
        // Determine the config file path based on team/subteam
        val configPath = when {
            actualTeam == "LSI" && actualSubteam.isNotEmpty() -> "teams/LSI/$actualSubteam/forms_config.json"
            actualTeam == "AML" -> "teams/AML/forms_config.json"
            else -> "forms_config.json" // Fallback to root for backwards compatibility
        }
        
        return try {
            val inputStream = context.assets.open(configPath)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val configs = parseJson(jsonString)
            try {
                android.util.Log.d("FormConfigLoader", "Loaded ${configs.size} forms from $configPath: ${configs.map { it.id }}")
            } catch (e: Exception) {
                // Ignore logging errors in test environments
            }
            cachedConfigs = configs
            cachedTeam = actualTeam
            cachedSubteam = actualSubteam
            configs
        } catch (e: Exception) {
            try {
                android.util.Log.e("FormConfigLoader", "Error loading form config from $configPath: ${e.message}", e)
                // Try fallback to root forms_config.json if team-specific config doesn't exist
                if (configPath != "forms_config.json") {
                    android.util.Log.d("FormConfigLoader", "Trying fallback to root forms_config.json")
                    try {
                        val fallbackStream = context.assets.open("forms_config.json")
                        val fallbackJson = fallbackStream.bufferedReader().use { it.readText() }
                        val fallbackConfigs = parseJson(fallbackJson)
                        cachedConfigs = fallbackConfigs
                        cachedTeam = actualTeam
                        cachedSubteam = actualSubteam
                        return fallbackConfigs
                    } catch (fallbackError: Exception) {
                        android.util.Log.e("FormConfigLoader", "Fallback also failed: ${fallbackError.message}")
                    }
                }
            } catch (logError: Exception) {
                // Ignore logging errors in test environments
            }
            e.printStackTrace()
            emptyList()
        }
    }
    
    fun clearCache() {
        cachedConfigs = null
        cachedTeam = null
        cachedSubteam = null
    }
    
    internal fun parseJson(jsonString: String): List<FormConfig> {
        val forms = mutableListOf<FormConfig>()
        
        try {
            val jsonObject = JSONObject(jsonString)
            // Use optJSONArray to handle missing or null "forms" key gracefully
            val formsArray = jsonObject.optJSONArray("forms")
            
            // If forms array is missing or null, return empty list
            if (formsArray == null) {
                return forms
            }
            
            for (i in 0 until formsArray.length()) {
                try {
                    val formObj = formsArray.getJSONObject(i)
                    val fields = parseFields(formObj.getJSONArray("fields"))
                    
                    forms.add(
                        FormConfig(
                            id = formObj.getString("id"),
                            name = formObj.getString("name"),
                            section = formObj.getString("section"),
                            description = formObj.optString("description").takeIf { it.isNotEmpty() },
                            mandatory = formObj.optBoolean("mandatory", false),
                            fields = fields
                        )
                    )
                } catch (e: Exception) {
                    // Silently continue with next form (logging disabled for unit tests)
                    // Continue with next form
                }
            }
        } catch (e: Exception) {
            // Return empty list on any parsing error (logging disabled for unit tests)
            return forms
        }
        
        return forms
    }
    
    internal fun parseFields(fieldsArray: JSONArray): List<FormFieldConfig> {
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
                    "select_image" -> FormFieldConfig.FieldType.SELECT_IMAGE
                    "multiselect_image" -> FormFieldConfig.FieldType.MULTISELECT_IMAGE
                    "gps" -> FormFieldConfig.FieldType.GPS
                    "photo" -> FormFieldConfig.FieldType.PHOTO
                    "barcode" -> FormFieldConfig.FieldType.BARCODE
                    "section" -> FormFieldConfig.FieldType.SECTION
                    "table" -> FormFieldConfig.FieldType.TABLE
                    "dynamic" -> FormFieldConfig.FieldType.DYNAMIC
                    else -> {
                        // Unknown field type, default to TEXT (logging disabled for unit tests)
                        FormFieldConfig.FieldType.TEXT
                    }
                }
                
                // Parse text-based options (for select/multiselect)
                val options = if ((fieldType == FormFieldConfig.FieldType.SELECT || fieldType == FormFieldConfig.FieldType.MULTISELECT) 
                    && fieldObj.has("options") && !fieldObj.isNull("options")) {
                    val optionsArray = fieldObj.getJSONArray("options")
                    (0 until optionsArray.length()).map { optionsArray.getString(it) }
                } else {
                    null
                }
                
                // Parse image-based options (for select_image/multiselect_image)
                val imageOptions = if ((fieldType == FormFieldConfig.FieldType.SELECT_IMAGE || fieldType == FormFieldConfig.FieldType.MULTISELECT_IMAGE)
                    && fieldObj.has("options") && !fieldObj.isNull("options")) {
                    val optionsArray = fieldObj.getJSONArray("options")
                    (0 until optionsArray.length()).mapNotNull { index ->
                        val optionObj = optionsArray.getJSONObject(index)
                        try {
                            ImageOption(
                                value = optionObj.getString("value"),
                                imagePath = optionObj.getString("image"),
                                label = optionObj.optString("label").takeIf { it.isNotEmpty() }
                            )
                        } catch (e: Exception) {
                            // Skip invalid option entries
                            null
                        }
                    }
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
                
                val subFields = if (fieldType == FormFieldConfig.FieldType.DYNAMIC && fieldObj.has("subFields") && !fieldObj.isNull("subFields")) {
                    parseFields(fieldObj.getJSONArray("subFields"))
                } else {
                    null
                }
                
                val instanceName = if (fieldType == FormFieldConfig.FieldType.DYNAMIC) {
                    fieldObj.optString("instance_name").takeIf { it.isNotEmpty() }
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
                        imageOptions = imageOptions,
                        inputType = if (fieldType == FormFieldConfig.FieldType.SECTION) null else fieldObj.optString("inputType").takeIf { it.isNotEmpty() },
                        rows = if (fieldType == FormFieldConfig.FieldType.TABLE) rows else null,
                        columns = if (fieldType == FormFieldConfig.FieldType.TABLE) columns else null,
                        subFields = subFields,
                        instanceName = instanceName
                    )
                )
            }
        } catch (e: Exception) {
            // Re-throw the exception (logging disabled for unit tests)
            throw e
        }
        
        return fields
    }
}

