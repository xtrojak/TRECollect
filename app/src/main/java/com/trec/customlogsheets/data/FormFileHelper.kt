package com.trec.customlogsheets.data

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.trec.customlogsheets.data.FolderStructureHelper
import com.trec.customlogsheets.data.SettingsPreferences
import com.trec.customlogsheets.util.AppLogger
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
         * 
         * @param sectionName The section name (will be sanitized)
         * @param formId The form ID
         * @param orderInSection The 0-based index of the form within its section
         * @return The generated filename
         */
        fun generateFileName(sectionName: String?, formId: String, orderInSection: Int): String {
            val sanitizedSection = sanitizeSectionName(sectionName)
            return "${sanitizedSection}_${formId}_${orderInSection}.xml"
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
     * Filename pattern: ${sectionName}_${formId}_${orderInSection}.xml
     * @param formData The form data to save
     * @param orderInSection The 0-based index of this specific form instance in its section (required when same formId appears multiple times)
     * @return true if successful, false otherwise
     */
    fun saveFormData(formData: FormData, orderInSection: Int? = null): Boolean {
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
        
        // Generate filename using new pattern
        val fileName = generateFileName(formConfig.section, formData.formId, actualOrderInSection)
        
        AppLogger.d("FormFileHelper", "Saving form: site=${formData.siteName}, formId=${formData.formId}, orderInSection=$actualOrderInSection, fileName=$fileName")
        
        // Create or update the XML file
        val existingFile = siteFolder.findFile(fileName)
        val isNewFile = existingFile == null || !existingFile.exists()
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
            val xmlContent = formData.toXml()
            val outputStream: OutputStream? = context.contentResolver.openOutputStream(file.uri)
            outputStream?.use { it.write(xmlContent.toByteArray()) }
            val success = outputStream != null
            if (success) {
                val status = if (formData.submittedAt != null) "submitted" else "draft"
                if (isNewFile) {
                    AppLogger.i("FormFileHelper", "Created $status form: site=${formData.siteName}, form=${formData.formId}, file=$fileName")
                } else {
                    AppLogger.i("FormFileHelper", "Updated $status form: site=${formData.siteName}, form=${formData.formId}, file=$fileName")
                }
            }
            success
        } catch (e: Exception) {
            AppLogger.e("FormFileHelper", "Error saving form: site=${formData.siteName}, form=${formData.formId}, file=$fileName", e)
            android.util.Log.e("FormFileHelper", "Error saving form: ${e.message}", e)
            false
        }
    }
    
    /**
     * Loads form data from XML file
     * @param siteName The name of the site
     * @param formId The ID of the form
     * @param orderInSection The 0-based index of this specific form instance in its section (required when same formId appears multiple times)
     * @param loadDraft If true, loads only if it's a draft (submittedAt is null), if false, loads only if submitted (submittedAt is set)
     * @param checkFinished If true, also checks the finished folder (for finalized sites)
     * @return FormData if found, null otherwise
     */
    fun loadFormData(siteName: String, formId: String, orderInSection: Int? = null, loadDraft: Boolean = false, checkFinished: Boolean = true): FormData? {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Get form config to determine section
        val formConfig = PredefinedForms.getFormConfig(context, formId) ?: return null
        
        // Get order in section (0-based)
        // If provided, use it; otherwise calculate it (for backward compatibility)
        val actualOrderInSection = orderInSection ?: getOrderInSection(context, formId) ?: return null
        
        // Generate expected filename
        val fileName = generateFileName(formConfig.section, formId, actualOrderInSection)
        
        AppLogger.d("FormFileHelper", "Loading form: site=$siteName, formId=$formId, orderInSection=$actualOrderInSection, fileName=$fileName, loadDraft=$loadDraft")
        
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
     * Checks if a form has been submitted (not just saved as draft)
     * @param orderInSection The 0-based index of this specific form instance in its section (optional, will be calculated if not provided)
     */
    fun isFormSubmitted(siteName: String, formId: String, orderInSection: Int? = null): Boolean {
        val formData = loadFormData(siteName, formId, orderInSection, loadDraft = false)
        return formData != null && formData.submittedAt != null
    }
    
    /**
     * Checks if a form has a draft version
     * @param orderInSection The 0-based index of this specific form instance in its section (optional, will be calculated if not provided)
     */
    fun hasDraft(siteName: String, formId: String, orderInSection: Int? = null): Boolean {
        val formData = loadFormData(siteName, formId, orderInSection, loadDraft = true)
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
    fun getAllFormStatuses(siteName: String, checkFinished: Boolean = true): Map<String, Pair<Boolean, Boolean>> {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        val statusMap = mutableMapOf<String, Pair<Boolean, Boolean>>()
        
        // Helper function to process files in a folder
        fun processFolder(siteFolder: DocumentFile?) {
            if (siteFolder != null && siteFolder.exists() && siteFolder.canRead()) {
                val files = siteFolder.listFiles()
                files
                    .filter { file ->
                        val fileName = file.name
                        file.isFile && fileName != null && fileName.endsWith(".xml")
                    }
                    .forEach { file ->
                        try {
                            val fileName = file.name ?: return@forEach
                            
                            // Read XML first to get formId (more reliable than parsing filename)
                            val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
                            val xmlContent = inputStream?.bufferedReader().use { it?.readText() ?: "" }
                            inputStream?.close()
                            val formData = FormData.fromXml(xmlContent)
                            
                            if (formData == null) {
                                return@forEach
                            }
                            
                            // Parse filename to extract orderInSection
                            // Pattern: ${sectionName}_${formId}_${orderInSection}.xml
                            // Since section name might contain underscores, parse from the end
                            val nameWithoutExt = fileName.removeSuffix(".xml")
                            
                            // Find the last underscore (before orderInSection)
                            val lastUnderscoreIndex = nameWithoutExt.lastIndexOf("_")
                            if (lastUnderscoreIndex < 0) {
                                // Invalid format, skip
                                return@forEach
                            }
                            
                            // Last part is orderInSection
                            val orderInSection = nameWithoutExt.substring(lastUnderscoreIndex + 1).toIntOrNull() ?: return@forEach
                            
                            // Use formId from XML (more reliable) and orderInSection from filename
                            val formId = formData.formId
                            val key = "${formId}_${orderInSection}"
                            val isSubmitted = formData.submittedAt != null
                            val hasDraft = formData.submittedAt == null
                            
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
        
        return statusMap
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
    fun deleteForm(siteName: String, formId: String, orderInSection: Int? = null, isDraft: Boolean): Boolean {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Get the site folder (in ongoing)
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences) ?: return false
        val siteFolder = ongoingFolder.findFile(siteName) ?: return false
        
        if (!siteFolder.exists() || !siteFolder.canWrite()) {
            return false
        }
        
        // Get form config to determine section
        val formConfig = PredefinedForms.getFormConfig(context, formId) ?: return false
        
        // Get order in section (0-based)
        // If provided, use it; otherwise calculate it (for backward compatibility)
        val actualOrderInSection = orderInSection ?: getOrderInSection(context, formId) ?: return false
        
        // Generate expected filename
        val fileName = generateFileName(formConfig.section, formId, actualOrderInSection)
        
        // Find and delete the file
        val file = siteFolder.findFile(fileName)
        return if (file != null && file.exists()) {
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
                        } else {
                            AppLogger.w("FormFileHelper", "Failed to delete form file: site=$siteName, form=$formId, file=$fileName")
                        }
                        deleted
                    } else {
                        AppLogger.w("FormFileHelper", "Form file type mismatch: expected isDraft=$isDraft but file isDraft=$isDraftFile")
                        false
                    }
                } else {
                    AppLogger.w("FormFileHelper", "Form ID mismatch in file: expected=$formId, found=${formData?.formId}")
                    false
                }
            } catch (e: Exception) {
                AppLogger.e("FormFileHelper", "Error deleting form: site=$siteName, form=$formId, file=$fileName", e)
                android.util.Log.e("FormFileHelper", "Error deleting form: ${e.message}", e)
                false
            }
        } else {
            // File doesn't exist, consider it already deleted
            AppLogger.d("FormFileHelper", "Form file not found (already deleted?): site=$siteName, form=$formId, file=$fileName")
            true
        }
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
}

