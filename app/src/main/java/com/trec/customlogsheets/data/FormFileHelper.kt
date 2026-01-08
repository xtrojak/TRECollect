package com.trec.customlogsheets.data

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.trec.customlogsheets.data.FolderStructureHelper
import com.trec.customlogsheets.data.SettingsPreferences
import java.io.InputStream
import java.io.OutputStream

/**
 * Helper class for saving and loading form XML files
 */
class FormFileHelper(private val context: Context) {
    
    /**
     * Saves form data as XML file in the site's folder
     * @param formData The form data to save
     * @param isDraft If true, saves as draft (can be edited later), if false, saves as submitted
     * @return true if successful, false otherwise
     */
    fun saveFormData(formData: FormData, isDraft: Boolean = false): Boolean {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Get the site folder (in ongoing)
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences) ?: return false
        val siteFolder = ongoingFolder.findFile(formData.siteName) ?: return false
        
        if (!siteFolder.exists() || !siteFolder.canWrite()) {
            return false
        }
        
        // Determine filename: draft files have "_draft" suffix
        val fileName = if (isDraft) {
            "${formData.formId}_draft.xml"
        } else {
            "${formData.formId}.xml"
        }
        
        // Check if draft exists and delete it when submitting
        if (!isDraft) {
            val draftFile = siteFolder.findFile("${formData.formId}_draft.xml")
            draftFile?.delete()
        }
        
        // Create or update the XML file
        val existingFile = siteFolder.findFile(fileName)
        val file = if (existingFile != null && existingFile.exists()) {
            existingFile
        } else {
            siteFolder.createFile("text/xml", fileName)
        }
        
        if (file == null || !file.exists()) {
            return false
        }
        
        // Write XML content
        return try {
            val xmlContent = formData.toXml()
            val outputStream: OutputStream? = context.contentResolver.openOutputStream(file.uri)
            outputStream?.use { it.write(xmlContent.toByteArray()) }
            outputStream != null
        } catch (e: Exception) {
            android.util.Log.e("FormFileHelper", "Error saving form: ${e.message}", e)
            false
        }
    }
    
    /**
     * Loads form data from XML file
     * @param siteName The name of the site
     * @param formId The ID of the form
     * @param loadDraft If true, loads draft version, if false, loads submitted version
     * @param checkFinished If true, also checks the finished folder (for finalized sites)
     * @return FormData if found, null otherwise
     */
    fun loadFormData(siteName: String, formId: String, loadDraft: Boolean = false, checkFinished: Boolean = true): FormData? {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Determine filename
        val fileName = if (loadDraft) {
            "${formId}_draft.xml"
        } else {
            "${formId}.xml"
        }
        
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
                    FormData.fromXml(xmlContent)
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
                        FormData.fromXml(xmlContent)
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
        return formData != null && formData.isSubmitted
    }
    
    /**
     * Checks if a form has a draft version
     */
    fun hasDraft(siteName: String, formId: String): Boolean {
        val formData = loadFormData(siteName, formId, loadDraft = true)
        return formData != null
    }
    
    /**
     * Gets all submitted forms for a site
     * @param siteName The name of the site
     * @param checkFinished If true, also checks the finished folder (for finalized sites)
     */
    fun getSubmittedForms(siteName: String, checkFinished: Boolean = true): List<String> {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        val formIds = mutableSetOf<String>()
        
        // Check ongoing folder
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences)
        val ongoingSiteFolder = ongoingFolder?.findFile(siteName)
        if (ongoingSiteFolder != null && ongoingSiteFolder.exists() && ongoingSiteFolder.canRead()) {
            val files = ongoingSiteFolder.listFiles()
            files
                .filter { file ->
                    val fileName = file.name
                    file.isFile && 
                    fileName != null && 
                    fileName.endsWith(".xml") && 
                    !fileName.endsWith("_draft.xml")
                }
                .mapNotNull { 
                    val name = it.name ?: return@mapNotNull null
                    name.removeSuffix(".xml")
                }
                .forEach { formIds.add(it) }
        }
        
        // Check finished folder if requested
        if (checkFinished) {
            val finishedFolder = folderHelper.getFinishedFolder(settingsPreferences)
            val finishedSiteFolder = finishedFolder?.findFile(siteName)
            if (finishedSiteFolder != null && finishedSiteFolder.exists() && finishedSiteFolder.canRead()) {
                val files = finishedSiteFolder.listFiles()
                files
                    .filter { file ->
                        val fileName = file.name
                        file.isFile && 
                        fileName != null && 
                        fileName.endsWith(".xml") && 
                        !fileName.endsWith("_draft.xml")
                    }
                    .mapNotNull { 
                        val name = it.name ?: return@mapNotNull null
                        name.removeSuffix(".xml")
                    }
                    .forEach { formIds.add(it) }
            }
        }
        
        return formIds.toList()
    }
    
    /**
     * Gets all forms with draft versions for a site
     * @param siteName The name of the site
     * @param checkFinished If true, also checks the finished folder (for finalized sites)
     */
    fun getDraftForms(siteName: String, checkFinished: Boolean = true): List<String> {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        val formIds = mutableSetOf<String>()
        
        // Check ongoing folder
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences)
        val ongoingSiteFolder = ongoingFolder?.findFile(siteName)
        if (ongoingSiteFolder != null && ongoingSiteFolder.exists() && ongoingSiteFolder.canRead()) {
            val files = ongoingSiteFolder.listFiles()
            files
                .filter { file ->
                    val fileName = file.name
                    file.isFile && 
                    fileName != null && 
                    fileName.endsWith("_draft.xml")
                }
                .mapNotNull { 
                    val name = it.name ?: return@mapNotNull null
                    name.removeSuffix("_draft.xml")
                }
                .forEach { formIds.add(it) }
        }
        
        // Check finished folder if requested
        if (checkFinished) {
            val finishedFolder = folderHelper.getFinishedFolder(settingsPreferences)
            val finishedSiteFolder = finishedFolder?.findFile(siteName)
            if (finishedSiteFolder != null && finishedSiteFolder.exists() && finishedSiteFolder.canRead()) {
                val files = finishedSiteFolder.listFiles()
                files
                    .filter { file ->
                        val fileName = file.name
                        file.isFile && 
                        fileName != null && 
                        fileName.endsWith("_draft.xml")
                    }
                    .mapNotNull { 
                        val name = it.name ?: return@mapNotNull null
                        name.removeSuffix("_draft.xml")
                    }
                    .forEach { formIds.add(it) }
            }
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
        
        // Determine filename
        val fileName = if (isDraft) {
            "${formId}_draft.xml"
        } else {
            "${formId}.xml"
        }
        
        // Find and delete the file
        val file = siteFolder.findFile(fileName)
        return if (file != null && file.exists()) {
            try {
                file.delete()
            } catch (e: Exception) {
                android.util.Log.e("FormFileHelper", "Error deleting form: ${e.message}", e)
                false
            }
        } else {
            // File doesn't exist, consider it already deleted
            true
        }
    }
}

