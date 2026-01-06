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
     * The stored URI should point directly to TREC_logsheets folder
     */
    fun getTrecLogsheetsFolder(settingsPreferences: SettingsPreferences): DocumentFile? {
        val uri = getTrecLogsheetsFolderUri(settingsPreferences) ?: return null
        try {
            // The URI stored should be a tree URI pointing to TREC_logsheets
            val documentFile = DocumentFile.fromTreeUri(context, uri)
            if (documentFile == null) {
                android.util.Log.e("FolderStructureHelper", "DocumentFile.fromTreeUri returned null for URI: $uri")
                return null
            }
            
            // Verify it's actually the TREC_logsheets folder by checking the name
            val folderName = documentFile.name
            android.util.Log.d("FolderStructureHelper", "Retrieved folder name: '$folderName', expected: '$PARENT_FOLDER_NAME'")
            
            if (folderName != null && folderName == PARENT_FOLDER_NAME) {
                // This is the TREC_logsheets folder - return it
                return documentFile
            }
            
            // If name doesn't match, the URI might point to the parent folder
            // Try to find TREC_logsheets inside it
            if (folderName != null) {
                android.util.Log.w("FolderStructureHelper", "URI points to '$folderName', not '$PARENT_FOLDER_NAME'. Looking for TREC_logsheets inside...")
                val trecFolder = documentFile.findFile(PARENT_FOLDER_NAME)
                if (trecFolder != null && trecFolder.exists()) {
                    android.util.Log.d("FolderStructureHelper", "Found TREC_logsheets inside parent folder")
                    return trecFolder
                }
            }
            
            // If we can't find it, return the document file anyway (might work)
            android.util.Log.w("FolderStructureHelper", "Could not verify TREC_logsheets folder, using folder: '$folderName'")
            return documentFile
        } catch (e: Exception) {
            android.util.Log.e("FolderStructureHelper", "Error getting TREC_logsheets folder: ${e.message}", e)
            return null
        }
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
     * Structure: baseFolder/TREC_logsheets/{ongoing, finished, deleted}
     */
    fun ensureFolderStructure(baseUri: Uri): DocumentFile? {
        val baseFolder = DocumentFile.fromTreeUri(context, baseUri) ?: return null
        if (!baseFolder.exists() || !baseFolder.canWrite()) return null
        
        // Check if TREC_logsheets folder already exists
        var trecFolder = baseFolder.findFile(PARENT_FOLDER_NAME)
        if (trecFolder == null || !trecFolder.exists()) {
            // Only create if it doesn't exist
            trecFolder = baseFolder.createDirectory(PARENT_FOLDER_NAME)
            if (trecFolder == null) return null
        }
        
        // Ensure all subfolders exist inside TREC_logsheets
        val subfolders = listOf(ONGOING_FOLDER, FINISHED_FOLDER, DELETED_FOLDER)
        for (subfolderName in subfolders) {
            var subfolder = trecFolder.findFile(subfolderName)
            if (subfolder == null || !subfolder.exists()) {
                // Create the subfolder inside TREC_logsheets
                subfolder = trecFolder.createDirectory(subfolderName)
                if (subfolder == null) {
                    // Log error but continue with other folders
                    android.util.Log.e("FolderStructureHelper", "Failed to create subfolder: $subfolderName")
                }
            }
        }
        
        return trecFolder
    }
    
    /**
     * Ensures the TREC_logsheets folder structure is complete
     * This is a helper method to verify and create missing subfolders
     */
    fun ensureSubfoldersExist(settingsPreferences: SettingsPreferences): Boolean {
        val trecFolder = getTrecLogsheetsFolder(settingsPreferences) ?: return false
        if (!trecFolder.exists() || !trecFolder.canWrite()) return false
        
        val subfolders = listOf(ONGOING_FOLDER, FINISHED_FOLDER, DELETED_FOLDER)
        var allCreated = true
        
        for (subfolderName in subfolders) {
            var subfolder = trecFolder.findFile(subfolderName)
            if (subfolder == null || !subfolder.exists()) {
                subfolder = trecFolder.createDirectory(subfolderName)
                if (subfolder == null) {
                    allCreated = false
                }
            }
        }
        
        return allCreated
    }
}

