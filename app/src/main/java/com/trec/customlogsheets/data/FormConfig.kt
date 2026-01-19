package com.trec.customlogsheets.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents an option with an image and optional label
 */
data class ImageOption(
    val value: String, // The value stored when selected
    val imagePath: String, // Path to image (e.g., "images/option1.png")
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
    val imagePath: String? = null, // For image_display: path to image (e.g., "images/image.png")
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
        IMAGE_DISPLAY, // Image display (display only, not collected)
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
    
    fun load(context: android.content.Context, team: String? = null, subteam: String? = null): List<FormConfig> {
        // Get team/subteam from SettingsPreferences if not provided
        val actualTeam = team ?: SettingsPreferences(context).getSamplingTeam()
        val actualSubteam = subteam ?: SettingsPreferences(context).getSamplingSubteam()
        
        // Cache the configs to avoid reloading on every call (check if team/subteam changed)
        if (cachedConfigs != null && cachedTeam == actualTeam && cachedSubteam == actualSubteam) {
            return cachedConfigs!!
        }
        
        // Load from downloaded logsheets
        val configs = loadFromDownloaded(context, actualTeam, actualSubteam)
        cachedConfigs = configs
        cachedTeam = actualTeam
        cachedSubteam = actualSubteam
        return configs
    }
    
    /**
     * Loads form configs from downloaded logsheets
     * Uses team config to determine which logsheets to load
     */
    private fun loadFromDownloaded(context: android.content.Context, team: String, subteam: String): List<FormConfig> {
        val downloader = LogsheetDownloader(context)
        
        // Find team config by matching team and name/subteam fields dynamically
        // This allows us to discover team configs without hardcoding folder names
        val teamConfigFile = downloader.findTeamConfigByTeamAndName(team, subteam.takeIf { it.isNotEmpty() })
            ?: run {
                android.util.Log.e("FormConfigLoader", "No team config found for team=$team, subteam=$subteam")
                return emptyList()
            }
        val teamConfigJson = try {
            teamConfigFile.readText()
        } catch (e: Exception) {
            android.util.Log.e("FormConfigLoader", "Error reading team config: ${e.message}", e)
            return emptyList()
        }
        
        // Parse team config to get list of form IDs
        val formIds = try {
            parseTeamConfig(teamConfigJson)
        } catch (e: Exception) {
            android.util.Log.e("FormConfigLoader", "Error parsing team config: ${e.message}", e)
            return emptyList()
        }
        
        // Load each logsheet config
        val configs = mutableListOf<FormConfig>()
        for (formId in formIds) {
            val logsheetFile = downloader.getLogsheetFile(formId) ?: continue
            val logsheetJson = try {
                logsheetFile.readText()
            } catch (e: Exception) {
                android.util.Log.e("FormConfigLoader", "Error reading logsheet $formId: ${e.message}", e)
                continue
            }
            
            val config = try {
                parseLogsheetConfig(logsheetJson, formId, teamConfigJson)
            } catch (e: Exception) {
                android.util.Log.e("FormConfigLoader", "Error parsing logsheet $formId: ${e.message}", e)
                continue
            }
            
            if (config != null) {
                configs.add(config)
            }
        }
        
        try {
            android.util.Log.d("FormConfigLoader", "Loaded ${configs.size} forms from downloaded logsheets: ${configs.map { it.id }}")
        } catch (e: Exception) {
            // Ignore logging errors in test environments
        }
        
        return configs
    }
    
    /**
     * Parses team config JSON to extract form IDs and their organization
     * Returns list of form IDs in order they appear in sections
     */
    private fun parseTeamConfig(teamConfigJson: String): List<String> {
        val formIds = mutableListOf<String>()
        val jsonObject = org.json.JSONObject(teamConfigJson)
        val sectionsArray = jsonObject.getJSONArray("sections")
        
        for (i in 0 until sectionsArray.length()) {
            val sectionObj = sectionsArray.getJSONObject(i)
            val formsArray = sectionObj.getJSONArray("forms")
            
            for (j in 0 until formsArray.length()) {
                val formObj = formsArray.getJSONObject(j)
                val formId = formObj.getString("form_id")
                formIds.add(formId)
            }
        }
        
        return formIds
    }
    
    /**
     * Parses a single logsheet config JSON and converts it to FormConfig
     * Uses team config to get section and title information
     */
    private fun parseLogsheetConfig(logsheetJson: String, formId: String, teamConfigJson: String): FormConfig? {
        val logsheetObj = org.json.JSONObject(logsheetJson)
        val teamObj = org.json.JSONObject(teamConfigJson)
        
        // Find form info from team config
        var formName = logsheetObj.optString("name", formId)
        var formSection = "" // Default to empty string (no section name)
        var formDescription = logsheetObj.optString("description").takeIf { it.isNotEmpty() }
        var formMandatory = false
        
        // Search team config for this form_id
        val sectionsArray = teamObj.getJSONArray("sections")
        for (i in 0 until sectionsArray.length()) {
            val sectionObj = sectionsArray.getJSONObject(i)
            // Section name is optional - use empty string if not specified
            val sectionName = sectionObj.optString("name", "")
            val formsArray = sectionObj.getJSONArray("forms")
            
            for (j in 0 until formsArray.length()) {
                val formObj = formsArray.getJSONObject(j)
                if (formObj.getString("form_id") == formId) {
                    formName = formObj.optString("title", formName)
                    formSection = sectionName
                    formMandatory = formObj.optBoolean("mandatory", false)
                    break
                }
            }
        }
        
        // Parse fields from logsheet config
        val fieldsArray = logsheetObj.getJSONArray("fields")
        val fields = parseFields(fieldsArray)
        
        return FormConfig(
            id = formId,
            name = formName,
            section = formSection,
            description = formDescription,
            mandatory = formMandatory,
            fields = fields
        )
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
                    "image_display" -> FormFieldConfig.FieldType.IMAGE_DISPLAY
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
                
                // Parse imagePath for image_display type
                val imagePath = if (fieldType == FormFieldConfig.FieldType.IMAGE_DISPLAY) {
                    fieldObj.optString("image").takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                
                // Section headers and image displays don't need options, inputType, or required flag
                val isDisplayOnly = fieldType == FormFieldConfig.FieldType.SECTION || fieldType == FormFieldConfig.FieldType.IMAGE_DISPLAY
                fields.add(
                    FormFieldConfig(
                        id = fieldObj.getString("id"),
                        label = fieldObj.getString("label"),
                        type = fieldType,
                        required = if (isDisplayOnly) false else fieldObj.optBoolean("required", false),
                        options = if (isDisplayOnly) null else options,
                        imageOptions = imageOptions,
                        imagePath = imagePath,
                        inputType = if (isDisplayOnly) null else fieldObj.optString("inputType").takeIf { it.isNotEmpty() },
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

