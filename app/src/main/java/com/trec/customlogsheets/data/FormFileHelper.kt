package com.trec.customlogsheets.data

import android.content.Context
import android.util.Xml
import androidx.documentfile.provider.DocumentFile
import com.trec.customlogsheets.data.FolderStructureHelper
import com.trec.customlogsheets.data.SettingsPreferences
import com.trec.customlogsheets.util.AppLogger
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream

/**
 * Helper class for saving and loading form XML files
 */
class FormFileHelper(private val context: Context) {
    
    companion object {
        /**
         * Sanitizes a section name for use in filenames.
         * Replaces spaces with underscores and removes/sanitizes special characters.
         * Empty or null section names are converted to "default".
         * 
         * @param sectionName The section name to sanitize
         * @return Sanitized section name safe for use in filenames
         */
        fun sanitizeSectionName(sectionName: String?): String {
            if (sectionName.isNullOrBlank()) {
                return "default"
            }
            
            // Replace spaces with underscores
            var sanitized = sectionName.replace(" ", "_")
            
            // Remove or replace special characters that are problematic in filenames
            // Characters to remove: / \ : * ? " < > |
            // Replace with underscore: / \ : | (common path/command separators)
            sanitized = sanitized
                .replace("/", "_")
                .replace("\\", "_")
                .replace(":", "_")
                .replace("|", "_")
                .replace("*", "")
                .replace("?", "")
                .replace("\"", "")
                .replace("<", "")
                .replace(">", "")
            
            // Remove leading/trailing underscores and dots (Windows doesn't allow these)
            sanitized = sanitized.trim('_', '.')
            
            // If empty after sanitization, use "default"
            if (sanitized.isEmpty()) {
                return "default"
            }
            
            return sanitized
        }
        
        /**
         * Generates a filename for a form based on section name, form ID, and order in section.
         * Pattern: ${sectionName}_${formId}_${orderInSection}.xml
         * For dynamic forms: ${sectionName}_${formId}_${orderInSection}_${subIndex}.xml
         * 
         * @param sectionName The section name (will be sanitized)
         * @param formId The form ID
         * @param orderInSection The 0-based index of the form within its section
         * @param subIndex The 0-based sub-index for dynamic forms (null for non-dynamic forms)
         * @return The generated filename
         */
        fun generateFileName(sectionName: String?, formId: String, orderInSection: Int, subIndex: Int? = null): String {
            val sanitizedSection = sanitizeSectionName(sectionName)
            return if (subIndex != null) {
                "${sanitizedSection}_${formId}_${orderInSection}_${subIndex}.xml"
            } else {
                "${sanitizedSection}_${formId}_${orderInSection}.xml"
            }
        }
        
        /**
         * Extracts form ID, orderInSection, and subIndex from a filename.
         * Pattern: ${sectionName}_${formId}_${orderInSection}.xml or ${sectionName}_${formId}_${orderInSection}_${subIndex}.xml
         * Since section name and formId might contain underscores, we parse from the end.
         * 
         * @param fileName The filename to parse
         * @param knownFormId Optional: if the formId is already known (e.g., from XML), use it to parse more accurately
         * @return Triple of (formId, orderInSection, subIndex) or null if parsing fails
         */
        fun extractFormIdAndOrderAndSubIndex(fileName: String, knownFormId: String? = null): Triple<String, Int, Int?>? {
            val nameWithoutExt = fileName.removeSuffix(".xml")
            
            // If we know the formId, use it to parse more accurately
            if (knownFormId != null) {
                // Find where the formId ends in the filename
                // Pattern: Section_formId_orderInSection or Section_formId_orderInSection_subIndex
                val formIdSuffix = "_${knownFormId}_"
                val formIdIndex = nameWithoutExt.indexOf(formIdSuffix)
                if (formIdIndex >= 0) {
                    // Found formId, extract what comes after it
                    val afterFormId = nameWithoutExt.substring(formIdIndex + formIdSuffix.length)
                    
                    // Try pattern with sub-index first: orderInSection_subIndex
                    val subIndexPattern = Regex("^(\\d+)_(\\d+)$")
                    val subIndexMatch = subIndexPattern.find(afterFormId)
                    if (subIndexMatch != null) {
                        val orderInSection = subIndexMatch.groupValues[1].toIntOrNull() ?: return null
                        val subIndex = subIndexMatch.groupValues[2].toIntOrNull() ?: return null
                        return Triple(knownFormId, orderInSection, subIndex)
                    }
                    
                    // Try pattern without sub-index: orderInSection
                    val orderPattern = Regex("^(\\d+)$")
                    val orderMatch = orderPattern.find(afterFormId)
                    if (orderMatch != null) {
                        val orderInSection = orderMatch.groupValues[1].toIntOrNull() ?: return null
                        return Triple(knownFormId, orderInSection, null)
                    }
                }
            }
            
            // Fallback to original parsing logic (for backward compatibility or when formId is unknown)
            // Try pattern with sub-index first: ..._orderInSection_subIndex
            val subIndexPattern = Regex("_(\\d+)_(\\d+)$")
            val subIndexMatch = subIndexPattern.find(nameWithoutExt)
            if (subIndexMatch != null) {
                val subIndex = subIndexMatch.groupValues[2].toIntOrNull() ?: return null
                val orderInSection = subIndexMatch.groupValues[1].toIntOrNull() ?: return null
                
                // Extract formId by removing the last two parts (orderInSection_subIndex)
                val beforeSubIndex = nameWithoutExt.substring(0, subIndexMatch.range.first)
                val lastUnderscoreBeforeOrder = beforeSubIndex.lastIndexOf("_")
                if (lastUnderscoreBeforeOrder < 0) return null
                
                val formId = beforeSubIndex.substring(lastUnderscoreBeforeOrder + 1)
                return Triple(formId, orderInSection, subIndex)
            }
            
            // Try pattern without sub-index: ..._orderInSection
            val orderPattern = Regex("_(\\d+)$")
            val orderMatch = orderPattern.find(nameWithoutExt)
            if (orderMatch != null) {
                val orderInSection = orderMatch.groupValues[1].toIntOrNull() ?: return null
                
                // Extract formId by removing the last part (orderInSection)
                val beforeOrder = nameWithoutExt.substring(0, orderMatch.range.first)
                val lastUnderscoreBeforeOrder = beforeOrder.lastIndexOf("_")
                if (lastUnderscoreBeforeOrder < 0) return null
                
                val formId = beforeOrder.substring(lastUnderscoreBeforeOrder + 1)
                return Triple(formId, orderInSection, null)
            }
            
            return null
        }
        
        /**
         * Gets the order index of a form within its section.
         * 
         * @param context The context
         * @param formId The form ID
         * @return The 0-based index of the form in its section, or null if form not found
         */
        fun getOrderInSection(context: Context, formId: String): Int? {
            val forms = PredefinedForms.getForms(context)
            val formConfig = forms.firstOrNull { it.id == formId } ?: return null
            
            val section = formConfig.section
            val formsInSection = PredefinedForms.getFormsBySection(context, section)
            
            return formsInSection.indexOfFirst { it.id == formId }.takeIf { it >= 0 }
        }
    }
    
