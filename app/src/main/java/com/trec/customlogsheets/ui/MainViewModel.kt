package com.trec.customlogsheets.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trec.customlogsheets.data.AppDatabase
import com.trec.customlogsheets.data.FolderStructureHelper
import com.trec.customlogsheets.data.SamplingSite
import com.trec.customlogsheets.data.SettingsPreferences
import com.trec.customlogsheets.data.SiteStatus
import com.trec.customlogsheets.data.UploadStatus
import com.trec.customlogsheets.data.OwnCloudManager
import com.trec.customlogsheets.util.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.InputStream
import java.io.OutputStream

class MainViewModel(
    private val database: AppDatabase,
    private val context: Context
) : ViewModel() {
    private val _ongoingSites = MutableStateFlow<List<SamplingSite>>(emptyList())
    val ongoingSites: StateFlow<List<SamplingSite>> = _ongoingSites.asStateFlow()
    
    private val _finishedSites = MutableStateFlow<List<SamplingSite>>(emptyList())
    val finishedSites: StateFlow<List<SamplingSite>> = _finishedSites.asStateFlow()
    
    init {
        // Load sites from folder structure on initialization
        loadSitesFromFolders()
    }
    
    /**
     * Loads sites from the folder structure (ongoing and finished folders)
     */
    fun loadSitesFromFolders() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val settingsPreferences = SettingsPreferences(context)
            val folderUriString = settingsPreferences.getFolderUri()
            
            if (folderUriString.isEmpty()) {
                // No storage configured, set empty lists
                _ongoingSites.value = emptyList()
                _finishedSites.value = emptyList()
                return@launch
            }
            
            val folderHelper = FolderStructureHelper(context)
            
            // Load all sites from database ONCE (optimization: avoid querying for each folder)
            val allDbSites = try {
                database.samplingSiteDao().getAllSites().first()
            } catch (e: Exception) {
                AppLogger.e("MainViewModel", "Error loading sites from database: ${e.message}", e)
                emptyList()
            }
            // Create a map for O(1) lookup by name
            val dbSitesByName = allDbSites.associateBy { it.name }
            
            // Load ongoing sites
            val ongoingFolder = try {
                folderHelper.getOngoingFolder(settingsPreferences)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error loading ongoing folder: ${e.message}", e)
                null
            }
            
            val ongoingSitesList = if (ongoingFolder != null && ongoingFolder.exists() && ongoingFolder.canRead()) {
                val folders = ongoingFolder.listFiles()
                folders.filter { it.isDirectory && it.name != null }
                    .map { folder ->
                        SamplingSite(
                            id = 0, // ID not used when loading from folders
                            name = folder.name!!,
                            status = SiteStatus.ONGOING,
                            uploadStatus = UploadStatus.NOT_UPLOADED, // Ongoing sites are not uploaded
                            createdAt = 0 // We don't have creation time from folders
                        )
                    }
                    .sortedBy { it.name }
            } else {
                emptyList()
            }
            
            // Load finished sites
            val finishedFolder = try {
                folderHelper.getFinishedFolder(settingsPreferences)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error loading finished folder: ${e.message}", e)
                null
            }
            
            val finishedSitesList = if (finishedFolder != null && finishedFolder.exists() && finishedFolder.canRead()) {
                val folders = finishedFolder.listFiles()
                val folderSites = folders.filter { it.isDirectory && it.name != null }
                    .map { folder ->
                        // Try to find existing site in database to preserve upload status
                        // Use the pre-loaded map for O(1) lookup instead of querying for each folder
                        val dbSite = dbSitesByName[folder.name]
                        
                        if (dbSite != null) {
                            // Use database record, but ensure status is FINISHED (since it's in finished folder)
                            val siteWithCorrectStatus = if (dbSite.status != SiteStatus.FINISHED) {
                                // Update status in database if it changed
                                try {
                                    database.samplingSiteDao().updateSite(dbSite.copy(status = SiteStatus.FINISHED))
                                } catch (e: Exception) {
                                    AppLogger.e("MainViewModel", "Error updating site status: ${e.message}", e)
                                }
                                dbSite.copy(status = SiteStatus.FINISHED)
                            } else {
                                dbSite
                            }
                            AppLogger.d("MainViewModel", "Found site in database: name='${folder.name}', id=${siteWithCorrectStatus.id}, status=${siteWithCorrectStatus.status}, uploadStatus=${siteWithCorrectStatus.uploadStatus}")
                            siteWithCorrectStatus
                        } else {
                            AppLogger.d("MainViewModel", "Site not found in database, creating new: name='${folder.name}'")
                            // Create new site record with default upload status
                            val newSite = SamplingSite(
                                id = 0,
                                name = folder.name!!,
                                status = SiteStatus.FINISHED,
                                uploadStatus = UploadStatus.NOT_UPLOADED,
                                createdAt = System.currentTimeMillis()
                            )
                            // Insert into database
                            try {
                                val insertedId = database.samplingSiteDao().insertSite(newSite)
                                val insertedSite = newSite.copy(id = insertedId)
                                AppLogger.d("MainViewModel", "Inserted new site: name='${folder.name}', id=$insertedId, uploadStatus=${insertedSite.uploadStatus}")
                                insertedSite
                            } catch (e: Exception) {
                                AppLogger.e("MainViewModel", "Error inserting new site: ${e.message}", e)
                                newSite
                            }
                        }
                    }
                    .sortedBy { it.name }
                folderSites
            } else {
                emptyList()
            }
            
            _ongoingSites.value = ongoingSitesList
            _finishedSites.value = finishedSitesList
        }
    }
    
    suspend fun createSite(name: String): CreateSiteResult {
        val trimmedName = name.trim()
        AppLogger.i("MainViewModel", "Creating site: name='$trimmedName'")
        if (trimmedName.isBlank()) {
            AppLogger.w("MainViewModel", "Site creation failed: name is empty")
            return CreateSiteResult.Error("Site name cannot be empty")
        }
        
        // Check if site already exists by checking folders
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        
        // Check ongoing folder
        val ongoingFolder = try {
            folderHelper.getOngoingFolder(settingsPreferences)
        } catch (e: Exception) {
            // Will be handled later
            null
        }
        
        if (ongoingFolder != null && ongoingFolder.exists() && ongoingFolder.canRead()) {
            val existingFolder = ongoingFolder.findFile(trimmedName)
            if (existingFolder != null && existingFolder.exists()) {
                return CreateSiteResult.Error("A site with this name already exists")
            }
        }
        
        // Check finished folder
        val finishedFolder = try {
            folderHelper.getFinishedFolder(settingsPreferences)
        } catch (e: Exception) {
            // Will be handled later
            null
        }
        
        if (finishedFolder != null && finishedFolder.exists() && finishedFolder.canRead()) {
            val existingFolder = finishedFolder.findFile(trimmedName)
            if (existingFolder != null && existingFolder.exists()) {
                return CreateSiteResult.Error("A site with this name already exists")
            }
        }
        
        // Check if storage is configured
        val folderUriString = settingsPreferences.getFolderUri()
        
        if (folderUriString.isEmpty()) {
            return CreateSiteResult.Error("Storage folder not configured. Please select a folder in Settings.")
        }
        
        // Try to get the TREC_logsheets folder (the stored URI should point to TREC_logsheets)
        val trecFolder = try {
            folderHelper.getTrecLogsheetsFolder(settingsPreferences)
        } catch (e: Exception) {
            return CreateSiteResult.Error("Error accessing storage folder: ${e.message}")
        }
        
        if (trecFolder == null) {
            return CreateSiteResult.Error("TREC_logsheets folder not found. Please reconfigure storage in Settings.")
        }
        
        // CRITICAL: Verify we have the TREC_logsheets folder by checking its name
        val folderName = trecFolder.name
        android.util.Log.d("MainViewModel", "Retrieved folder name: '$folderName', expected: '${FolderStructureHelper.PARENT_FOLDER_NAME}'")
        
        if (folderName != FolderStructureHelper.PARENT_FOLDER_NAME) {
            // The URI might point to the parent folder, try to find TREC_logsheets inside it
            android.util.Log.w("MainViewModel", "Folder name mismatch! Looking for TREC_logsheets inside '$folderName'...")
            val actualTrecFolder = trecFolder.findFile(FolderStructureHelper.PARENT_FOLDER_NAME)
            if (actualTrecFolder != null && actualTrecFolder.exists()) {
                android.util.Log.d("MainViewModel", "Found TREC_logsheets inside parent folder")
                // Use the actual TREC_logsheets folder
                val verifiedTrecFolder = actualTrecFolder
                return createSiteInFolder(trimmedName, verifiedTrecFolder)
            } else {
                return CreateSiteResult.Error("TREC_logsheets folder not found. The stored URI points to '$folderName' instead. Please reconfigure storage in Settings.")
            }
        }
        
        // Continue with the verified TREC_logsheets folder
        return createSiteInFolder(trimmedName, trecFolder)
    }
    
    private suspend fun createSiteInFolder(siteName: String, trecFolder: androidx.documentfile.provider.DocumentFile): CreateSiteResult {
        // Check if TREC_logsheets folder exists and is accessible
        if (!trecFolder.canRead() || !trecFolder.canWrite()) {
            return CreateSiteResult.Error("Cannot access TREC_logsheets folder. Please check permissions in Settings.")
        }
        
        // Verify this is actually TREC_logsheets
        if (trecFolder.name != FolderStructureHelper.PARENT_FOLDER_NAME) {
            return CreateSiteResult.Error("Internal error: Not working with TREC_logsheets folder. Please reconfigure storage.")
        }
        
        // Ensure all subfolders exist inside TREC_logsheets: ongoing, finished, deleted
        val subfolders = listOf(
            FolderStructureHelper.ONGOING_FOLDER,
            FolderStructureHelper.FINISHED_FOLDER,
            FolderStructureHelper.DELETED_FOLDER
        )
        
        for (subfolderName in subfolders) {
            var subfolder = trecFolder.findFile(subfolderName)
            if (subfolder == null || !subfolder.exists()) {
                // Create the subfolder inside TREC_logsheets
                subfolder = trecFolder.createDirectory(subfolderName)
                if (subfolder == null) {
                    return CreateSiteResult.Error("Could not create $subfolderName folder inside TREC_logsheets. Please check storage permissions.")
                }
            }
        }
        
        // Get the ongoing subfolder inside TREC_logsheets
        val ongoingFolder = trecFolder.findFile(FolderStructureHelper.ONGOING_FOLDER)
        
        if (ongoingFolder == null || !ongoingFolder.exists()) {
            return CreateSiteResult.Error("Ongoing folder not found inside TREC_logsheets. Please reconfigure storage.")
        }
        
        if (!ongoingFolder.canRead() || !ongoingFolder.canWrite()) {
            return CreateSiteResult.Error("Cannot access ongoing folder inside TREC_logsheets. Please check permissions.")
        }
        
        // Check if folder for this site already exists inside TREC_logsheets/ongoing/
        val siteFolder = ongoingFolder.findFile(siteName)
        if (siteFolder != null && siteFolder.exists()) {
            return CreateSiteResult.Error("A folder for this site already exists in TREC_logsheets/ongoing/")
        }
        
        // Create the site folder inside TREC_logsheets/ongoing/
        val createdFolder = ongoingFolder.createDirectory(siteName)
        if (createdFolder == null) {
            AppLogger.e("MainViewModel", "Failed to create site folder: name='$siteName'")
            return CreateSiteResult.Error("Could not create site folder in TREC_logsheets/ongoing/")
        }
        
        AppLogger.i("MainViewModel", "Site created successfully: name='$siteName'")
        
        // Insert site into database
        try {
            val newSite = SamplingSite(
                id = 0,
                name = siteName,
                status = SiteStatus.ONGOING,
                uploadStatus = UploadStatus.NOT_UPLOADED,
                createdAt = System.currentTimeMillis()
            )
            database.samplingSiteDao().insertSite(newSite)
        } catch (e: Exception) {
            AppLogger.w("MainViewModel", "Could not insert site into database: ${e.message}")
        }
        
        // Give the file system a moment to update, then reload sites from folders to update the UI
        // Using a small delay to ensure the folder is visible in the file system
        kotlinx.coroutines.delay(100)
        loadSitesFromFolders()
        
        return CreateSiteResult.Success
    }
    
    sealed class CreateSiteResult {
        object Success : CreateSiteResult()
        data class Error(val message: String) : CreateSiteResult()
    }
    
    suspend fun renameSite(site: SamplingSite, newName: String): RenameSiteResult {
        val trimmedName = newName.trim()
        if (trimmedName.isBlank()) {
            return RenameSiteResult.Error("Site name cannot be empty")
        }
        
        if (trimmedName == site.name) {
            return RenameSiteResult.Error("New name is the same as the current name")
        }
        
        // Get storage settings
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        val folderUriString = settingsPreferences.getFolderUri()
        
        if (folderUriString.isEmpty()) {
            return RenameSiteResult.Error("Storage folder not configured. Please select a folder in Settings.")
        }
        
        // Check ongoing folder
        val ongoingFolder = try {
            folderHelper.getOngoingFolder(settingsPreferences)
        } catch (e: Exception) {
            return RenameSiteResult.Error("Error accessing storage folder: ${e.message}")
        }
        
        if (ongoingFolder == null || !ongoingFolder.exists()) {
            return RenameSiteResult.Error("Ongoing folder not found. Please reconfigure storage in Settings.")
        }
        
        if (!ongoingFolder.canRead() || !ongoingFolder.canWrite()) {
            return RenameSiteResult.Error("Cannot access ongoing folder. Please check permissions.")
        }
        
        // Check if a folder with the new name already exists
        val newSiteFolder = ongoingFolder.findFile(trimmedName)
        if (newSiteFolder != null && newSiteFolder.exists() && newSiteFolder.name != site.name) {
            return RenameSiteResult.Error("A site with this name already exists")
        }
        
        // Check finished folder
        val finishedFolder = try {
            folderHelper.getFinishedFolder(settingsPreferences)
        } catch (e: Exception) {
            // Continue with ongoing folder check
            null
        }
        
        if (finishedFolder != null && finishedFolder.exists() && finishedFolder.canRead()) {
            val existingFolder = finishedFolder.findFile(trimmedName)
            if (existingFolder != null && existingFolder.exists()) {
                return RenameSiteResult.Error("A site with this name already exists")
            }
        }
        
        // Find the old site folder
        val oldSiteFolder = ongoingFolder.findFile(site.name)
        if (oldSiteFolder == null || !oldSiteFolder.exists()) {
            // Folder doesn't exist, but we can still proceed
            android.util.Log.w("MainViewModel", "Site folder '${site.name}' not found")
        } else {
            // Rename the folder
            val renameSuccess = oldSiteFolder.renameTo(trimmedName)
            if (!renameSuccess) {
                return RenameSiteResult.Error("Could not rename site folder. Please check permissions.")
            }
        }
        
        // Reload sites from folders to update the UI
        loadSitesFromFolders()
        
        return RenameSiteResult.Success
    }
    
    sealed class RenameSiteResult {
        object Success : RenameSiteResult()
        data class Error(val message: String) : RenameSiteResult()
    }
    
    fun finishSite(site: SamplingSite) {
        viewModelScope.launch {
            database.samplingSiteDao().updateSite(
                site.copy(status = SiteStatus.FINISHED)
            )
        }
    }
    
    suspend fun finalizeSite(site: SamplingSite): FinalizeSiteResult {
        AppLogger.i("MainViewModel", "Finalizing site: name='${site.name}', id=${site.id}")
        // Get storage settings
        val settingsPreferences = SettingsPreferences(context)
        val folderUriString = settingsPreferences.getFolderUri()
        
        if (folderUriString.isEmpty()) {
            AppLogger.w("MainViewModel", "Site finalization failed: storage not configured, site='${site.name}'")
            return FinalizeSiteResult.Error("Storage not configured. Please configure storage in settings.")
        }
        
        val folderHelper = FolderStructureHelper(context)
        
        // Get the ongoing and finished folders
        val ongoingFolder = try {
            folderHelper.getOngoingFolder(settingsPreferences)
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Error accessing ongoing folder: ${e.message}", e)
            return FinalizeSiteResult.Error("Error accessing ongoing folder: ${e.message}")
        }
        
        val finishedFolder = try {
            folderHelper.getFinishedFolder(settingsPreferences)
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Error accessing finished folder: ${e.message}", e)
            return FinalizeSiteResult.Error("Error accessing finished folder: ${e.message}")
        }
        
        if (ongoingFolder == null || !ongoingFolder.exists() || !ongoingFolder.canRead()) {
            return FinalizeSiteResult.Error("Ongoing folder not accessible")
        }
        
        if (finishedFolder == null || !finishedFolder.exists() || !finishedFolder.canWrite()) {
            return FinalizeSiteResult.Error("Finished folder not accessible")
        }
        
        // Find the site folder in ongoing
        val siteFolder = ongoingFolder.findFile(site.name)
        if (siteFolder == null || !siteFolder.exists()) {
            return FinalizeSiteResult.Error("Site folder '${site.name}' not found in ongoing folder")
        }
        
        // Check if a folder with the same name already exists in finished
        val existingFinishedFolder = finishedFolder.findFile(site.name)
        if (existingFinishedFolder != null && existingFinishedFolder.exists()) {
            // If it exists, rename the source folder with a timestamp to avoid conflicts
            val timestamp = System.currentTimeMillis()
            val newName = "${site.name}_${timestamp}"
            val renameSuccess = siteFolder.renameTo(newName)
            if (renameSuccess) {
                val renamedFolder = ongoingFolder.findFile(newName)
                if (renamedFolder != null && renamedFolder.exists()) {
                    // Now try to move it
                    val moveSuccess = moveFolder(renamedFolder, finishedFolder)
                    if (!moveSuccess) {
                        return FinalizeSiteResult.Error("Could not move folder to finished")
                    }
                } else {
                    return FinalizeSiteResult.Error("Could not find renamed folder")
                }
            } else {
                return FinalizeSiteResult.Error("Could not rename folder before moving")
            }
        } else {
            // Move the folder to finished
            val moveSuccess = moveFolder(siteFolder, finishedFolder)
            if (!moveSuccess) {
                return FinalizeSiteResult.Error("Could not move folder to finished")
            }
        }
        
        // Update site status to FINISHED with NOT_UPLOADED status (only if site has valid ID)
        val updatedSite = site.copy(status = SiteStatus.FINISHED, uploadStatus = UploadStatus.NOT_UPLOADED)
        if (site.id > 0) {
            try {
                database.samplingSiteDao().updateSite(updatedSite)
            } catch (e: Exception) {
                AppLogger.e("MainViewModel", "Error updating site status to FINISHED: ${e.message}", e)
                // Continue anyway - the folder move was successful
            }
        }
        
        // Reload sites from folders to update the UI
        loadSitesFromFolders()
        
        AppLogger.i("MainViewModel", "Site finalized successfully: name='${site.name}', id=${site.id}")
        
        // Trigger upload in background using a scope that won't be cancelled when ViewModel is cleared
        // Use SupervisorJob to prevent cancellation of other uploads if one fails
        val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        uploadScope.launch {
            try {
                uploadSiteToOwnCloud(updatedSite)
            } catch (e: Exception) {
                AppLogger.e("MainViewModel", "Site upload error: name='${updatedSite.name}'", e)
                // Update status to UPLOAD_FAILED if upload fails
                if (updatedSite.id > 0) {
                    try {
                        database.samplingSiteDao().updateSite(
                            updatedSite.copy(uploadStatus = UploadStatus.UPLOAD_FAILED)
                        )
                        loadSitesFromFolders()
                    } catch (dbException: Exception) {
                        AppLogger.e("MainViewModel", "Error updating site upload status: ${dbException.message}", dbException)
                    }
                }
            }
        }
        
        return FinalizeSiteResult.Success
    }
    
    sealed class FinalizeSiteResult {
        object Success : FinalizeSiteResult()
        data class Error(val message: String) : FinalizeSiteResult()
    }
    
    suspend fun deleteSite(site: SamplingSite): DeleteSiteResult {
        AppLogger.i("MainViewModel", "Deleting site: name='${site.name}', id=${site.id}")
        // Get storage settings
        val settingsPreferences = SettingsPreferences(context)
        val folderUriString = settingsPreferences.getFolderUri()
        
        if (folderUriString.isEmpty()) {
            // No storage configured, just delete form completions
            try {
                database.formCompletionDao().deleteCompletionsForSiteByName(site.name)
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Could not delete form completions: ${e.message}")
            }
            loadSitesFromFolders()
            return DeleteSiteResult.Success
        }
        
        val folderHelper = FolderStructureHelper(context)
        
        // Get the ongoing and deleted folders
        val ongoingFolder = try {
            folderHelper.getOngoingFolder(settingsPreferences)
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Error accessing ongoing folder: ${e.message}", e)
            // Still delete from database
            database.samplingSiteDao().deleteSite(site)
            return DeleteSiteResult.Success
        }
        
        val deletedFolder = try {
            folderHelper.getDeletedFolder(settingsPreferences)
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Error accessing deleted folder: ${e.message}", e)
            // Still delete from database
            database.samplingSiteDao().deleteSite(site)
            return DeleteSiteResult.Success
        }
        
        if (ongoingFolder == null || !ongoingFolder.exists() || !ongoingFolder.canRead()) {
            android.util.Log.w("MainViewModel", "Ongoing folder not accessible")
            // Delete form completions anyway
            try {
                database.formCompletionDao().deleteCompletionsForSiteByName(site.name)
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Could not delete form completions: ${e.message}")
            }
            loadSitesFromFolders()
            return DeleteSiteResult.Success
        }
        
        if (deletedFolder == null || !deletedFolder.exists() || !deletedFolder.canWrite()) {
            android.util.Log.w("MainViewModel", "Deleted folder not accessible")
            // Delete form completions anyway
            try {
                database.formCompletionDao().deleteCompletionsForSiteByName(site.name)
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Could not delete form completions: ${e.message}")
            }
            loadSitesFromFolders()
            return DeleteSiteResult.Success
        }
        
        // Find the site folder in ongoing
        val siteFolder = ongoingFolder.findFile(site.name)
        if (siteFolder != null && siteFolder.exists()) {
            // Check if a folder with the same name already exists in deleted
            val existingDeletedFolder = deletedFolder.findFile(site.name)
            if (existingDeletedFolder != null && existingDeletedFolder.exists()) {
                // If it exists, rename the source folder with a timestamp to avoid conflicts
                val timestamp = System.currentTimeMillis()
                val newName = "${site.name}_${timestamp}"
                val renameSuccess = siteFolder.renameTo(newName)
                if (renameSuccess) {
                    val renamedFolder = ongoingFolder.findFile(newName)
                    if (renamedFolder != null && renamedFolder.exists()) {
                        // Now try to move it
                        val moveSuccess = moveFolder(renamedFolder, deletedFolder)
                        if (!moveSuccess) {
                            android.util.Log.w("MainViewModel", "Could not move folder to deleted, but continuing with database deletion")
                        }
                    } else {
                        android.util.Log.w("MainViewModel", "Could not find renamed folder, but continuing with database deletion")
                    }
                } else {
                    android.util.Log.w("MainViewModel", "Could not rename folder before moving, but continuing with database deletion")
                }
            } else {
                // Move the folder to deleted
                val moveSuccess = moveFolder(siteFolder, deletedFolder)
                if (!moveSuccess) {
                    android.util.Log.w("MainViewModel", "Could not move folder to deleted, but continuing with database deletion")
                }
            }
        } else {
            android.util.Log.w("MainViewModel", "Site folder '${site.name}' not found in ongoing folder")
        }
        
        // Delete form completions for this site (using site name)
        try {
            database.formCompletionDao().deleteCompletionsForSiteByName(site.name)
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "Could not delete form completions: ${e.message}")
        }
        
        // Reload sites from folders to update the UI
        loadSitesFromFolders()
        
        AppLogger.i("MainViewModel", "Site deleted successfully: name='${site.name}', id=${site.id}")
        return DeleteSiteResult.Success
    }
    
    /**
     * Moves a folder from source to destination by copying recursively and then deleting the source.
     * Returns true if the move was successful, false otherwise.
     */
    private fun moveFolder(sourceFolder: androidx.documentfile.provider.DocumentFile, destinationFolder: androidx.documentfile.provider.DocumentFile): Boolean {
        if (!sourceFolder.exists() || !sourceFolder.isDirectory) {
            android.util.Log.e("MainViewModel", "Source folder does not exist or is not a directory")
            return false
        }
        
        if (!destinationFolder.exists() || !destinationFolder.canWrite()) {
            android.util.Log.e("MainViewModel", "Destination folder does not exist or is not writable")
            return false
        }
        
        // Check if a folder with the same name already exists in destination
        val folderName = sourceFolder.name ?: return false
        val existingFolder = destinationFolder.findFile(folderName)
        if (existingFolder != null && existingFolder.exists()) {
            android.util.Log.w("MainViewModel", "Folder '$folderName' already exists in deleted folder")
            // We could append a timestamp, but for now let's just return false
            return false
        }
        
        // Create the destination folder
        val newFolder = destinationFolder.createDirectory(folderName)
        if (newFolder == null || !newFolder.exists()) {
            android.util.Log.e("MainViewModel", "Could not create folder '${sourceFolder.name}' in destination")
            return false
        }
        
        // Recursively copy all files and subfolders
        val copySuccess = copyFolderRecursive(sourceFolder, newFolder)
        
        if (copySuccess) {
            // Delete the source folder and all its contents
            val deleteSuccess = sourceFolder.delete()
            if (!deleteSuccess) {
                android.util.Log.w("MainViewModel", "Could not delete source folder after copying")
                // The copy succeeded, so we'll consider it a partial success
                return true
            }
            return true
        } else {
            // Copy failed, try to clean up the destination folder
            newFolder.delete()
            return false
        }
    }
    
    /**
     * Recursively copies all files and subfolders from source to destination.
     */
    private fun copyFolderRecursive(source: androidx.documentfile.provider.DocumentFile, destination: androidx.documentfile.provider.DocumentFile): Boolean {
        try {
            val files = source.listFiles()
            if (files.isEmpty()) {
                android.util.Log.w("MainViewModel", "Could not list files in source folder")
                return true // Empty folder, consider it success
            }
            
            for (file in files) {
                val fileName = file.name ?: continue // Skip files/folders without a name
                if (file.isDirectory) {
                    // Create subdirectory in destination
                    val newSubDir = destination.createDirectory(fileName)
                    if (newSubDir == null || !newSubDir.exists()) {
                        android.util.Log.e("MainViewModel", "Could not create subdirectory '$fileName'")
                        return false
                    }
                    // Recursively copy subdirectory
                    if (!copyFolderRecursive(file, newSubDir)) {
                        return false
                    }
                } else {
                    // Copy file
                    if (!copyFile(file, destination)) {
                        android.util.Log.e("MainViewModel", "Could not copy file '$fileName'")
                        return false
                    }
                }
            }
            return true
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Error copying folder: ${e.message}", e)
            return false
        }
    }
    
    /**
     * Copies a single file from source to destination directory.
     */
    private fun copyFile(sourceFile: androidx.documentfile.provider.DocumentFile, destinationDir: androidx.documentfile.provider.DocumentFile): Boolean {
        try {
            // Create the destination file
            val fileName = sourceFile.name ?: return false
            val destinationFile = destinationDir.createFile(sourceFile.type ?: "*/*", fileName)
            if (destinationFile == null || !destinationFile.exists()) {
                android.util.Log.e("MainViewModel", "Could not create destination file '${sourceFile.name}'")
                return false
            }
            
            // Copy the file content using use blocks for automatic resource management
            context.contentResolver.openInputStream(sourceFile.uri)?.use { inputStream ->
                context.contentResolver.openOutputStream(destinationFile.uri)?.use { outputStream ->
                    inputStream.copyTo(outputStream)
                    return true
                } ?: run {
                    android.util.Log.e("MainViewModel", "Could not open output stream for file '${sourceFile.name}'")
                    return false
                }
            } ?: run {
                android.util.Log.e("MainViewModel", "Could not open input stream for file '${sourceFile.name}'")
                return false
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Error copying file '${sourceFile.name}': ${e.message}", e)
            return false
        }
    }
    
    sealed class DeleteSiteResult {
        object Success : DeleteSiteResult()
        data class Error(val message: String) : DeleteSiteResult()
    }
    
    /**
     * Uploads a finished site to ownCloud
     */
    suspend fun uploadSiteToOwnCloud(site: SamplingSite): UploadSiteResult {
        AppLogger.i("MainViewModel", "Starting site upload: name='${site.name}', id=${site.id}")
        
        // Update status to UPLOADING
        // Try to find the site in the database by name if ID is 0
        try {
            val siteToUpdate = if (site.id > 0) {
                site
            } else {
                // Find site by name
                val foundSite = database.samplingSiteDao().getAllSites().first().find { it.name == site.name }
                if (foundSite != null) {
                    foundSite
                } else {
                    // Site not in database yet, create it
                    val newSite = SamplingSite(
                        id = 0,
                        name = site.name,
                        status = SiteStatus.FINISHED,
                        uploadStatus = UploadStatus.UPLOADING,
                        createdAt = System.currentTimeMillis()
                    )
                    val insertedId = database.samplingSiteDao().insertSite(newSite)
                    newSite.copy(id = insertedId)
                }
            }
            
            database.samplingSiteDao().updateSite(
                siteToUpdate.copy(uploadStatus = UploadStatus.UPLOADING)
            )
            AppLogger.d("MainViewModel", "Updated site upload status to UPLOADING: name='${site.name}', id=${siteToUpdate.id}")
            loadSitesFromFolders()
        } catch (e: Exception) {
            AppLogger.e("MainViewModel", "Error updating site upload status to UPLOADING: ${e.message}", e)
            // Continue anyway - the upload can still proceed
        }
        
        try {
            val settingsPreferences = SettingsPreferences(context)
            val folderHelper = FolderStructureHelper(context)
            val ownCloudManager = OwnCloudManager(context)
            
            // Get the finished folder
            val finishedFolder = folderHelper.getFinishedFolder(settingsPreferences)
                ?: return UploadSiteResult.Error("Finished folder not accessible")
            
            // Find the site folder
            val siteFolder = finishedFolder.findFile(site.name)
            if (siteFolder == null || !siteFolder.exists()) {
                AppLogger.e("MainViewModel", "Site folder not found: name='${site.name}'")
                if (site.id > 0) {
                    try {
                        database.samplingSiteDao().updateSite(
                            site.copy(uploadStatus = UploadStatus.UPLOAD_FAILED)
                        )
                        loadSitesFromFolders()
                    } catch (e: Exception) {
                        AppLogger.e("MainViewModel", "Error updating site upload status: ${e.message}", e)
                    }
                }
                return UploadSiteResult.Error("Site folder not found")
            }
            
            // Get UUID
            val appUuid = settingsPreferences.getAppUuid()
            
            // Upload folder
            val uploadResult = ownCloudManager.uploadFolder(
                uuidFolder = appUuid,
                siteFolderName = site.name,
                localFolder = siteFolder
            ) { uploaded, total ->
                AppLogger.d("MainViewModel", "Upload progress: $uploaded/$total files")
            }
            
            if (uploadResult.success && uploadResult.uploadedCount > 0) {
                // Update status to UPLOADED
                // Try to find the site in the database by name if ID is 0
                try {
                    val siteToUpdate = if (site.id > 0) {
                        site
                    } else {
                        // Find site by name
                        val foundSite = database.samplingSiteDao().getAllSites().first().find { it.name == site.name }
                        if (foundSite != null) {
                            foundSite
                        } else {
                            // Site not in database yet, create it
                            val newSite = SamplingSite(
                                id = 0,
                                name = site.name,
                                status = SiteStatus.FINISHED,
                                uploadStatus = UploadStatus.UPLOADED,
                                createdAt = System.currentTimeMillis()
                            )
                            val insertedId = database.samplingSiteDao().insertSite(newSite)
                            newSite.copy(id = insertedId)
                        }
                    }
                    
                    database.samplingSiteDao().updateSite(
                        siteToUpdate.copy(uploadStatus = UploadStatus.UPLOADED)
                    )
                    AppLogger.i("MainViewModel", "Updated site upload status to UPLOADED: name='${site.name}', id=${siteToUpdate.id}")
                    loadSitesFromFolders()
                } catch (e: Exception) {
                    AppLogger.e("MainViewModel", "Error updating site upload status to UPLOADED: ${e.message}", e)
                }
                AppLogger.i("MainViewModel", "Site upload completed successfully: name='${site.name}', files=${uploadResult.uploadedCount}/${uploadResult.totalCount}")
                return UploadSiteResult.Success(uploadResult.uploadedCount, uploadResult.totalCount)
            } else {
                // Update status to UPLOAD_FAILED (only if site has valid ID)
                if (site.id > 0) {
                    try {
                        database.samplingSiteDao().updateSite(
                            site.copy(uploadStatus = UploadStatus.UPLOAD_FAILED)
                        )
                        loadSitesFromFolders()
                    } catch (e: Exception) {
                        AppLogger.e("MainViewModel", "Error updating site upload status to UPLOAD_FAILED: ${e.message}", e)
                    }
                }
                AppLogger.e("MainViewModel", "Site upload failed: name='${site.name}', error='${uploadResult.errorMessage}'")
                return UploadSiteResult.Error(uploadResult.errorMessage ?: "Upload failed")
            }
        } catch (e: Exception) {
            // Update status to UPLOAD_FAILED (only if site has valid ID)
            if (site.id > 0) {
                try {
                    database.samplingSiteDao().updateSite(
                        site.copy(uploadStatus = UploadStatus.UPLOAD_FAILED)
                    )
                    loadSitesFromFolders()
                } catch (dbException: Exception) {
                    AppLogger.e("MainViewModel", "Error updating site upload status to UPLOAD_FAILED: ${dbException.message}", dbException)
                }
            }
            AppLogger.e("MainViewModel", "Site upload error: name='${site.name}'", e)
            return UploadSiteResult.Error("Upload error: ${e.message}")
        }
    }
    
    sealed class UploadSiteResult {
        data class Success(val uploadedCount: Int, val totalCount: Int) : UploadSiteResult()
        data class Error(val message: String) : UploadSiteResult()
    }
}

class MainViewModelFactory(
    private val database: AppDatabase,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(database, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

