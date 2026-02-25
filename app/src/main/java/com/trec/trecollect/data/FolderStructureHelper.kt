package com.trec.trecollect.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile

class FolderStructureHelper(private val context: Context) {
    companion object {
        const val PARENT_FOLDER_NAME = "TREC_logsheets"
        const val ONGOING_FOLDER = "ongoing"
        const val FINISHED_FOLDER = "finished"
        const val DELETED_FOLDER = "deleted"
    }

    /**
     * Finds an existing child folder by name or creates it. Uses findFile first, then listFiles as fallback, then createDirectory.
     */
    private fun findOrCreateChildFolder(parent: DocumentFile, folderName: String): DocumentFile? {
        var folder = parent.findFile(folderName)
        if (folder != null && folder.exists()) return folder
        try {
            val files = parent.listFiles()
            folder = files.firstOrNull { it.name == folderName && it.isDirectory && it.exists() }
            if (folder != null) return folder
        } catch (e: Exception) {
            android.util.Log.w("FolderStructureHelper", "Error listing files in ${parent.name}: ${e.message}")
        }
        return parent.createDirectory(folderName)
    }

    /**
     * Gets the TREC_logsheets folder URI from settings
     */
    fun getTrecLogsheetsFolderUri(settingsPreferences: SettingsPreferences): Uri? {
        val uriString = settingsPreferences.getFolderUri()
        return if (uriString.isNotEmpty()) {
            try {
                uriString.toUri()
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
        if (team.isEmpty()) return null
        return findOrCreateChildFolder(trecFolder, team)
    }
    
    /**
     * Gets the subteam folder for the current team/subteam
     * All teams now use the same structure: TREC_logsheets/{team}/{subteam}/
     * Safely handles existing folders to avoid creating duplicates
     */
    private fun getSubteamFolder(settingsPreferences: SettingsPreferences): DocumentFile? {
        val teamFolder = getTeamFolder(settingsPreferences) ?: return null
        val subteam = settingsPreferences.getSamplingSubteam()
        if (subteam.isEmpty()) return null
        return findOrCreateChildFolder(teamFolder, subteam)
    }
    
    /**
     * Gets a subfolder (ongoing, finished, or deleted) by name for the current team/subteam.
     */
    private fun getSubfolderByName(settingsPreferences: SettingsPreferences, folderName: String): DocumentFile? {
        val subteamFolder = getSubteamFolder(settingsPreferences) ?: return null
        return findOrCreateChildFolder(subteamFolder, folderName)
    }

    /**
     * Gets the ongoing subfolder for the current team/subteam
     */
    fun getOngoingFolder(settingsPreferences: SettingsPreferences): DocumentFile? =
        getSubfolderByName(settingsPreferences, ONGOING_FOLDER)

    /**
     * Gets the finished subfolder for the current team/subteam
     */
    fun getFinishedFolder(settingsPreferences: SettingsPreferences): DocumentFile? =
        getSubfolderByName(settingsPreferences, FINISHED_FOLDER)

    /**
     * Gets the deleted subfolder for the current team/subteam
     */
    fun getDeletedFolder(settingsPreferences: SettingsPreferences): DocumentFile? =
        getSubfolderByName(settingsPreferences, DELETED_FOLDER)
    
    /**
     * Ensures ongoing, finished, and deleted subfolders exist under the given subteam folder.
     * Used by both [ensureFolderStructure] and [ensureSubfoldersExist].
     */
    private fun ensureSubfoldersInSubteamFolder(subteamFolder: DocumentFile): Boolean {
        if (!subteamFolder.exists() || !subteamFolder.canWrite()) return false
        val subfolders = listOf(ONGOING_FOLDER, FINISHED_FOLDER, DELETED_FOLDER)
        for (name in subfolders) {
            if (findOrCreateChildFolder(subteamFolder, name) == null) return false
        }
        return true
    }

    /**
     * Ensures the folder structure exists, creating it only if it doesn't exist.
     * Structure: baseFolder/TREC_logsheets/{team}/{subteam}/{ongoing, finished, deleted}.
     * Called when the user selects a base folder (e.g. in Settings); [ensureSubfoldersExist] is used to ensure ongoing/finished/deleted exist for the current team/subteam.
     */
    fun ensureFolderStructure(baseUri: Uri, settingsPreferences: SettingsPreferences): DocumentFile? {
        android.util.Log.d("FolderStructureHelper", "ensureFolderStructure called with URI: $baseUri")
        val baseFolder = DocumentFile.fromTreeUri(context, baseUri)
            ?: run {
                android.util.Log.e("FolderStructureHelper", "Could not create DocumentFile from baseUri: $baseUri")
                return null
            }
        if (!baseFolder.exists()) {
            android.util.Log.e("FolderStructureHelper", "Base folder does not exist: ${baseFolder.name}")
            return null
        }
        if (!baseFolder.canWrite()) {
            android.util.Log.e("FolderStructureHelper", "Base folder is not writable: ${baseFolder.name}")
            return null
        }

        val trecFolder = findOrCreateChildFolder(baseFolder, PARENT_FOLDER_NAME)
            ?: run {
                android.util.Log.e("FolderStructureHelper", "Could not create or find $PARENT_FOLDER_NAME folder")
                return null
            }
        if (!trecFolder.exists() || !trecFolder.canWrite()) {
            android.util.Log.e("FolderStructureHelper", "$PARENT_FOLDER_NAME folder is not writable")
            return null
        }

        android.util.Log.i("FolderStructureHelper", "TREC_logsheets folder ready: ${trecFolder.uri}")

        val team = settingsPreferences.getSamplingTeam()
        val subteam = settingsPreferences.getSamplingSubteam()
        if (team.isEmpty()) {
            android.util.Log.i("FolderStructureHelper", "Team not set - TREC_logsheets created, team/subteam later")
            return trecFolder
        }
        if (subteam.isEmpty()) {
            android.util.Log.i("FolderStructureHelper", "Subteam not set - TREC_logsheets created, team/subteam later")
            return trecFolder
        }

        val teamFolder = findOrCreateChildFolder(trecFolder, team)
            ?: run {
                android.util.Log.e("FolderStructureHelper", "Failed to create team folder: $team")
                return trecFolder
            }
        val subteamFolder = findOrCreateChildFolder(teamFolder, subteam)
            ?: run {
                android.util.Log.e("FolderStructureHelper", "Failed to create subteam folder: $subteam")
                return trecFolder
            }
        ensureSubfoldersInSubteamFolder(subteamFolder)
        return trecFolder
    }

    /**
     * Ensures the TREC_logsheets subfolders (ongoing, finished, deleted) exist for the current team/subteam.
     * Called when team/subteam are already set and we need to ensure the three subfolders exist (e.g. after settings change or before save).
     */
    fun ensureSubfoldersExist(settingsPreferences: SettingsPreferences): Boolean {
        val subteamFolder = getSubteamFolder(settingsPreferences) ?: return false
        return ensureSubfoldersInSubteamFolder(subteamFolder)
    }
}

