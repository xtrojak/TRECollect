package com.trec.trecollect.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.ConcurrentHashMap

class FolderStructureHelper(private val context: Context) {
    companion object {
        const val PARENT_FOLDER_NAME = "TRECollect_logsheets"
        const val ONGOING_FOLDER = "ongoing"
        const val FINISHED_FOLDER = "finished"
        const val DELETED_FOLDER = "deleted"
        /** Filename for the UUID file stored in the root of the output folder. */
        const val UUID_FILENAME = "app_uuid.txt"
        
        /** Locks per (parentUri, folderName) so concurrent callers don't both create the same folder. */
        private val folderLocks = ConcurrentHashMap<String, Any>()
        private fun lockFor(parentUri: Uri, folderName: String): Any =
            folderLocks.getOrPut("${parentUri}_$folderName") { Any() }
    }

    /**
     * Finds an existing child folder by name or creates it. Uses a per-folder lock so that
     * concurrent callers (e.g. Settings + ViewModel) don't both create the same folder.
     * After createDirectory, if the provider returned a duplicate (e.g. "ongoing (1)"), we
     * re-query and return the folder with the exact name so callers always get the canonical folder.
     */
    private fun findOrCreateChildFolder(parent: DocumentFile, folderName: String): DocumentFile? {
        val lock = lockFor(parent.uri, folderName)
        return synchronized(lock) {
            findOrCreateChildFolderLocked(parent, folderName)
        }
    }
    
    private fun findOrCreateChildFolderLocked(parent: DocumentFile, folderName: String): DocumentFile? {
        // 1) Try listFiles() first (often more up-to-date than findFile after a create)
        try {
            val files = parent.listFiles()
            val folder = files?.firstOrNull { it.name == folderName && it.isDirectory && it.exists() }
            if (folder != null) return folder
        } catch (e: Exception) {
            android.util.Log.w("FolderStructureHelper", "Error listing files in ${parent.name}: ${e.message}")
        }
        // 2) Try findFile
        var folder = parent.findFile(folderName)
        if (folder != null && folder.exists()) return folder
        // 3) Create
        val created = parent.createDirectory(folderName) ?: return null
        // 4) If provider created a duplicate (e.g. "ongoing (1)" because "ongoing" already existed),
        //    find and return the folder with the exact name so we never return the (1) variant
        if (created.name != folderName) {
            try {
                val files = parent.listFiles()
                val exact = files?.firstOrNull { it.name == folderName && it.isDirectory && it.exists() }
                if (exact != null) return exact
            } catch (_: Exception) { }
        }
        try {
            val files = parent.listFiles()
            val existing = files?.firstOrNull { it.name == folderName && it.isDirectory && it.exists() }
            if (existing != null) return existing
        } catch (_: Exception) { }
        return created
    }

    /**
     * Gets the TRECollect_logsheets folder URI from settings
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
     * Gets the DocumentFile for the TRECollect_logsheets folder
     * The stored URI should point directly to TRECollect_logsheets folder
     * If the URI points to a parent folder, it will look for TRECollect_logsheets inside it
     */
    fun getTrecLogsheetsFolder(settingsPreferences: SettingsPreferences): DocumentFile? {
        val uri = getTrecLogsheetsFolderUri(settingsPreferences) ?: return null
        try {
            // The URI stored should be a tree URI pointing to TRECollect_logsheets
            val documentFile = DocumentFile.fromTreeUri(context, uri)
            if (documentFile == null) {
                android.util.Log.e("FolderStructureHelper", "DocumentFile.fromTreeUri returned null for URI: $uri")
                return null
            }
            
            // Verify it's actually the TRECollect_logsheets folder by checking the name
            val folderName = documentFile.name
            android.util.Log.d("FolderStructureHelper", "Retrieved folder name: '$folderName', expected: '$PARENT_FOLDER_NAME'")
            
            if (folderName != null && folderName == PARENT_FOLDER_NAME) {
                // This is the TRECollect_logsheets folder - return it
                return documentFile
            }
            
            // If name doesn't match, the URI might point to the parent folder
            // Try to find TRECollect_logsheets inside it (use both findFile and listFiles for reliability)
            if (folderName != null) {
                android.util.Log.w("FolderStructureHelper", "URI points to '$folderName', not '$PARENT_FOLDER_NAME'. Looking for TRECollect_logsheets inside...")
                var trecFolder = documentFile.findFile(PARENT_FOLDER_NAME)
                if (trecFolder == null || !trecFolder.exists()) {
                    // Also check by listing files (fallback in case findFile() doesn't work reliably)
                    try {
                        val files = documentFile.listFiles()
                        trecFolder = files.firstOrNull { it.name == PARENT_FOLDER_NAME && it.isDirectory && it.exists() }
                    } catch (e: Exception) {
                        android.util.Log.w("FolderStructureHelper", "Error listing files to find TRECollect_logsheets: ${e.message}")
                    }
                }
                if (trecFolder != null && trecFolder.exists()) {
                    android.util.Log.d("FolderStructureHelper", "Found TRECollect_logsheets inside parent folder")
                    return trecFolder
                }
            }
            
            // If we can't find TRECollect_logsheets, don't return the wrong folder
            // This prevents creating team folders at the wrong level
            android.util.Log.e("FolderStructureHelper", "Could not find TRECollect_logsheets folder. URI points to '$folderName' but TRECollect_logsheets not found inside it.")
            return null
        } catch (e: Exception) {
            android.util.Log.e("FolderStructureHelper", "Error getting TRECollect_logsheets folder: ${e.message}", e)
            return null
        }
    }

