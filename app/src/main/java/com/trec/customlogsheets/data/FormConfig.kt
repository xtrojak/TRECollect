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
    val instanceName: String? = null, // For dynamic: custom name for instances (e.g., "Sample" instead of "Instance")
    val defaultValue: String? = null // Default value for supported field types
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
    val isDynamic: Boolean = false,
    val dynamicButtonName: String? = null,
    val fields: List<FormFieldConfig>,
    val prefills: Map<String, String> = emptyMap() // Map of widget_id -> value for prefilling form fields
)

/**
 * Loader for form configurations from JSON
 */
object FormConfigLoader {
    private var cachedConfigs: List<FormConfig>? = null
    private var cachedTeam: String? = null
    private var cachedSubteam: String? = null
    private var cachedSiteName: String? = null // Cache for site-specific configs
    
    fun load(context: android.content.Context, team: String? = null, subteam: String? = null): List<FormConfig> {
        // Get team/subteam from SettingsPreferences if not provided
        val actualTeam = team ?: SettingsPreferences(context).getSamplingTeam()
        val actualSubteam = subteam ?: SettingsPreferences(context).getSamplingSubteam()
        
        // Cache the configs to avoid reloading on every call (check if team/subteam changed)
        if (cachedConfigs != null && cachedTeam == actualTeam && cachedSubteam == actualSubteam && cachedSiteName == null) {
            return cachedConfigs!!
        }
        
        // Load from downloaded logsheets
        val configs = loadFromDownloaded(context, actualTeam, actualSubteam)
        cachedConfigs = configs
        cachedTeam = actualTeam
        cachedSubteam = actualSubteam
        cachedSiteName = null
        return configs
    }
    
    /**
     * Loads form configs for a specific site, using the team config version from site metadata
     * Falls back to latest version if metadata is not available or version not found
     */
    fun loadForSite(context: android.content.Context, siteName: String): List<FormConfig> {
        // Check cache first
        if (cachedConfigs != null && cachedSiteName == siteName) {
            return cachedConfigs!!
        }
        
        val formFileHelper = FormFileHelper(context)
        val metadata = formFileHelper.loadSiteMetadata(siteName)
        
        // Get team/subteam from SettingsPreferences
        val settingsPreferences = SettingsPreferences(context)
        val actualTeam = settingsPreferences.getSamplingTeam()
        val actualSubteam = settingsPreferences.getSamplingSubteam()
        
        // If metadata has team config info, try to load that specific version
        val configs = if (metadata != null && metadata.teamConfigId != null && metadata.teamConfigVersion != null) {
            try {
                loadFromDownloadedWithTeamConfigVersion(context, actualTeam, actualSubteam, metadata.teamConfigId, metadata.teamConfigVersion)
                    ?: loadFromDownloaded(context, actualTeam, actualSubteam) // Fallback to latest
            } catch (e: Exception) {
                android.util.Log.w("FormConfigLoader", "Error loading config for site $siteName with version ${metadata.teamConfigVersion}, falling back to latest: ${e.message}")
                loadFromDownloaded(context, actualTeam, actualSubteam) // Fallback to latest
            }
        } else {
            // No metadata or version info, use latest
            loadFromDownloaded(context, actualTeam, actualSubteam)
            }
        
        // Cache the results
            cachedConfigs = configs
            cachedTeam = actualTeam
            cachedSubteam = actualSubteam
        cachedSiteName = siteName
        return configs
    }
    
