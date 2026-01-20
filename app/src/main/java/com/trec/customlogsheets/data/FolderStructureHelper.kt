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
     * If the URI points to a parent folder, it will look for TREC_logsheets inside it
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
            // Try to find TREC_logsheets inside it (use both findFile and listFiles for reliability)
            if (folderName != null) {
                android.util.Log.w("FolderStructureHelper", "URI points to '$folderName', not '$PARENT_FOLDER_NAME'. Looking for TREC_logsheets inside...")
                var trecFolder = documentFile.findFile(PARENT_FOLDER_NAME)
                if (trecFolder == null || !trecFolder.exists()) {
                    // Also check by listing files (fallback in case findFile() doesn't work reliably)
                    try {
                        val files = documentFile.listFiles()
                        trecFolder = files.firstOrNull { it.name == PARENT_FOLDER_NAME && it.isDirectory && it.exists() }
                    } catch (e: Exception) {
                        android.util.Log.w("FolderStructureHelper", "Error listing files to find TREC_logsheets: ${e.message}")
                    }
                }
                if (trecFolder != null && trecFolder.exists()) {
                    android.util.Log.d("FolderStructureHelper", "Found TREC_logsheets inside parent folder")
                    return trecFolder
                }
            }
            
            // If we can't find TREC_logsheets, don't return the wrong folder
            // This prevents creating team folders at the wrong level
            android.util.Log.e("FolderStructureHelper", "Could not find TREC_logsheets folder. URI points to '$folderName' but TREC_logsheets not found inside it.")
            return null
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
            teamFolder = files.firstOrNull { it.name == team && it.isDirectory && it.exists() }
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
                val foundFolder = files.firstOrNull { it.name == team && it.isDirectory && it.exists() }
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
            subteamFolder = files.firstOrNull { it.name == subteam && it.isDirectory && it.exists() }
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
                val foundFolder = files.firstOrNull { it.name == subteam && it.isDirectory && it.exists() }
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
        android.util.Log.d("FolderStructureHelper", "ensureFolderStructure called with URI: $baseUri")
        val baseFolder = DocumentFile.fromTreeUri(context, baseUri)
        if (baseFolder == null) {
            android.util.Log.e("FolderStructureHelper", "Could not create DocumentFile from baseUri: $baseUri")
            return null
        }
        
        android.util.Log.d("FolderStructureHelper", "Base folder: name='${baseFolder.name}', exists=${baseFolder.exists()}, canWrite=${baseFolder.canWrite()}")
        
        if (!baseFolder.exists()) {
            android.util.Log.e("FolderStructureHelper", "Base folder does not exist: ${baseFolder.name}")
            return null
        }
        
        if (!baseFolder.canWrite()) {
            android.util.Log.e("FolderStructureHelper", "Base folder is not writable: ${baseFolder.name}")
            return null
        }
        
        // Check if TREC_logsheets folder already exists (use both findFile and listFiles for reliability)
        android.util.Log.d("FolderStructureHelper", "Checking if TREC_logsheets folder exists...")
        var trecFolder = baseFolder.findFile(PARENT_FOLDER_NAME)
        android.util.Log.d("FolderStructureHelper", "findFile result: ${if (trecFolder != null) "found (exists=${trecFolder.exists()})" else "null"}")
        
        if (trecFolder == null || !trecFolder.exists()) {
            // Also check by listing files (fallback in case findFile() doesn't work reliably)
            try {
                val files = baseFolder.listFiles()
                android.util.Log.d("FolderStructureHelper", "Listed ${files.size} files in base folder")
                trecFolder = files.firstOrNull { it.name == PARENT_FOLDER_NAME && it.isDirectory && it.exists() }
                if (trecFolder != null) {
                    android.util.Log.d("FolderStructureHelper", "Found TREC_logsheets via listFiles()")
                }
            } catch (e: Exception) {
                android.util.Log.w("FolderStructureHelper", "Error listing files to check for TREC_logsheets: ${e.message}", e)
            }
        }
        
        if (trecFolder == null || !trecFolder.exists()) {
            // Create TREC_logsheets folder - this should ALWAYS be created regardless of team/subteam
            android.util.Log.i("FolderStructureHelper", "TREC_logsheets folder does not exist, creating it...")
            try {
                trecFolder = baseFolder.createDirectory(PARENT_FOLDER_NAME)
                if (trecFolder == null) {
                    android.util.Log.e("FolderStructureHelper", "createDirectory returned null for TREC_logsheets - this usually means permission denied or storage full")
                    return null
                }
                android.util.Log.i("FolderStructureHelper", "createDirectory returned: name='${trecFolder.name}', exists=${trecFolder.exists()}, canWrite=${trecFolder.canWrite()}")
            } catch (e: Exception) {
                android.util.Log.e("FolderStructureHelper", "Exception during createDirectory: ${e.message}", e)
                return null
            }
            
            // Verify the created folder has the correct name (not a duplicate like "TREC_logsheets (1)")
            if (trecFolder.name != PARENT_FOLDER_NAME) {
                android.util.Log.w("FolderStructureHelper", "Created TREC_logsheets folder has unexpected name: '${trecFolder.name}' instead of '$PARENT_FOLDER_NAME'. This indicates a duplicate was created.")
                // Try to find the correct folder that might have existed
                try {
                    val files = baseFolder.listFiles()
                    val correctFolder = files.firstOrNull { it.name == PARENT_FOLDER_NAME && it.isDirectory && it.exists() }
                    if (correctFolder != null) {
                        android.util.Log.i("FolderStructureHelper", "Found existing TREC_logsheets folder. Deleting duplicate '${trecFolder.name}'.")
                        // Delete the duplicate folder we just created
                        try {
                            trecFolder.delete()
                        } catch (e: Exception) {
                            android.util.Log.w("FolderStructureHelper", "Could not delete duplicate TREC_logsheets folder: ${e.message}")
                        }
                        // Use the correct folder
                        trecFolder = correctFolder
                    }
                } catch (e: Exception) {
                    android.util.Log.w("FolderStructureHelper", "Error listing files after TREC_logsheets creation: ${e.message}")
                }
            }
            
            // Retry verification: Sometimes DocumentFile operations need a moment to propagate
            // Try to verify the folder exists and is accessible with a retry loop
            // Use listFiles() as a more reliable check than findFile() after creation
            var verified = false
            for (attempt in 1..5) {
                // First check the folder we just created
                if (trecFolder != null && trecFolder.exists() && trecFolder.canWrite()) {
                    verified = true
                    android.util.Log.d("FolderStructureHelper", "TREC_logsheets folder verified on attempt $attempt")
                    break
                }
                
                // Re-check by listing files (more reliable after creation)
                try {
                    val files = baseFolder.listFiles()
                    val foundFolder = files.firstOrNull { it.name == PARENT_FOLDER_NAME && it.isDirectory && it.exists() }
                    if (foundFolder != null && foundFolder.canWrite()) {
                        trecFolder = foundFolder
                        verified = true
                        android.util.Log.d("FolderStructureHelper", "TREC_logsheets folder found via listFiles() on attempt $attempt")
                        break
                    }
                } catch (e: Exception) {
                    android.util.Log.w("FolderStructureHelper", "Error re-checking TREC_logsheets folder (attempt $attempt): ${e.message}")
                }
                
                // If not verified yet, try refreshing the DocumentFile reference
                if (attempt < 5) {
                    try {
                        // Recreate DocumentFile from URI to get fresh state
                        val refreshedFolder = baseFolder.findFile(PARENT_FOLDER_NAME)
                        if (refreshedFolder != null && refreshedFolder.exists() && refreshedFolder.canWrite()) {
                            trecFolder = refreshedFolder
                            verified = true
                            android.util.Log.d("FolderStructureHelper", "TREC_logsheets folder verified after refresh on attempt $attempt")
                            break
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("FolderStructureHelper", "Error refreshing TREC_logsheets folder: ${e.message}")
                    }
                }
            }
            
            if (!verified) {
                android.util.Log.e("FolderStructureHelper", "TREC_logsheets folder could not be verified after creation (5 attempts)")
                return null
            }
        }
        
        // Final verification that trecFolder is valid
        if (trecFolder == null || !trecFolder.exists() || !trecFolder.canWrite()) {
            android.util.Log.e("FolderStructureHelper", "TREC_logsheets folder is null, doesn't exist, or is not writable")
            return null
        }
        
        android.util.Log.i("FolderStructureHelper", "TREC_logsheets folder successfully created/verified: ${trecFolder.uri}")
        
        // Get team and subteam - these are optional for now, deeper structure can be created later
        val team = settingsPreferences.getSamplingTeam()
        val subteam = settingsPreferences.getSamplingSubteam()
        
        android.util.Log.d("FolderStructureHelper", "Team: '$team', Subteam: '$subteam'")
        
        if (team.isEmpty()) {
            android.util.Log.i("FolderStructureHelper", "Team not set yet - TREC_logsheets folder created, team/subteam folders will be created later")
            return trecFolder
        }
        
        if (subteam.isEmpty()) {
            android.util.Log.i("FolderStructureHelper", "Subteam not set yet - TREC_logsheets folder created, team/subteam folders will be created later")
            return trecFolder
        }
        
        android.util.Log.d("FolderStructureHelper", "Team and subteam are set, creating deeper folder structure...")
        
        // Create team folder (with duplicate detection)
        var teamFolder = trecFolder.findFile(team)
        if (teamFolder == null || !teamFolder.exists()) {
            // Also check by listing files (fallback in case findFile() doesn't work reliably)
            try {
                val files = trecFolder.listFiles()
                teamFolder = files.firstOrNull { it.name == team && it.isDirectory && it.exists() }
            } catch (e: Exception) {
                android.util.Log.w("FolderStructureHelper", "Error listing files to check for team folder: ${e.message}")
            }
        }
        
        if (teamFolder == null || !teamFolder.exists()) {
            teamFolder = trecFolder.createDirectory(team)
            if (teamFolder == null) {
                android.util.Log.e("FolderStructureHelper", "Failed to create team folder: $team")
                return trecFolder
            }
            
            // Verify the created folder has the correct name (not a duplicate)
            if (teamFolder.name != team) {
                android.util.Log.w("FolderStructureHelper", "Created team folder has unexpected name: '${teamFolder.name}' instead of '$team'. Looking for correct folder...")
                // Try to find the correct folder
                try {
                    val files = trecFolder.listFiles()
                    val correctFolder = files.firstOrNull { it.name == team && it.isDirectory && it.exists() }
                    if (correctFolder != null) {
                        android.util.Log.i("FolderStructureHelper", "Found existing team folder. Deleting duplicate '${teamFolder.name}'.")
                        try {
                            teamFolder.delete()
                        } catch (e: Exception) {
                            android.util.Log.w("FolderStructureHelper", "Could not delete duplicate team folder: ${e.message}")
                        }
                        teamFolder = correctFolder
                    }
                } catch (e: Exception) {
                    android.util.Log.w("FolderStructureHelper", "Error listing files after team folder creation: ${e.message}")
                }
            }
        }
        
        // Ensure teamFolder is not null at this point
        if (teamFolder == null || !teamFolder.exists()) {
            android.util.Log.e("FolderStructureHelper", "Team folder is null or doesn't exist after creation attempt")
            return trecFolder
        }
        
        // Create subteam folder (all teams use subteams now) (with duplicate detection)
        var subteamFolder = teamFolder.findFile(subteam)
        if (subteamFolder == null || !subteamFolder.exists()) {
            // Also check by listing files (fallback in case findFile() doesn't work reliably)
            try {
                val files = teamFolder.listFiles()
                subteamFolder = files.firstOrNull { it.name == subteam && it.isDirectory && it.exists() }
            } catch (e: Exception) {
                android.util.Log.w("FolderStructureHelper", "Error listing files to check for subteam folder: ${e.message}")
            }
        }
        
        if (subteamFolder == null || !subteamFolder.exists()) {
            subteamFolder = teamFolder.createDirectory(subteam)
            if (subteamFolder == null) {
                android.util.Log.e("FolderStructureHelper", "Failed to create subteam folder: $subteam")
                return trecFolder
            }
            
            // Verify the created folder has the correct name (not a duplicate)
            if (subteamFolder.name != subteam) {
                android.util.Log.w("FolderStructureHelper", "Created subteam folder has unexpected name: '${subteamFolder.name}' instead of '$subteam'. Looking for correct folder...")
                // Try to find the correct folder
                try {
                    val files = teamFolder.listFiles()
                    val correctFolder = files.firstOrNull { it.name == subteam && it.isDirectory && it.exists() }
                    if (correctFolder != null) {
                        android.util.Log.i("FolderStructureHelper", "Found existing subteam folder. Deleting duplicate '${subteamFolder.name}'.")
                        try {
                            subteamFolder.delete()
                        } catch (e: Exception) {
                            android.util.Log.w("FolderStructureHelper", "Could not delete duplicate subteam folder: ${e.message}")
                        }
                        subteamFolder = correctFolder
                    }
                } catch (e: Exception) {
                    android.util.Log.w("FolderStructureHelper", "Error listing files after subteam folder creation: ${e.message}")
                }
            }
        }
        
        // Ensure subteamFolder is not null at this point
        if (subteamFolder == null || !subteamFolder.exists()) {
            android.util.Log.e("FolderStructureHelper", "Subteam folder is null or doesn't exist after creation attempt")
            return trecFolder
        }
        
        // Ensure all subfolders exist inside subteam folder
        val subfolders = listOf(ONGOING_FOLDER, FINISHED_FOLDER, DELETED_FOLDER)
        for (subfolderName in subfolders) {
            var subfolder = subteamFolder.findFile(subfolderName)
            if (subfolder == null || !subfolder.exists()) {
                // Also check by listing files (fallback in case findFile() doesn't work reliably)
                try {
                    val files = subteamFolder.listFiles()
                    subfolder = files.firstOrNull { it.name == subfolderName && it.isDirectory && it.exists() }
                } catch (e: Exception) {
                    android.util.Log.w("FolderStructureHelper", "Error listing files to check for subfolder $subfolderName: ${e.message}")
                }
            }
            
            if (subfolder == null || !subfolder.exists()) {
                // Create the subfolder inside subteam folder
                subfolder = subteamFolder.createDirectory(subfolderName)
                if (subfolder == null) {
                    // Log error but continue with other folders
                    android.util.Log.e("FolderStructureHelper", "Failed to create subfolder: $subfolderName")
                } else {
                    // Verify the created folder has the correct name (not a duplicate)
                    if (subfolder.name != subfolderName) {
                        android.util.Log.w("FolderStructureHelper", "Created subfolder has unexpected name: '${subfolder.name}' instead of '$subfolderName'. Looking for correct folder...")
                        // Try to find the correct folder
                        try {
                            val files = subteamFolder.listFiles()
                            val correctFolder = files.firstOrNull { it.name == subfolderName && it.isDirectory && it.exists() }
                            if (correctFolder != null) {
                                android.util.Log.i("FolderStructureHelper", "Found existing $subfolderName folder. Deleting duplicate '${subfolder.name}'.")
                                try {
                                    subfolder.delete()
                                } catch (e: Exception) {
                                    android.util.Log.w("FolderStructureHelper", "Could not delete duplicate subfolder: ${e.message}")
                                }
                                // Use the correct folder and verify it exists
                                subfolder = correctFolder
                                if (!subfolder.exists()) {
                                    android.util.Log.w("FolderStructureHelper", "Correct folder '$subfolderName' does not exist after assignment")
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("FolderStructureHelper", "Error listing files after subfolder creation: ${e.message}")
                        }
                    }
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
                // Also check by listing files (fallback in case findFile() doesn't work reliably)
                try {
                    val files = subteamFolder.listFiles()
                    subfolder = files.firstOrNull { it.name == subfolderName && it.isDirectory && it.exists() }
                } catch (e: Exception) {
                    android.util.Log.w("FolderStructureHelper", "Error listing files to check for subfolder $subfolderName: ${e.message}")
                }
            }
            
            if (subfolder == null || !subfolder.exists()) {
                subfolder = subteamFolder.createDirectory(subfolderName)
                if (subfolder == null) {
                    allCreated = false
                } else {
                    // Verify the created folder has the correct name (not a duplicate)
                    if (subfolder.name != subfolderName) {
                        android.util.Log.w("FolderStructureHelper", "Created subfolder has unexpected name: '${subfolder.name}' instead of '$subfolderName'. Looking for correct folder...")
                        // Try to find the correct folder
                        try {
                            val files = subteamFolder.listFiles()
                            val correctFolder = files.firstOrNull { it.name == subfolderName && it.isDirectory && it.exists() }
                            if (correctFolder != null) {
                                android.util.Log.i("FolderStructureHelper", "Found existing $subfolderName folder. Deleting duplicate '${subfolder.name}'.")
                                try {
                                    subfolder.delete()
                                } catch (e: Exception) {
                                    android.util.Log.w("FolderStructureHelper", "Could not delete duplicate subfolder: ${e.message}")
                                }
                                // Use the correct folder and verify it exists
                                subfolder = correctFolder
                                if (!subfolder.exists()) {
                                    android.util.Log.w("FolderStructureHelper", "Correct folder '$subfolderName' does not exist after assignment")
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("FolderStructureHelper", "Error listing files after subfolder creation: ${e.message}")
                        }
                    }
                }
            }
        }
        
        return allCreated
    }
}