    /**
     * Saves form data as XML file in the site's folder
     * Draft status is determined by the submittedAt field in the XML content
     * Filename pattern: ${sectionName}_${formId}_${orderInSection}.xml or ${sectionName}_${formId}_${orderInSection}_${subIndex}.xml for dynamic forms
     * @param formData The form data to save
     * @param orderInSection The 0-based index of this specific form instance in its section (required when same formId appears multiple times)
     * @param subIndex The 0-based sub-index for dynamic forms (null for non-dynamic forms)
     * @return true if successful, false otherwise
     */
    fun saveFormData(formData: FormData, orderInSection: Int? = null, subIndex: Int? = null): Boolean {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Get the site folder (in ongoing)
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences) ?: return false
        val siteFolder = ongoingFolder.findFile(formData.siteName) ?: return false
        
        if (!siteFolder.exists() || !siteFolder.canWrite()) {
            return false
        }
        
        // Get form config to determine section
        val formConfig = PredefinedForms.getFormConfig(context, formData.formId)
            ?: run {
                AppLogger.e("FormFileHelper", "Form config not found for formId: ${formData.formId}")
                return false
            }
        
        // Get order in section (0-based)
        // If provided, use it; otherwise calculate it (for backward compatibility)
        val actualOrderInSection = orderInSection ?: getOrderInSection(context, formData.formId)
            ?: run {
                AppLogger.e("FormFileHelper", "Could not determine order in section for formId: ${formData.formId}")
                return false
            }
        
        // Generate filename using new pattern (with sub-index for dynamic forms)
        val fileName = generateFileName(formConfig.section, formData.formId, actualOrderInSection, subIndex)
        
        AppLogger.d("FormFileHelper", "Saving form: site=${formData.siteName}, formId=${formData.formId}, orderInSection=$actualOrderInSection, subIndex=$subIndex, fileName=$fileName")
        
        // Create or update the XML file
        val existingFile = siteFolder.findFile(fileName)
        val isNewFile = existingFile == null || !existingFile.exists()
        
        // Verify that formData has a valid version
        if (formData.logsheetVersion.isEmpty()) {
            AppLogger.e("FormFileHelper", "FormData missing logsheetVersion for formId: ${formData.formId}")
            return false
        }
        
        // If updating an existing file, verify the version matches (don't allow version changes)
        if (!isNewFile) {
            existingFile?.let { file ->
                try {
                    val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
                    val existingXml = inputStream?.bufferedReader().use { it?.readText() ?: "" }
                    inputStream?.close()
                    val existingFormData = FormData.fromXml(existingXml)
                    if (existingFormData == null || existingFormData.logsheetVersion.isEmpty()) {
                        AppLogger.e("FormFileHelper", "Existing file missing logsheetVersion for formId: ${formData.formId}")
                        return false
                    }
                    if (existingFormData.logsheetVersion != formData.logsheetVersion) {
                        AppLogger.e("FormFileHelper", "Version mismatch: existing=${existingFormData.logsheetVersion}, new=${formData.logsheetVersion}")
                        return false
                    }
                } catch (e: Exception) {
                    AppLogger.e("FormFileHelper", "Could not read existing file to verify version: ${e.message}", e)
                    return false
                }
            } ?: run {
                AppLogger.e("FormFileHelper", "Existing file is null for formId: ${formData.formId}")
                return false
            }
        }
        
        // Use the version from formData (already set)
        val formDataWithVersion = formData
        
        val file = if (existingFile != null && existingFile.exists()) {
            existingFile
        } else {
            siteFolder.createFile("text/xml", fileName)
        }
        
        if (file == null || !file.exists()) {
            AppLogger.e("FormFileHelper", "Failed to create/access file: $fileName for site=${formData.siteName}, form=${formData.formId}")
            return false
        }
        