    /**
     * Loads form configs from downloaded logsheets using a specific team config version
     * @param teamConfigId The team config folder ID
     * @param teamConfigVersion The team config version (e.g., "1.0.0")
     * @return List of FormConfig if successful, null if the specific version is not found
     */
    private fun loadFromDownloadedWithTeamConfigVersion(
        context: android.content.Context,
        team: String,
        subteam: String,
        teamConfigId: String,
        teamConfigVersion: String
    ): List<FormConfig>? {
        val downloader = LogsheetDownloader(context)
        
        // Get the specific version of the team config
        val teamConfigFile = downloader.getTeamConfigFile(teamConfigId, teamConfigVersion)
            ?: run {
                android.util.Log.w("FormConfigLoader", "Team config version $teamConfigVersion not found for ID $teamConfigId")
                return null
            }
        
        // Verify the team config matches the expected team/subteam
        val teamConfigJson = try {
            teamConfigFile.readText()
        } catch (e: Exception) {
            android.util.Log.e("FormConfigLoader", "Error reading team config: ${e.message}", e)
            return null
        }
        
        // Verify it matches the expected team/subteam
        try {
            val configObj = org.json.JSONObject(teamConfigJson)
            val configTeam = configObj.optString("team", "")
            val configName = configObj.optString("name", "")
            
            if (configTeam != team) {
                android.util.Log.w("FormConfigLoader", "Team config version $teamConfigVersion for ID $teamConfigId has team=$configTeam, expected $team")
                return null
            }
            
            // For LSI, verify subteam matches
            if (team == "LSI" && subteam.isNotEmpty()) {
                if (!configName.equals(subteam, ignoreCase = true)) {
                    android.util.Log.w("FormConfigLoader", "Team config version $teamConfigVersion for ID $teamConfigId has name=$configName, expected $subteam")
                    return null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FormConfigLoader", "Error verifying team config: ${e.message}", e)
            return null
        }
        
        // Parse and load forms using this team config
        return loadFormsFromTeamConfig(downloader, teamConfigJson)
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
        
        return loadFormsFromTeamConfig(downloader, teamConfigJson)
    }
    
    /**
     * Helper method to load forms from a team config JSON
     */
    private fun loadFormsFromTeamConfig(
        downloader: LogsheetDownloader,
        teamConfigJson: String
    ): List<FormConfig> {
        // Parse team config to get list of form entries with their positions
        val formEntries = try {
            parseTeamConfig(teamConfigJson)
        } catch (e: Exception) {
            android.util.Log.e("FormConfigLoader", "Error parsing team config: ${e.message}", e)
            return emptyList()
        }
        
        // Load each logsheet config
        val configs = mutableListOf<FormConfig>()
        for (formEntry in formEntries) {
            val logsheetFile = downloader.getLogsheetFile(formEntry.formId) ?: continue
            val logsheetJson = try {
                logsheetFile.readText()
            } catch (e: Exception) {
                android.util.Log.e("FormConfigLoader", "Error reading logsheet ${formEntry.formId}: ${e.message}", e)
                continue
            }
            
            val config = try {
                parseLogsheetConfig(logsheetJson, formEntry, teamConfigJson)
            } catch (e: Exception) {
                android.util.Log.e("FormConfigLoader", "Error parsing logsheet ${formEntry.formId}: ${e.message}", e)
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
     * Data class to hold form entry information from team config
     */
    private data class FormEntry(
        val formId: String,
        val sectionIndex: Int,
        val formIndex: Int,
        val isDynamic: Boolean = false,
        val dynamicButtonName: String? = null
    )
    
    /**
     * Parses team config JSON to extract form entries with their positions
     * Returns list of form entries in order they appear in sections
     * This preserves the ability to have the same form_id multiple times with different titles
     */
    private fun parseTeamConfig(teamConfigJson: String): List<FormEntry> {
        val formEntries = mutableListOf<FormEntry>()
        val jsonObject = org.json.JSONObject(teamConfigJson)
        val sectionsArray = jsonObject.getJSONArray("sections")
        
        for (i in 0 until sectionsArray.length()) {
            val sectionObj = sectionsArray.getJSONObject(i)
            val formsArray = sectionObj.getJSONArray("forms")
            
            for (j in 0 until formsArray.length()) {
                val formObj = formsArray.getJSONObject(j)
                val formId = formObj.getString("form_id")
                // Check if dynamic is an object with button_name
                val dynamicObj = formObj.optJSONObject("dynamic")
                val isDynamic = dynamicObj != null
                val dynamicButtonName = dynamicObj?.optString("button_name")?.takeIf { it.isNotEmpty() }
                formEntries.add(FormEntry(formId, i, j, isDynamic, dynamicButtonName))
            }
        }
        
        return formEntries
    }
    
    /**
     * Parses a single logsheet config JSON and converts it to FormConfig
     * Uses team config to get section and title information
     * @param logsheetJson The logsheet JSON
     * @param formEntry The form entry from team config containing position and dynamic info
     * @param teamConfigJson The team config JSON
     */
    private fun parseLogsheetConfig(logsheetJson: String, formEntry: FormEntry, teamConfigJson: String): FormConfig? {
        val formId = formEntry.formId
        val sectionIndex = formEntry.sectionIndex
        val formIndex = formEntry.formIndex
        val logsheetObj = org.json.JSONObject(logsheetJson)
        val teamObj = org.json.JSONObject(teamConfigJson)
        
        // Find form info from team config using the specific position
        var formName = logsheetObj.optString("name", formId)
        var formSection = "" // Default to empty string (no section name)
        var formDescription = logsheetObj.optString("description").takeIf { it.isNotEmpty() }
        var formMandatory = false
        
        // Get the specific form entry from team config using the position indices
        val sectionsArray = teamObj.getJSONArray("sections")
        if (sectionIndex >= 0 && sectionIndex < sectionsArray.length()) {
            val sectionObj = sectionsArray.getJSONObject(sectionIndex)
            // Section name is optional - use empty string if not specified
            val sectionName = sectionObj.optString("name", "")
            val formsArray = sectionObj.getJSONArray("forms")
            
            if (formIndex >= 0 && formIndex < formsArray.length()) {
                val formObj = formsArray.getJSONObject(formIndex)
                // Verify this is the correct form_id (safety check)
                if (formObj.getString("form_id") == formId) {
                    formName = formObj.optString("title", formName)
                    formSection = sectionName
                    formMandatory = formObj.optBoolean("mandatory", false)
                } else {
                    android.util.Log.w("FormConfigLoader", "Form ID mismatch at section $sectionIndex, form $formIndex: expected '$formId', found '${formObj.getString("form_id")}'")
                }
            }
        }
        
        // Parse prefills from team config
        val prefills = mutableMapOf<String, String>()
        if (sectionIndex >= 0 && sectionIndex < sectionsArray.length()) {
            val sectionObj = sectionsArray.getJSONObject(sectionIndex)
            val formsArray = sectionObj.getJSONArray("forms")
            
            if (formIndex >= 0 && formIndex < formsArray.length()) {
                val formObj = formsArray.getJSONObject(formIndex)
                // Verify this is the correct form_id (safety check)
                if (formObj.getString("form_id") == formId && formObj.has("prefills") && !formObj.isNull("prefills")) {
                    try {
                        val prefillsArray = formObj.getJSONArray("prefills")
                        for (i in 0 until prefillsArray.length()) {
                            val prefillObj = prefillsArray.getJSONObject(i)
                            val widgetId = prefillObj.getString("widget_id")
                            val value = prefillObj.getString("value")
                            prefills[widgetId] = value
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("FormConfigLoader", "Error parsing prefills for form $formId: ${e.message}")
                    }
                }
            }
        }
        
        // Parse fields from logsheet config
        val fieldsArray = logsheetObj.getJSONArray("fields")
        val fields = parseFields(fieldsArray)
        
        // Get dynamic info from form entry
        val isDynamic = formEntry.isDynamic
        val dynamicButtonName = formEntry.dynamicButtonName
        
        return FormConfig(
            id = formId,
            name = formName,
            section = formSection,
            description = formDescription,
            mandatory = formMandatory,
            isDynamic = isDynamic,
            dynamicButtonName = dynamicButtonName,
            fields = fields,
            prefills = prefills
        )
    }
    
    /**
     * Loads a specific FormConfig for a formId and version
     * This is used when loading a form submission that has a pinned version
     * @param context The Android context
     * @param formId The form ID
     * @param version The logsheet version (e.g., "1.0.0")
     * @param siteName Optional site name to get section/title info from team config
     * @return The FormConfig if found, null otherwise
     */
    fun loadFormConfigForVersion(
        context: android.content.Context,
        formId: String,
        version: String,
        siteName: String? = null,
        orderInSection: Int? = null
    ): FormConfig? {
        val downloader = LogsheetDownloader(context)
        
        // Get the specific version of the logsheet
        val logsheetFile = downloader.getLogsheetFile(formId, version)
            ?: run {
                android.util.Log.w("FormConfigLoader", "Logsheet version $version not found for formId $formId")
                return null
            }
        
        val logsheetJson = try {
            logsheetFile.readText()
        } catch (e: Exception) {
            android.util.Log.e("FormConfigLoader", "Error reading logsheet $formId version $version: ${e.message}", e)
            return null
        }
        
        // If siteName is provided, try to get team config for section/title info
        // Otherwise, use defaults from logsheet config
        val teamConfigJson = if (siteName != null) {
            try {
                val formFileHelper = FormFileHelper(context)
                val metadata = formFileHelper.loadSiteMetadata(siteName)
                if (metadata != null && metadata.teamConfigId != null && metadata.teamConfigVersion != null) {
                    val teamConfigFile = downloader.getTeamConfigFile(metadata.teamConfigId, metadata.teamConfigVersion)
                    teamConfigFile?.readText()
                } else {
                    null
                }
            } catch (e: Exception) {
                android.util.Log.w("FormConfigLoader", "Could not load team config for site $siteName: ${e.message}")
                null
                    }
        } else {
            null
        }
        
        // If we have team config, try to find the form entry to get section/title
        // Otherwise, use defaults from logsheet config
        return if (teamConfigJson != null) {
            try {
                val formEntries = parseTeamConfig(teamConfigJson)
                val formEntry = if (orderInSection != null) {
                    // Find the specific instance by counting occurrences
                    var instanceCount = 0
                    formEntries.firstOrNull { entry ->
                        if (entry.formId == formId) {
                            if (instanceCount == orderInSection) {
                                true
                            } else {
                                instanceCount++
                                false
                            }
                        } else {
                            false
                        }
                    }
                } else {
                    formEntries.firstOrNull { it.formId == formId }
                }
                if (formEntry != null) {
                    parseLogsheetConfig(logsheetJson, formEntry, teamConfigJson)
                } else {
                    // Form not found in team config, use defaults
                    val defaultEntry = FormEntry(formId, -1, -1, false, null)
                    parseLogsheetConfig(logsheetJson, defaultEntry, teamConfigJson)
                }
            } catch (e: Exception) {
                android.util.Log.w("FormConfigLoader", "Error parsing team config for form $formId: ${e.message}")
                val defaultEntry = FormEntry(formId, -1, -1, false, null)
                parseLogsheetConfig(logsheetJson, defaultEntry, teamConfigJson)
            }
        } else {
            // No team config, use defaults from logsheet config
            val defaultEntry = FormEntry(formId, -1, -1, false, null)
            parseLogsheetConfig(logsheetJson, defaultEntry, "{\"sections\":[]}")
        }
    }
    
    fun clearCache() {
        cachedConfigs = null
        cachedTeam = null
        cachedSubteam = null
        cachedSiteName = null
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
                            fields = fields,
                            prefills = emptyMap() // parseJson doesn't have team config, so no prefills
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
                
                // Parse default_value (only for supported field types)
                val defaultValue = if (fieldObj.has("default_value") && !fieldObj.isNull("default_value")) {
                    val defaultValueString = fieldObj.getString("default_value")
                    // Only allow default values for supported types
                    when (fieldType) {
                        FormFieldConfig.FieldType.TEXT,
                        FormFieldConfig.FieldType.TEXTAREA,
                        FormFieldConfig.FieldType.SELECT,
                        FormFieldConfig.FieldType.MULTISELECT,
                        FormFieldConfig.FieldType.SELECT_IMAGE,
                        FormFieldConfig.FieldType.MULTISELECT_IMAGE,
                        FormFieldConfig.FieldType.DATE,
                        FormFieldConfig.FieldType.TIME -> defaultValueString.takeIf { it.isNotEmpty() }
                        else -> null
                    }
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
                        instanceName = instanceName,
                        defaultValue = defaultValue
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

