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
     * Gets the team folder inside TREC_logsheets
     * Safely handles existing folders to avoid creating duplicates
     */
    private fun getTeamFolder(settingsPreferences: SettingsPreferences): DocumentFile? {
        val trecFolder = getTrecLogsheetsFolder(settingsPreferences) ?: return null
        val team = settingsPreferences.getSamplingTeam()
        if (team.isEmpty()) {
            return null
        }
        
        // First, try to find existing folder by name
        var teamFolder = trecFolder.findFile(team)
        if (teamFolder != null && teamFolder.exists()) {
            return teamFolder
        }
        
        // Also check by listing files (fallback in case findFile() doesn't work)
        try {
            val files = trecFolder.listFiles()
            teamFolder = files?.firstOrNull { it.name == team && it.isDirectory && it.exists() }
            if (teamFolder != null) {
                return teamFolder
            }
        } catch (e: Exception) {
            android.util.Log.w("FolderStructureHelper", "Error listing files: ${e.message}")
        }
        
        // Folder doesn't exist, create it
        teamFolder = trecFolder.createDirectory(team)
        
        // Verify the created folder has the correct name (not a duplicate like "AML (1)")
        if (teamFolder != null && teamFolder.name != team) {
            // Created folder has wrong name, try to find the correct one
            // This can happen if createDirectory() creates a duplicate when folder already exists
            android.util.Log.w("FolderStructureHelper", "Created folder has unexpected name: '${teamFolder.name}' instead of '$team'. Looking for correct folder...")
            // Try findFile again
            val correctFolder = trecFolder.findFile(team)
            if (correctFolder != null && correctFolder.exists()) {
                return correctFolder
            }
            // Try listing files again
            try {
                val files = trecFolder.listFiles()
                val foundFolder = files?.firstOrNull { it.name == team && it.isDirectory && it.exists() }
                if (foundFolder != null) {
                    return foundFolder
                }
            } catch (e: Exception) {
                android.util.Log.w("FolderStructureHelper", "Error listing files after duplicate creation: ${e.message}")
            }
        }
        
        return teamFolder
    }
    
    /**
     * Gets the subteam folder for the current team/subteam
     * All teams now use the same structure: TREC_logsheets/{team}/{subteam}/
     * Safely handles existing folders to avoid creating duplicates
     */
    private fun getSubteamFolder(settingsPreferences: SettingsPreferences): DocumentFile? {
        val teamFolder = getTeamFolder(settingsPreferences) ?: return null
        val subteam = settingsPreferences.getSamplingSubteam()
        if (subteam.isEmpty()) {
            return null
        }
        
        // First, try to find existing folder by name
        var subteamFolder = teamFolder.findFile(subteam)
        if (subteamFolder != null && subteamFolder.exists()) {
            return subteamFolder
        }
        
        // Also check by listing files (fallback in case findFile() doesn't work)
        try {
            val files = teamFolder.listFiles()
            subteamFolder = files?.firstOrNull { it.name == subteam && it.isDirectory && it.exists() }
            if (subteamFolder != null) {
                return subteamFolder
            }
        } catch (e: Exception) {
            android.util.Log.w("FolderStructureHelper", "Error listing subteam files: ${e.message}")
        }
        
        // Folder doesn't exist, create it
        subteamFolder = teamFolder.createDirectory(subteam)
        
        // Verify the created folder has the correct name (not a duplicate)
        if (subteamFolder != null && subteamFolder.name != subteam) {
            // Created folder has wrong name, try to find the correct one
            android.util.Log.w("FolderStructureHelper", "Created subteam folder has unexpected name: '${subteamFolder.name}' instead of '$subteam'. Looking for correct folder...")
            // Try findFile again
            val correctFolder = teamFolder.findFile(subteam)
            if (correctFolder != null && correctFolder.exists()) {
                return correctFolder
            }
            // Try listing files again
            try {
                val files = teamFolder.listFiles()
                val foundFolder = files?.firstOrNull { it.name == subteam && it.isDirectory && it.exists() }
                if (foundFolder != null) {
                    return foundFolder
                }
            } catch (e: Exception) {
                android.util.Log.w("FolderStructureHelper", "Error listing files after duplicate creation: ${e.message}")
            }
        }
        
        return subteamFolder
    }
    
    /**
     * Gets the ongoing subfolder for the current team/subteam
     */
    fun getOngoingFolder(settingsPreferences: SettingsPreferences): DocumentFile? {
        val subteamFolder = getSubteamFolder(settingsPreferences) ?: return null
        var ongoingFolder = subteamFolder.findFile(ONGOING_FOLDER)
        if (ongoingFolder == null || !ongoingFolder.exists()) {
            ongoingFolder = subteamFolder.createDirectory(ONGOING_FOLDER)
        }
        return ongoingFolder
    }
    
    /**
     * Gets the finished subfolder for the current team/subteam
     */
    fun getFinishedFolder(settingsPreferences: SettingsPreferences): DocumentFile? {
        val subteamFolder = getSubteamFolder(settingsPreferences) ?: return null
        var finishedFolder = subteamFolder.findFile(FINISHED_FOLDER)
        if (finishedFolder == null || !finishedFolder.exists()) {
            finishedFolder = subteamFolder.createDirectory(FINISHED_FOLDER)
        }
        return finishedFolder
    }
    
    /**
     * Gets the deleted subfolder for the current team/subteam
     */
    fun getDeletedFolder(settingsPreferences: SettingsPreferences): DocumentFile? {
        val subteamFolder = getSubteamFolder(settingsPreferences) ?: return null
        var deletedFolder = subteamFolder.findFile(DELETED_FOLDER)
        if (deletedFolder == null || !deletedFolder.exists()) {
            deletedFolder = subteamFolder.createDirectory(DELETED_FOLDER)
        }
        return deletedFolder
    }
    
    /**
     * Ensures the folder structure exists, creating it only if it doesn't exist
     * Structure: baseFolder/TREC_logsheets/{team}/{subteam}/{ongoing, finished, deleted}
     * All teams use the same structure with team and subteam folders
     */
    fun ensureFolderStructure(baseUri: Uri, settingsPreferences: SettingsPreferences): DocumentFile? {
        val baseFolder = DocumentFile.fromTreeUri(context, baseUri) ?: return null
        if (!baseFolder.exists() || !baseFolder.canWrite()) return null
        
        // Check if TREC_logsheets folder already exists
        var trecFolder = baseFolder.findFile(PARENT_FOLDER_NAME)
        if (trecFolder == null || !trecFolder.exists()) {
            // Only create if it doesn't exist
            trecFolder = baseFolder.createDirectory(PARENT_FOLDER_NAME)
            if (trecFolder == null) return null
        }
        
        // Get team and subteam
        val team = settingsPreferences.getSamplingTeam()
        if (team.isEmpty()) {
            android.util.Log.w("FolderStructureHelper", "Cannot create folder structure: team not set")
            return trecFolder
        }
        
        val subteam = settingsPreferences.getSamplingSubteam()
        if (subteam.isEmpty()) {
            android.util.Log.w("FolderStructureHelper", "Cannot create folder structure: subteam not set")
            return trecFolder
        }
        
        // Create team folder
        var teamFolder = trecFolder.findFile(team)
        if (teamFolder == null || !teamFolder.exists()) {
            teamFolder = trecFolder.createDirectory(team)
            if (teamFolder == null) {
                android.util.Log.e("FolderStructureHelper", "Failed to create team folder: $team")
                return trecFolder
            }
        }
        
        // Create subteam folder (all teams use subteams now)
        var subteamFolder = teamFolder.findFile(subteam)
        if (subteamFolder == null || !subteamFolder.exists()) {
            subteamFolder = teamFolder.createDirectory(subteam)
            if (subteamFolder == null) {
                android.util.Log.e("FolderStructureHelper", "Failed to create subteam folder: $subteam")
                return trecFolder
            }
        }
        
        // Ensure all subfolders exist inside subteam folder
        val subfolders = listOf(ONGOING_FOLDER, FINISHED_FOLDER, DELETED_FOLDER)
        for (subfolderName in subfolders) {
            var subfolder = subteamFolder.findFile(subfolderName)
            if (subfolder == null || !subfolder.exists()) {
                // Create the subfolder inside subteam folder
                subfolder = subteamFolder.createDirectory(subfolderName)
                if (subfolder == null) {
                    // Log error but continue with other folders
                    android.util.Log.e("FolderStructureHelper", "Failed to create subfolder: $subfolderName")
                }
            }
        }
        
        return trecFolder
    }
    
    /**
     * Ensures the TREC_logsheets folder structure is complete for current team/subteam
     * This is a helper method to verify and create missing subfolders
     */
    fun ensureSubfoldersExist(settingsPreferences: SettingsPreferences): Boolean {
        val subteamFolder = getSubteamFolder(settingsPreferences) ?: return false
        if (!subteamFolder.exists() || !subteamFolder.canWrite()) return false
        
        val subfolders = listOf(ONGOING_FOLDER, FINISHED_FOLDER, DELETED_FOLDER)
        var allCreated = true
        
        for (subfolderName in subfolders) {
            var subfolder = subteamFolder.findFile(subfolderName)
            if (subfolder == null || !subfolder.exists()) {
                subfolder = subteamFolder.createDirectory(subfolderName)
                if (subfolder == null) {
                    allCreated = false
                }
            }
        }
        
        return allCreated
    }
}

