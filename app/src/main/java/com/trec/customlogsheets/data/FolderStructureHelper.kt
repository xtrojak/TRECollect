package com.trec.customlogsheets.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class FolderStructureHelper(private val context: Context) {
    companion object {
        const val PARENT_FOLDER_NAME = "TREC_logsheets"
        const val ONGOING_FOLDER = "ongoing"
        const val FINISHED_FOLDER = "finished"
        const val DELETED_FOLDER = "deleted"
    }
    
    /**
     * Gets the TREC_logsheets folder URI from settings
     */
    fun getTrecLogsheetsFolderUri(settingsPreferences: SettingsPreferences): Uri? {
        val uriString = settingsPreferences.getFolderUri()
        return if (uriString.isNotEmpty()) {
            try {
                Uri.parse(uriString)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
    
    /**
     * Gets the DocumentFile for the TREC_logsheets folder
     */
    fun getTrecLogsheetsFolder(settingsPreferences: SettingsPreferences): DocumentFile? {
        val uri = getTrecLogsheetsFolderUri(settingsPreferences) ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }
    
    /**
     * Gets the ongoing subfolder
     */
    fun getOngoingFolder(settingsPreferences: SettingsPreferences): DocumentFile? {
        val trecFolder = getTrecLogsheetsFolder(settingsPreferences) ?: return null
        return trecFolder.findFile(ONGOING_FOLDER)
    }
    
    /**
     * Gets the finished subfolder
     */
    fun getFinishedFolder(settingsPreferences: SettingsPreferences): DocumentFile? {
        val trecFolder = getTrecLogsheetsFolder(settingsPreferences) ?: return null
        return trecFolder.findFile(FINISHED_FOLDER)
    }
    
    /**
     * Gets the deleted subfolder
     */
    fun getDeletedFolder(settingsPreferences: SettingsPreferences): DocumentFile? {
        val trecFolder = getTrecLogsheetsFolder(settingsPreferences) ?: return null
        return trecFolder.findFile(DELETED_FOLDER)
    }
    
    /**
     * Ensures the folder structure exists, creating it only if it doesn't exist
     */
    fun ensureFolderStructure(baseUri: Uri): DocumentFile? {
        val baseFolder = DocumentFile.fromTreeUri(context, baseUri) ?: return null
        if (!baseFolder.exists()) return null
        
        // Check if TREC_logsheets folder already exists
        var trecFolder = baseFolder.findFile(PARENT_FOLDER_NAME)
        if (trecFolder == null || !trecFolder.exists()) {
            // Only create if it doesn't exist
            trecFolder = baseFolder.createDirectory(PARENT_FOLDER_NAME)
            if (trecFolder == null) return null
        }
        
        // Create subfolders only if they don't exist
        val subfolders = listOf(ONGOING_FOLDER, FINISHED_FOLDER, DELETED_FOLDER)
        for (subfolderName in subfolders) {
            val subfolder = trecFolder.findFile(subfolderName)
            if (subfolder == null || !subfolder.exists()) {
                // Only create if it doesn't exist
                trecFolder.createDirectory(subfolderName)
            }
        }
        
        return trecFolder
    }
}