    /**
     * Ensures the UUID file exists in the root of the output folder (TRECollect_logsheets).
     * If the file already exists (release only), reads the UUID and sets it in prefs.
     * If the file does not exist, writes the current app UUID (getAppUuid()) to a new file.
     */
    fun ensureUuidFileInOutputFolder(trecFolder: DocumentFile, settingsPreferences: SettingsPreferences) {
        if (!trecFolder.exists() || !trecFolder.canWrite()) return
        val existingFile = trecFolder.findFile(UUID_FILENAME)
        if (existingFile != null && existingFile.exists() && existingFile.canRead()) {
            if (!settingsPreferences.isDevBuild()) {
                try {
                    context.contentResolver.openInputStream(existingFile.uri)?.use { input ->
                        val uuid = input.bufferedReader().readText().trim()
                        if (uuid.isNotEmpty()) settingsPreferences.setAppUuid(uuid)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("FolderStructureHelper", "Could not read UUID file: ${e.message}")
                }
            }
            return
        }
        val uuid = settingsPreferences.getAppUuid()
        try {
            val file = trecFolder.createFile("text/plain", UUID_FILENAME)
            if (file != null && file.exists()) {
                context.contentResolver.openOutputStream(file.uri)?.use { it.write(uuid.toByteArray()) }
            }
        } catch (e: Exception) {
            android.util.Log.w("FolderStructureHelper", "Could not write UUID file: ${e.message}")
        }
    }
    
    /**
     * Gets the team folder inside TRECollect_logsheets
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
     * All teams now use the same structure: TRECollect_logsheets/{team}/{subteam}/
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
     * Structure: baseFolder/TRECollect_logsheets/{team}/{subteam}/{ongoing, finished, deleted}.
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

        android.util.Log.i("FolderStructureHelper", "TRECollect_logsheets folder ready: ${trecFolder.uri}")

        val team = settingsPreferences.getSamplingTeam()
        val subteam = settingsPreferences.getSamplingSubteam()
        if (team.isEmpty()) {
            android.util.Log.i("FolderStructureHelper", "Team not set - TRECollect_logsheets created, team/subteam later")
            return trecFolder
        }
        if (subteam.isEmpty()) {
            android.util.Log.i("FolderStructureHelper", "Subteam not set - TRECollect_logsheets created, team/subteam later")
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
     * Ensures the TRECollect_logsheets subfolders (team/subteam/ongoing/finished/deleted) exist.
     * Uses the stored folder URI. Use when folder is already saved (e.g. after team/subteam change).
     */
    fun ensureSubfoldersExist(settingsPreferences: SettingsPreferences): Boolean {
        val subteamFolder = getSubteamFolder(settingsPreferences) ?: return false
        return ensureSubfoldersInSubteamFolder(subteamFolder)
    }

    /**
     * Ensures team/subteam/ongoing/finished/deleted exist under the given TRECollect_logsheets folder.
     * Use this when you have the trecFolder directly (e.g. right after creating it) so the folder
     * URI does not need to be saved yet. Avoids creating team/subteam twice.
     */
    fun ensureSubfoldersExist(trecFolder: DocumentFile, settingsPreferences: SettingsPreferences): Boolean {
        val team = settingsPreferences.getSamplingTeam()
        val subteam = settingsPreferences.getSamplingSubteam()
        if (team.isEmpty() || subteam.isEmpty()) return true
        val teamFolder = findOrCreateChildFolder(trecFolder, team) ?: return false
        val subteamFolder = findOrCreateChildFolder(teamFolder, subteam) ?: return false
        return ensureSubfoldersInSubteamFolder(subteamFolder)
    }
}