        // Write XML content
        return try {
            val xmlContent = formDataWithVersion.toXml()
            val outputStream: OutputStream? = context.contentResolver.openOutputStream(file.uri)
            if (outputStream == null) {
                AppLogger.e("FormFileHelper", "openOutputStream returned null for file: $fileName")
                // If this was a newly created file, delete it to avoid leaving empty XML on disk
                if (isNewFile) {
                    try {
                        file.delete()
                    } catch (_: Exception) {
                    }
                }
                return false
            }
            
            outputStream.use { it.write(xmlContent.toByteArray()) }
            
            val status = if (formData.submittedAt != null) "submitted" else "draft"
            if (isNewFile) {
                AppLogger.i("FormFileHelper", "Created $status form: site=${formData.siteName}, form=${formData.formId}, file=$fileName")
            } else {
                AppLogger.i("FormFileHelper", "Updated $status form: site=${formData.siteName}, form=${formData.formId}, file=$fileName")
            }
            true
        } catch (e: Exception) {
            AppLogger.e("FormFileHelper", "Error saving form: site=${formData.siteName}, form=${formData.formId}, file=$fileName", e)
            android.util.Log.e("FormFileHelper", "Error saving form: ${e.message}", e)
            // If this was a newly created file, delete it so we don't leave partial/empty XML
            if (isNewFile && existingFile == null) {
                try {
                    val fileToDelete = siteFolder.findFile(fileName)
                    fileToDelete?.delete()
                } catch (_: Exception) {
                }
            }
            false
        }
    }
    
    /**
     * Loads form data from XML file
     * @param siteName The name of the site
     * @param formId The ID of the form
     * @param orderInSection The 0-based index of this specific form instance in its section (required when same formId appears multiple times)
     * @param subIndex The 0-based sub-index for dynamic forms (null for non-dynamic forms)
     * @param loadDraft If true, loads only if it's a draft (submittedAt is null), if false, loads only if submitted (submittedAt is set)
     * @param checkFinished If true, also checks the finished folder (for finalized sites)
     * @return FormData if found, null otherwise
     */
    fun loadFormData(siteName: String, formId: String, orderInSection: Int? = null, subIndex: Int? = null, loadDraft: Boolean = false, checkFinished: Boolean = true): FormData? {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Get form config to determine section
        val formConfig = PredefinedForms.getFormConfig(context, formId) ?: return null
        
        // Get order in section (0-based)
        // If provided, use it; otherwise calculate it (for backward compatibility)
        val actualOrderInSection = orderInSection ?: getOrderInSection(context, formId) ?: return null
        
        // Generate expected filename (with sub-index for dynamic forms)
        val fileName = generateFileName(formConfig.section, formId, actualOrderInSection, subIndex)
        
        AppLogger.d("FormFileHelper", "Loading form: site=$siteName, formId=$formId, orderInSection=$actualOrderInSection, subIndex=$subIndex, fileName=$fileName, loadDraft=$loadDraft")
        
        // Try ongoing folder first
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences)
        val ongoingSiteFolder = ongoingFolder?.findFile(siteName)
        if (ongoingSiteFolder != null && ongoingSiteFolder.exists() && ongoingSiteFolder.canRead()) {
            val file = ongoingSiteFolder.findFile(fileName)
            if (file != null && file.exists() && file.canRead()) {
                return try {
                    val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
                    val xmlContent = inputStream?.bufferedReader().use { it?.readText() ?: "" }
                    inputStream?.close()
                    val formData = FormData.fromXml(xmlContent)
                    // Verify it's the correct formId (safety check)
                    if (formData != null && formData.formId == formId) {
                        val isDraft = formData.submittedAt == null
                        if ((loadDraft && isDraft) || (!loadDraft && !isDraft)) {
                            formData
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FormFileHelper", "Error loading form from ongoing: ${e.message}", e)
                    null
                }
            }
        }
        
        // If not found in ongoing and checkFinished is true, try finished folder
        if (checkFinished) {
            val finishedFolder = folderHelper.getFinishedFolder(settingsPreferences)
            val finishedSiteFolder = finishedFolder?.findFile(siteName)
            if (finishedSiteFolder != null && finishedSiteFolder.exists() && finishedSiteFolder.canRead()) {
                val file = finishedSiteFolder.findFile(fileName)
                if (file != null && file.exists() && file.canRead()) {
                    return try {
                        val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
                        val xmlContent = inputStream?.bufferedReader().use { it?.readText() ?: "" }
                        inputStream?.close()
                        val formData = FormData.fromXml(xmlContent)
                        // Verify it's the correct formId (safety check)
                        if (formData != null && formData.formId == formId) {
                            val isDraft = formData.submittedAt == null
                            if ((loadDraft && isDraft) || (!loadDraft && !isDraft)) {
                                formData
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("FormFileHelper", "Error loading form from finished: ${e.message}", e)
                        null
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * OPTIMIZATION: Loads form data without filtering by draft/submitted status
     * Returns the first form found (draft or submitted), useful when you want to check status after loading
     * This avoids loading the file twice when checking both draft and submitted
     */
    fun loadFormDataAny(siteName: String, formId: String, orderInSection: Int? = null, subIndex: Int? = null, checkFinished: Boolean = true): FormData? {
        return loadFormDataAny(siteName, formId, orderInSection, subIndex, checkFinished, null)
    }
    
    /**
     * OPTIMIZATION: Overload that accepts cached file list to avoid slow findFile() calls
     */
    fun loadFormDataAny(siteName: String, formId: String, orderInSection: Int? = null, subIndex: Int? = null, checkFinished: Boolean = true, cachedFiles: List<DocumentFile>? = null): FormData? {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Get form config to determine section
        val formConfig = PredefinedForms.getFormConfig(context, formId) ?: return null
        
        // Get order in section (0-based)
        val actualOrderInSection = orderInSection ?: getOrderInSection(context, formId) ?: return null
        
        // Generate expected filename (with sub-index for dynamic forms)
        val fileName = generateFileName(formConfig.section, formId, actualOrderInSection, subIndex)
        
        // OPTIMIZATION: If we have cached files, use them instead of findFile()
        if (cachedFiles != null) {
            val file = cachedFiles.firstOrNull { it.name == fileName }
            
            if (file != null && file.exists() && file.canRead()) {
                return try {
                    val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
                    val xmlContent = inputStream?.bufferedReader().use { it?.readText() ?: "" }
                    val formData = FormData.fromXml(xmlContent)
                    
                    // Verify it's the correct formId (safety check)
                    if (formData != null && formData.formId == formId) {
                        formData
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    AppLogger.e("FormFileHelper", "Error loading form from cache: ${e.message}", e)
                    null
                }
            }
            // If not found in cache and checkFinished is false, return null
            if (!checkFinished) {
                return null
            }
        }
        
        // Fallback to findFile() if no cache available
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences)
        val ongoingSiteFolder = ongoingFolder?.findFile(siteName)
        
        if (ongoingSiteFolder != null && ongoingSiteFolder.exists() && ongoingSiteFolder.canRead()) {
            val file = ongoingSiteFolder.findFile(fileName)
            
            if (file != null && file.exists() && file.canRead()) {
                return try {
                    val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
                    val xmlContent = inputStream?.bufferedReader().use { it?.readText() ?: "" }
                    val formData = FormData.fromXml(xmlContent)
                    
                    // Verify it's the correct formId (safety check)
                    if (formData != null && formData.formId == formId) {
                        formData
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    AppLogger.e("FormFileHelper", "Error loading form from ongoing: ${e.message}", e)
                    null
                }
            }
        }
        
        // If not found in ongoing and checkFinished is true, try finished folder
        if (checkFinished) {
            val finishedFolder = folderHelper.getFinishedFolder(settingsPreferences)
            val finishedSiteFolder = finishedFolder?.findFile(siteName)
            
            if (finishedSiteFolder != null && finishedSiteFolder.exists() && finishedSiteFolder.canRead()) {
                val file = finishedSiteFolder.findFile(fileName)
                
                if (file != null && file.exists() && file.canRead()) {
                    return try {
                        val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
                        val xmlContent = inputStream?.bufferedReader().use { it?.readText() ?: "" }
                        val formData = FormData.fromXml(xmlContent)
                        
                        // Verify it's the correct formId (safety check)
                        if (formData != null && formData.formId == formId) {
                            formData
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        AppLogger.e("FormFileHelper", "Error loading form from finished: ${e.message}", e)
                        null
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Checks if a form has been submitted (not just saved as draft)
     * @param orderInSection The 0-based index of this specific form instance in its section (optional, will be calculated if not provided)
     */
    fun isFormSubmitted(siteName: String, formId: String, orderInSection: Int? = null, subIndex: Int? = null): Boolean {
        val formData = loadFormData(siteName, formId, orderInSection, subIndex, loadDraft = false)
        return formData != null && formData.submittedAt != null
    }
    
    /**
     * Checks if a form has a draft version
     * @param orderInSection The 0-based index of this specific form instance in its section (optional, will be calculated if not provided)
     * @param subIndex The 0-based sub-index for dynamic forms (null for non-dynamic forms)
     */
    fun hasDraft(siteName: String, formId: String, orderInSection: Int? = null, subIndex: Int? = null): Boolean {
        val formData = loadFormData(siteName, formId, orderInSection, subIndex, loadDraft = true)
        return formData != null && formData.submittedAt == null
    }
    
    /**
     * Gets all submitted forms for a site (forms with submittedAt set)
     * @param siteName The name of the site
     * @param checkFinished If true, also checks the finished folder (for finalized sites)
     */
    fun getSubmittedForms(siteName: String, checkFinished: Boolean = true): List<String> {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        val formIds = mutableSetOf<String>()
        
        // Helper function to check a folder for submitted forms
        fun checkFolder(siteFolder: DocumentFile?) {
            if (siteFolder != null && siteFolder.exists() && siteFolder.canRead()) {
                val files = siteFolder.listFiles()
                files
                    .filter { file ->
                        val fileName = file.name
                        file.isFile && fileName != null && fileName.endsWith(".xml")
                    }
                    .forEach { file ->
                        try {
                            val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
                            val xmlContent = inputStream?.bufferedReader().use { it?.readText() ?: "" }
                            inputStream?.close()
                            val formData = FormData.fromXml(xmlContent)
                            if (formData != null && formData.submittedAt != null) {
                                formIds.add(formData.formId)
                            }
                        } catch (e: Exception) {
                            AppLogger.e("FormFileHelper", "Error reading form file to check submission status: ${file.name}", e)
                        }
                    }
            }
        }
        
        // Check ongoing folder
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences)
        val ongoingSiteFolder = ongoingFolder?.findFile(siteName)
        checkFolder(ongoingSiteFolder)
        
        // Check finished folder if requested
        if (checkFinished) {
            val finishedFolder = folderHelper.getFinishedFolder(settingsPreferences)
            val finishedSiteFolder = finishedFolder?.findFile(siteName)
            checkFolder(finishedSiteFolder)
        }
        
        return formIds.toList()
    }
    
    /**
     * Efficiently loads all form statuses for a site by reading all XML files once
     * Returns a map of (formId, orderInSection) -> (isSubmitted: Boolean, hasDraft: Boolean)
     * @param siteName The name of the site
     * @param checkFinished If true, also checks the finished folder (for finalized sites)
     * @return Map where key is "formId_orderInSection" and value is Pair(isSubmitted, hasDraft)
     */
    /**
     * OPTIMIZATION: Lightweight parser that only reads formId and submittedAt from the root <form> tag
     * without parsing the entire XML file. This is much faster than full FormData.fromXml parsing.
     */
    private fun parseFormHeaderOnly(inputStream: InputStream): Pair<String?, String?>? {
        return try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, null)
            
            var formId: String? = null
            var submittedAt: String? = null
            var eventType = parser.eventType
            
            // Only parse until we get the form tag attributes, then stop
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "form") {
                            formId = parser.getAttributeValue(null, "formId")
                            submittedAt = parser.getAttributeValue(null, "submittedAt")
                            // We got what we need, break early
                            break
                        }
                    }
                }
                eventType = parser.next()
            }
            
            if (formId != null) {
                Pair(formId, submittedAt)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Data class to hold both status map and file list for caching
     */
    data class FormStatusResult(
        val statusMap: Map<String, Pair<Boolean, Boolean>>,
        val fileList: List<DocumentFile> // Cached file list for reuse
    )
    
    fun getAllFormStatuses(siteName: String, checkFinished: Boolean = true): Map<String, Pair<Boolean, Boolean>> {
        return getAllFormStatusesWithCache(siteName, checkFinished).statusMap
    }
    
    /**
     * OPTIMIZATION: Returns both status map and cached file list to avoid multiple listFiles() calls
     */
    fun getAllFormStatusesWithCache(siteName: String, checkFinished: Boolean = true): FormStatusResult {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        val statusMap = mutableMapOf<String, Pair<Boolean, Boolean>>()
        val allFiles = mutableListOf<DocumentFile>()
        
        // Helper function to process files in a folder
        fun processFolder(siteFolder: DocumentFile?) {
            if (siteFolder != null && siteFolder.exists() && siteFolder.canRead()) {
                val files = siteFolder.listFiles()
                
                // OPTIMIZATION: Use more efficient filtering - check isFile first (cheaper check)
                val xmlFiles = files.filter { file ->
                    file.isFile && file.name?.endsWith(".xml") == true
                }
                allFiles.addAll(xmlFiles)
                
                xmlFiles.forEach { file ->
                    try {
                        val fileName = file.name ?: return@forEach
                        
                        // OPTIMIZATION: Only read formId and submittedAt from XML header, not full file
                        // Use buffered input stream for better performance
                        val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
                        val headerInfo = inputStream?.buffered()?.use { parseFormHeaderOnly(it) }
                        
                        if (headerInfo == null) {
                            return@forEach
                        }
                        
                        val (formId, submittedAt) = headerInfo
                        if (formId == null) {
                            return@forEach
                        }
                        
                        // Parse filename to extract orderInSection and subIndex using known formId
                        val extracted = extractFormIdAndOrderAndSubIndex(fileName, formId)
                        if (extracted == null) {
                            // Invalid format, skip
                            return@forEach
                        }
                        
                        val (_, orderInSection, subIndex) = extracted
                        val key = if (subIndex != null) {
                            "${formId}_${orderInSection}_${subIndex}"
                        } else {
                            "${formId}_${orderInSection}"
                        }
                        val isSubmitted = submittedAt != null
                        val hasDraft = submittedAt == null
                        
                        // Update status map - if already exists, merge (submitted takes precedence)
                        val existing = statusMap[key]
                        if (existing == null) {
                            statusMap[key] = Pair(isSubmitted, hasDraft)
                        } else {
                            // If we find a submitted version, it takes precedence
                            statusMap[key] = Pair(isSubmitted || existing.first, hasDraft || existing.second)
                        }
                    } catch (e: Exception) {
                        AppLogger.e("FormFileHelper", "Error reading form file: ${file.name}", e)
                    }
                }
            }
        }
        
        // Check ongoing folder
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences)
        val ongoingSiteFolder = ongoingFolder?.findFile(siteName)
        processFolder(ongoingSiteFolder)
        
        // Check finished folder if requested
        if (checkFinished) {
            val finishedFolder = folderHelper.getFinishedFolder(settingsPreferences)
            val finishedSiteFolder = finishedFolder?.findFile(siteName)
            processFolder(finishedSiteFolder)
        }
        
        return FormStatusResult(statusMap, allFiles)
    }
    
    /**
     * Gets all forms with draft versions for a site (forms with submittedAt not set)
     * @param siteName The name of the site
     * @param checkFinished If true, also checks the finished folder (for finalized sites)
     */
    fun getDraftForms(siteName: String, checkFinished: Boolean = true): List<String> {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        val formIds = mutableSetOf<String>()
        
        // Helper function to check a folder for draft forms
        fun checkFolder(siteFolder: DocumentFile?) {
            if (siteFolder != null && siteFolder.exists() && siteFolder.canRead()) {
                val files = siteFolder.listFiles()
                files
                    .filter { file ->
                        val fileName = file.name
                        file.isFile && fileName != null && fileName.endsWith(".xml")
                    }
                    .forEach { file ->
                        try {
                            val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
                            val xmlContent = inputStream?.bufferedReader().use { it?.readText() ?: "" }
                            inputStream?.close()
                            val formData = FormData.fromXml(xmlContent)
                            if (formData != null && formData.submittedAt == null) {
                                formIds.add(formData.formId)
                            }
                        } catch (e: Exception) {
                            AppLogger.e("FormFileHelper", "Error reading form file to check draft status: ${file.name}", e)
                        }
                    }
            }
        }
        
        // Check ongoing folder
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences)
        val ongoingSiteFolder = ongoingFolder?.findFile(siteName)
        checkFolder(ongoingSiteFolder)
        
        // Check finished folder if requested
        if (checkFinished) {
            val finishedFolder = folderHelper.getFinishedFolder(settingsPreferences)
            val finishedSiteFolder = finishedFolder?.findFile(siteName)
            checkFolder(finishedSiteFolder)
        }
        
        return formIds.toList()
    }
    
    /**
     * Deletes a form file (draft or submitted)
     * @param siteName The name of the site
     * @param formId The ID of the form
     * @param orderInSection The 0-based index of this specific form instance in its section (optional, will be calculated if not provided)
     * @param isDraft If true, deletes draft version, if false, deletes submitted version
     * @return true if successful, false otherwise
     */
    fun deleteForm(siteName: String, formId: String, orderInSection: Int? = null, subIndex: Int? = null, isDraft: Boolean): Boolean {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Get form config to determine section
        val formConfig = PredefinedForms.getFormConfig(context, formId) ?: return false
        
        // Get order in section (0-based)
        // If provided, use it; otherwise calculate it (for backward compatibility)
        val actualOrderInSection = orderInSection ?: getOrderInSection(context, formId) ?: return false
        
        // Generate expected filename (include subIndex for dynamic forms)
        val fileName = generateFileName(formConfig.section, formId, actualOrderInSection, subIndex)
        
        // Check both ongoing and finished folders
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences)
        val ongoingSiteFolder = ongoingFolder?.findFile(siteName)
        val finishedFolder = folderHelper.getFinishedFolder(settingsPreferences)
        val finishedSiteFolder = finishedFolder?.findFile(siteName)
        
        val siteFolders = listOfNotNull(ongoingSiteFolder, finishedSiteFolder).filter { it.exists() && it.canWrite() }
        if (siteFolders.isEmpty()) {
            AppLogger.w("FormFileHelper", "Site folder not found or not writable: $siteName")
            return false
        }
        
        AppLogger.d("FormFileHelper", "Attempting to delete form: site=$siteName, form=$formId, orderInSection=$actualOrderInSection, subIndex=$subIndex, isDraft=$isDraft, fileName=$fileName")
        
        // Try to delete from any folder that has the file
        for (siteFolder in siteFolders) {
        val file = siteFolder.findFile(fileName)
            if (file != null && file.exists()) {
                AppLogger.d("FormFileHelper", "Found file to delete: ${file.uri}, exists=${file.exists()}, canWrite=${file.canWrite()}")
                try {
                    // Verify it's the correct form before deleting
                    val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
                    val xmlContent = inputStream?.bufferedReader().use { it?.readText() ?: "" }
                    inputStream?.close()
                    val formData = FormData.fromXml(xmlContent)
                    
                    if (formData != null && formData.formId == formId) {
                        val isDraftFile = formData.submittedAt == null
                        if ((isDraft && isDraftFile) || (!isDraft && !isDraftFile)) {
                val deleted = file.delete()
                if (deleted) {
                    AppLogger.i("FormFileHelper", "Deleted form: site=$siteName, form=$formId, isDraft=$isDraft, file=$fileName")
                                return true
                            } else {
                                AppLogger.w("FormFileHelper", "Failed to delete form file (delete() returned false): site=$siteName, form=$formId, file=$fileName, uri=${file.uri}")
                            }
                        } else {
                            AppLogger.w("FormFileHelper", "Form file type mismatch: expected isDraft=$isDraft but file isDraft=$isDraftFile, site=$siteName, form=$formId, file=$fileName")
                            // Still try to delete if the file type doesn't match (might be wrong version)
                            val deleted = file.delete()
                            if (deleted) {
                                AppLogger.i("FormFileHelper", "Deleted form file despite type mismatch: site=$siteName, form=$formId, file=$fileName")
                                return true
                            }
                        }
                    } else {
                        AppLogger.w("FormFileHelper", "Form ID mismatch or null in file: expected=$formId, found=${formData?.formId}, site=$siteName, file=$fileName")
                        // If we can't verify the form ID, still try to delete (file might be corrupted or wrong)
                        // But only if the filename matches what we expect
                        val deleted = file.delete()
                        if (deleted) {
                            AppLogger.i("FormFileHelper", "Deleted form file despite ID mismatch/null: site=$siteName, form=$formId, file=$fileName")
                            return true
                } else {
                            AppLogger.w("FormFileHelper", "Failed to delete file with ID mismatch: site=$siteName, form=$formId, file=$fileName")
                }
                    }
            } catch (e: Exception) {
                    AppLogger.e("FormFileHelper", "Error deleting form: site=$siteName, form=$formId, file=$fileName, error=${e.message}", e)
                    // Try to delete anyway if we can't verify (might be corrupted file)
                    try {
                        if (file.delete()) {
                            AppLogger.w("FormFileHelper", "Deleted form file despite verification error: site=$siteName, form=$formId, file=$fileName")
                            return true
                        }
                    } catch (deleteException: Exception) {
                        AppLogger.e("FormFileHelper", "Failed to delete file even after verification error: ${deleteException.message}", deleteException)
                    }
                }
            }
        }
        
        // File doesn't exist, consider it already deleted
        AppLogger.d("FormFileHelper", "Form file not found (already deleted?): site=$siteName, form=$formId, file=$fileName, orderInSection=$actualOrderInSection, subIndex=$subIndex")
        return true
    }
    
    /**
     * Saves site metadata to site_metadata.xml in the site folder
     * @param siteName The name of the site
     * @param metadata The site metadata to save
     * @return true if successful, false otherwise
     */
    fun saveSiteMetadata(siteName: String, metadata: SiteMetadata): Boolean {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Try ongoing folder first, then finished folder
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences)
        var siteFolder = ongoingFolder?.findFile(siteName)
        
        if (siteFolder == null || !siteFolder.exists()) {
            val finishedFolder = folderHelper.getFinishedFolder(settingsPreferences)
            siteFolder = finishedFolder?.findFile(siteName)
        }
        
        if (siteFolder == null || !siteFolder.exists() || !siteFolder.canWrite()) {
            AppLogger.e("FormFileHelper", "Site folder not found or not writable: $siteName")
            return false
            }
        
        val fileName = "site_metadata.xml"
        val existingFile = siteFolder.findFile(fileName)
        val file = if (existingFile != null && existingFile.exists()) {
            existingFile
        } else {
            siteFolder.createFile("text/xml", fileName)
        }
        
        if (file == null || !file.exists()) {
            AppLogger.e("FormFileHelper", "Failed to create/access site metadata file: $fileName for site=$siteName")
            return false
        }
        
        return try {
            val xmlContent = SiteMetadata.toXml(metadata)
            val outputStream: OutputStream? = context.contentResolver.openOutputStream(file.uri)
            outputStream?.use { it.write(xmlContent.toByteArray()) }
            val success = outputStream != null
            if (success) {
                AppLogger.i("FormFileHelper", "Saved site metadata: site=$siteName, teamConfig=${metadata.teamConfigId}/${metadata.teamConfigVersion}")
            }
            success
        } catch (e: Exception) {
            AppLogger.e("FormFileHelper", "Error saving site metadata: site=$siteName", e)
            false
        }
    }
    
    /**
     * Loads site metadata from site_metadata.xml in the site folder
     * @param siteName The name of the site
     * @return SiteMetadata if found, null otherwise
     */
    fun loadSiteMetadata(siteName: String): SiteMetadata? {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Try ongoing folder first, then finished folder
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences)
        var siteFolder = ongoingFolder?.findFile(siteName)
        
        if (siteFolder == null || !siteFolder.exists()) {
            val finishedFolder = folderHelper.getFinishedFolder(settingsPreferences)
            siteFolder = finishedFolder?.findFile(siteName)
        }
        
        if (siteFolder == null || !siteFolder.exists() || !siteFolder.canRead()) {
            return null
        }
        
        val fileName = "site_metadata.xml"
        val file = siteFolder.findFile(fileName)
        
        if (file == null || !file.exists() || !file.canRead()) {
            return null
        }
        
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
            val xmlContent = inputStream?.bufferedReader().use { it?.readText() ?: "" }
            inputStream?.close()
            SiteMetadata.fromXml(xmlContent)
        } catch (e: Exception) {
            AppLogger.e("FormFileHelper", "Error loading site metadata: site=$siteName", e)
            null
        }
    }
    
    /**
     * Gets all dynamic instances of a form (by sub-index).
     * @param siteName The name of the site
     * @param formId The form ID
     * @param orderInSection The 0-based index of the form within its section
     * @return List of sub-indices that have saved forms (draft or submitted), sorted ascending
     */
    fun getDynamicFormInstances(siteName: String, formId: String, orderInSection: Int): List<Int> {
        val startTime = System.currentTimeMillis()
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Get form config to determine section (check if form exists)
        PredefinedForms.getFormConfig(context, formId) ?: return emptyList()
        
        // Get site folder
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences)
        val ongoingSiteFolder = ongoingFolder?.findFile(siteName)
        val finishedFolder = folderHelper.getFinishedFolder(settingsPreferences)
        val finishedSiteFolder = finishedFolder?.findFile(siteName)
        
        val instances = mutableSetOf<Int>()
        
        // Check both ongoing and finished folders
        listOfNotNull(ongoingSiteFolder, finishedSiteFolder).forEach outer@{ siteFolder ->
            if (siteFolder.exists() && siteFolder.canRead()) {
                val listFilesStartTime = System.currentTimeMillis()
                val files = siteFolder.listFiles()
                val listFilesEndTime = System.currentTimeMillis()
                AppLogger.d("FormFileHelper", "listFiles() for $siteName took ${listFilesEndTime - listFilesStartTime}ms, found ${files.size} files")
                
                files.forEach { file ->
                    val fileName = file.name ?: return@forEach
                    if (file.isFile && fileName.endsWith(".xml") && fileName != "site_metadata.xml") {
                        // Use known formId to parse filename more accurately
                        val extracted = extractFormIdAndOrderAndSubIndex(fileName, formId)
                        if (extracted != null) {
                            val (fileFormId, fileOrderInSection, fileSubIndex) = extracted
                            if (fileFormId == formId && fileOrderInSection == orderInSection && fileSubIndex != null) {
                                instances.add(fileSubIndex)
                            }
                        }
                    }
                }
            }
        }
        
        val endTime = System.currentTimeMillis()
        AppLogger.d("FormFileHelper", "getDynamicFormInstances($siteName, $formId, $orderInSection) took ${endTime - startTime}ms, found ${instances.size} instances")
        return instances.sorted()
    }
    
    /**
     * OPTIMIZATION: Get dynamic form instances from cached file list (avoids listFiles() call)
     */
    fun getDynamicFormInstancesFromCache(files: List<DocumentFile>, formId: String, orderInSection: Int): List<Int> {
        val instances = mutableSetOf<Int>()
        
        files.forEach { file ->
            val fileName = file.name ?: return@forEach
            if (fileName.endsWith(".xml") && fileName != "site_metadata.xml") {
                // Use known formId to parse filename more accurately
                val extracted = extractFormIdAndOrderAndSubIndex(fileName, formId)
                if (extracted != null) {
                    val (fileFormId, fileOrderInSection, fileSubIndex) = extracted
                    if (fileFormId == formId && fileOrderInSection == orderInSection && fileSubIndex != null) {
                        instances.add(fileSubIndex)
                    }
                }
            }
        }
        
        return instances.sorted()
    }
    
    /**
     * Checks if all existing dynamic instances of a form are saved (draft or submitted).
     * This determines if a new instance can be added.
     * @param siteName The name of the site
     * @param formId The form ID
     * @param orderInSection The 0-based index of the form within its section
     * @return true if all instances are saved, false if any instance is unsaved
     */
    fun canAddDynamicFormInstance(siteName: String, formId: String, orderInSection: Int): Boolean {
        val instances = getDynamicFormInstances(siteName, formId, orderInSection)
        if (instances.isEmpty()) {
            return true // No instances yet, can add first one
        }
        
        // Check if all instances have at least a draft or submitted form
        return instances.all { subIndex ->
            val draft = loadFormData(siteName, formId, orderInSection, subIndex, loadDraft = true, checkFinished = true)
            val submitted = loadFormData(siteName, formId, orderInSection, subIndex, loadDraft = false, checkFinished = true)
            draft != null || submitted != null
        }
    }
    
    /**
     * OPTIMIZATION: Check if can add dynamic form instance using cached status map (avoids loading XML files)
     */
    fun canAddDynamicFormInstanceFromStatus(statusMap: Map<String, Pair<Boolean, Boolean>>, formId: String, orderInSection: Int, instances: List<Int>): Boolean {
        if (instances.isEmpty()) {
            return true // No instances yet, can add first one
        }
        
        // Check if all instances have at least a draft or submitted form using status map
        return instances.all { subIndex ->
            val key = "${formId}_${orderInSection}_${subIndex}"
            val status = statusMap[key]
            status != null && (status.first || status.second) // isSubmitted || hasDraft
        }
    }
    
    /**
     * Deletes a dynamic form instance and reindexes all remaining instances.
     * @param siteName The name of the site
     * @param formId The form ID
     * @param orderInSection The 0-based index of the form within its section
     * @param subIndexToDelete The sub-index of the instance to delete
     * @return true if successful, false otherwise
     */
    fun deleteDynamicFormInstance(siteName: String, formId: String, orderInSection: Int, subIndexToDelete: Int): Boolean {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Get form config to determine section
        val formConfig = PredefinedForms.getFormConfig(context, formId) ?: return false
        
        // Get site folder
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences)
        val ongoingSiteFolder = ongoingFolder?.findFile(siteName)
        val finishedFolder = folderHelper.getFinishedFolder(settingsPreferences)
        val finishedSiteFolder = finishedFolder?.findFile(siteName)
        
        val siteFolders = listOfNotNull(ongoingSiteFolder, finishedSiteFolder).filter { it.exists() && it.canWrite() }
        if (siteFolders.isEmpty()) {
            AppLogger.e("FormFileHelper", "Site folder not found or not writable: $siteName")
            return false
        }
        
        // Get all instances
        val allInstances = getDynamicFormInstances(siteName, formId, orderInSection)
        if (!allInstances.contains(subIndexToDelete)) {
            AppLogger.w("FormFileHelper", "Sub-index $subIndexToDelete not found for form $formId")
            return false
        }
        
        // Delete the file(s) for this sub-index (both draft and submitted if they exist)
        var deleted = false
        siteFolders.forEach { siteFolder ->
            val fileNameToDelete = generateFileName(formConfig.section, formId, orderInSection, subIndexToDelete)
            val fileToDelete = siteFolder.findFile(fileNameToDelete)
            if (fileToDelete != null && fileToDelete.exists()) {
                try {
                    if (fileToDelete.delete()) {
                        deleted = true
                        AppLogger.d("FormFileHelper", "Deleted file: $fileNameToDelete")
                    }
                } catch (e: Exception) {
                    AppLogger.e("FormFileHelper", "Error deleting file $fileNameToDelete: ${e.message}", e)
                }
            }
        }
        
        if (!deleted) {
            AppLogger.w("FormFileHelper", "No file found to delete for sub-index $subIndexToDelete")
            return false
        }
        
        // Reindex remaining instances
        val remainingInstances = allInstances.filter { it != subIndexToDelete && it > subIndexToDelete }
        if (remainingInstances.isEmpty()) {
            return true // No reindexing needed
        }
        
        // Rename files in descending order to avoid conflicts
        remainingInstances.sortedDescending().forEach { oldSubIndex ->
            val newSubIndex = oldSubIndex - 1
            val oldFileName = generateFileName(formConfig.section, formId, orderInSection, oldSubIndex)
            val newFileName = generateFileName(formConfig.section, formId, orderInSection, newSubIndex)
            
            siteFolders.forEach { siteFolder ->
                val oldFile = siteFolder.findFile(oldFileName)
                if (oldFile != null && oldFile.exists()) {
                    try {
                        // Read the content
                        val inputStream = context.contentResolver.openInputStream(oldFile.uri)
                        val content = inputStream?.bufferedReader().use { it?.readText() ?: "" }
                        inputStream?.close()
                        
                        if (content.isNotEmpty()) {
                            // Create new file with new name
                            val newFile = siteFolder.createFile("text/xml", newFileName)
                            if (newFile != null) {
                                val outputStream = context.contentResolver.openOutputStream(newFile.uri)
                                outputStream?.use { it.write(content.toByteArray()) }
                                outputStream?.close()
                                
                                // Delete old file
                                oldFile.delete()
                                AppLogger.d("FormFileHelper", "Reindexed: $oldFileName -> $newFileName")
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.e("FormFileHelper", "Error reindexing file $oldFileName: ${e.message}", e)
                    }
                }
            }
        }
        
        return true
    }
}

