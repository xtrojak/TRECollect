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
    
    /**
     * Saves form data as XML file in the site's folder
     * Draft status is determined by the submittedAt field in the XML content
     * @param formData The form data to save
     * @return true if successful, false otherwise
     */
    fun saveFormData(formData: FormData): Boolean {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Get the site folder (in ongoing)
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences) ?: return false
        val siteFolder = ongoingFolder.findFile(formData.siteName) ?: return false
        
        if (!siteFolder.exists() || !siteFolder.canWrite()) {
            return false
        }
        
        // Always use regular filename (no _draft suffix)
        // Draft status is determined by submittedAt field in XML
        val fileName = "${formData.formId}.xml"
        
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
     * @param loadDraft If true, loads only if it's a draft (submittedAt is null), if false, loads only if submitted (submittedAt is set)
     * @param checkFinished If true, also checks the finished folder (for finalized sites)
     * @return FormData if found, null otherwise
     */
    fun loadFormData(siteName: String, formId: String, loadDraft: Boolean = false, checkFinished: Boolean = true): FormData? {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Always use regular filename
        val fileName = "${formId}.xml"
        
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
                    // Check if it matches the requested type (draft or submitted)
                    if (formData != null) {
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
                        // Check if it matches the requested type (draft or submitted)
                        if (formData != null) {
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
     */
    fun isFormSubmitted(siteName: String, formId: String): Boolean {
        val formData = loadFormData(siteName, formId, loadDraft = false)
        return formData != null && formData.submittedAt != null
    }
    
    /**
     * Checks if a form has a draft version
     */
    fun hasDraft(siteName: String, formId: String): Boolean {
        val formData = loadFormData(siteName, formId, loadDraft = true)
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
     * @param isDraft If true, deletes draft version, if false, deletes submitted version
     * @return true if successful, false otherwise
     */
    fun deleteForm(siteName: String, formId: String, isDraft: Boolean): Boolean {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Get the site folder (in ongoing)
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences) ?: return false
        val siteFolder = ongoingFolder.findFile(siteName) ?: return false
        
        if (!siteFolder.exists() || !siteFolder.canWrite()) {
            return false
        }
        
        // Always use regular filename (no _draft suffix)
        val fileName = "${formId}.xml"
        
        // Find and delete the file
        val file = siteFolder.findFile(fileName)
        return if (file != null && file.exists()) {
            try {
                val deleted = file.delete()
                if (deleted) {
                    AppLogger.i("FormFileHelper", "Deleted form: site=$siteName, form=$formId, isDraft=$isDraft, file=$fileName")
                } else {
                    AppLogger.w("FormFileHelper", "Failed to delete form file: site=$siteName, form=$formId, file=$fileName")
                }
                deleted
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
}

