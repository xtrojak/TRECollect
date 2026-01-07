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
     * @return FormData if found, null otherwise
     */
    fun loadFormData(siteName: String, formId: String, loadDraft: Boolean = false): FormData? {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Get the site folder
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences) ?: return null
        val siteFolder = ongoingFolder.findFile(siteName) ?: return null
        
        if (!siteFolder.exists() || !siteFolder.canRead()) {
            return null
        }
        
        // Determine filename
        val fileName = if (loadDraft) {
            "${formId}_draft.xml"
        } else {
            "${formId}.xml"
        }
        
        val file = siteFolder.findFile(fileName) ?: return null
        if (!file.exists() || !file.canRead()) {
            return null
        }
        
        // Read XML content
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
            val xmlContent = inputStream?.bufferedReader().use { it?.readText() ?: "" }
            inputStream?.close()
            FormData.fromXml(xmlContent)
        } catch (e: Exception) {
            android.util.Log.e("FormFileHelper", "Error loading form: ${e.message}", e)
            null
        }
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
     */
    fun getSubmittedForms(siteName: String): List<String> {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences) ?: return emptyList()
        val siteFolder = ongoingFolder.findFile(siteName) ?: return emptyList()
        
        if (!siteFolder.exists() || !siteFolder.canRead()) {
            return emptyList()
        }
        
        val files = siteFolder.listFiles() ?: return emptyList()
        return files
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
    }
}

