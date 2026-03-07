package com.trec.trecollect.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trec.trecollect.data.AppDatabase
import com.trec.trecollect.data.FolderStructureHelper
import com.trec.trecollect.data.SamplingSite
import com.trec.trecollect.data.SettingsPreferences
import com.trec.trecollect.data.SiteMetadata
import com.trec.trecollect.data.SiteStatus
import com.trec.trecollect.data.UploadStatus
import com.trec.trecollect.data.OwnCloudManager
import com.trec.trecollect.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import androidx.annotation.VisibleForTesting
import androidx.documentfile.provider.DocumentFile

class MainViewModel(
    private val database: AppDatabase,
    private val context: Context
) : ViewModel() {
    private val _ongoingSites = MutableStateFlow<List<SamplingSite>>(emptyList())
    val ongoingSites: StateFlow<List<SamplingSite>> = _ongoingSites.asStateFlow()
    
    private val _finishedSites = MutableStateFlow<List<SamplingSite>>(emptyList())
    val finishedSites: StateFlow<List<SamplingSite>> = _finishedSites.asStateFlow()
    
    // Debounce mechanism to prevent excessive reloads
    private var lastLoadTime = 0L
    private val LOAD_DEBOUNCE_MS = 500L // Minimum time between loads
    
    init {
        // Load sites from folder structure on initialization
        loadSitesFromFolders()
    }
    
    /**
     * Loads sites from the folder structure (ongoing and finished folders)
     * Includes debouncing to prevent excessive reloads
     * @param force If true, bypasses debounce and forces immediate reload
     */
    fun loadSitesFromFolders(force: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        // Debounce: skip if called too soon after last load (unless forced)
        if (!force && currentTime - lastLoadTime < LOAD_DEBOUNCE_MS && lastLoadTime > 0) {
            AppLogger.d("MainViewModel", "Skipping loadSitesFromFolders - debounced (last load ${currentTime - lastLoadTime}ms ago)")
            return
        }
        lastLoadTime = currentTime
        
        viewModelScope.launch(Dispatchers.IO) {
            val settingsPreferences = SettingsPreferences(context)
            val folderUriString = settingsPreferences.getFolderUri()
            
            if (folderUriString.isEmpty()) {
                // No storage configured, set empty lists immediately
                withContext(Dispatchers.Main) {
                    _ongoingSites.value = emptyList()
                    _finishedSites.value = emptyList()
                }
                return@launch
            }
            
            val folderHelper = FolderStructureHelper(context)
            
            // Ensure all subfolders exist (ongoing, finished, deleted) for current team/subteam
            folderHelper.ensureSubfoldersExist(settingsPreferences)
            
            // Load all sites from database ONCE (optimization: avoid querying for each folder)
            // Removed delay - not needed, database operations are already async
            val allDbSites = try {
                database.samplingSiteDao().getAllSites().first()
            } catch (e: Exception) {
                AppLogger.e("MainViewModel", "Error loading sites from database: ${e.message}", e)
                emptyList()
            }
            // Create a map for O(1) lookup by name
            val dbSitesByName = allDbSites.associateBy { it.name }
            AppLogger.d("MainViewModel", "Loaded ${allDbSites.size} sites from database for folder loading")
            
            // Load ongoing sites first (simpler, no DB operations needed)
            val ongoingFolder = try {
                folderHelper.getOngoingFolder(settingsPreferences)
            } catch (e: Exception) {
                AppLogger.e("MainViewModel", "Error loading ongoing folder: ${e.message}", e)
                null
            }
            
            val ongoingSitesList = if (ongoingFolder != null && ongoingFolder.exists() && ongoingFolder.canRead()) {
                try {
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
                } catch (e: Exception) {
                    AppLogger.e("MainViewModel", "Error listing ongoing folders: ${e.message}", e)
                    emptyList()
                }
            } else {
                emptyList()
            }
            
            // Update ongoing sites immediately for faster UI response
            withContext(Dispatchers.Main) {
                _ongoingSites.value = ongoingSitesList
            }
            
            // Load finished sites (more complex, involves DB operations)
            val finishedFolder = try {
                folderHelper.getFinishedFolder(settingsPreferences)
            } catch (e: Exception) {
                AppLogger.e("MainViewModel", "Error loading finished folder: ${e.message}", e)
                null
            }
            
            val finishedSitesList = if (finishedFolder != null && finishedFolder.exists() && finishedFolder.canRead()) {
                try {
                    val folders = finishedFolder.listFiles()
                    // Batch database operations: collect all sites to insert/update first
                    val sitesToInsert = mutableListOf<SamplingSite>()
                    val sitesToUpdate = mutableListOf<SamplingSite>()
                    
                    val folderSites = folders.filter { it.isDirectory && it.name != null }
                        .mapNotNull { folder ->
                            // Try to find existing site in database to preserve upload status
                            // Use the pre-loaded map for O(1) lookup instead of querying for each folder
                            val dbSite = dbSitesByName[folder.name]
                            
                            if (dbSite != null) {
                                // Use database record, but ensure status is FINISHED (since it's in finished folder)
                                val siteWithCorrectStatus = if (dbSite.status != SiteStatus.FINISHED) {
                                    // Queue for batch update
                                    sitesToUpdate.add(dbSite.copy(status = SiteStatus.FINISHED))
                                    dbSite.copy(status = SiteStatus.FINISHED)
                                } else {
                                    dbSite
                                }
                                AppLogger.d("MainViewModel", "Found site in database: name='${folder.name}', id=${siteWithCorrectStatus.id}, status=${siteWithCorrectStatus.status}, uploadStatus=${siteWithCorrectStatus.uploadStatus}")
                                siteWithCorrectStatus
                            } else {
                                AppLogger.d("MainViewModel", "Site not found in database, will create: name='${folder.name}'")
                                val formFileHelper = com.trec.trecollect.data.FormFileHelper(context)
                                val metadata = formFileHelper.loadSiteMetadata(folder.name!!)
                                val uploadStatus = if (metadata?.uploadedAt != null && metadata.uploadedAt.isNotEmpty())
                                    UploadStatus.UPLOADED else UploadStatus.NOT_UPLOADED
                                val newSite = SamplingSite(
                                    id = 0,
                                    name = folder.name!!,
                                    status = SiteStatus.FINISHED,
                                    uploadStatus = uploadStatus,
                                    createdAt = System.currentTimeMillis()
                                )
                                sitesToInsert.add(newSite)
                                newSite
                            }
                        }
                        .sortedBy { it.name }
                    
                    // Batch insert new sites and track inserted IDs
                    val insertedSitesMap = mutableMapOf<String, SamplingSite>()
                    if (sitesToInsert.isNotEmpty()) {
                        try {
                            sitesToInsert.forEach { site ->
                                val insertedId = database.samplingSiteDao().insertSite(site)
                                val insertedSite = site.copy(id = insertedId)
                                insertedSitesMap[site.name] = insertedSite
                                AppLogger.d("MainViewModel", "Inserted new site: name='${site.name}', id=$insertedId")
                            }
                        } catch (e: Exception) {
                            AppLogger.e("MainViewModel", "Error batch inserting sites: ${e.message}", e)
                        }
                    }
                    
                    // Batch update sites with status changes
                    if (sitesToUpdate.isNotEmpty()) {
                        try {
                            sitesToUpdate.forEach { site ->
                                database.samplingSiteDao().updateSite(site)
                            }
                        } catch (e: Exception) {
                            AppLogger.e("MainViewModel", "Error batch updating sites: ${e.message}", e)
                        }
                    }
                    
                    // Map folder sites to database sites with correct IDs
                    // folderSites already has correct status and data from mapNotNull above
                    // We just need to update IDs for sites that were inserted or already existed
                    folderSites.map { folderSite ->
                        // First check if it was just inserted (has correct ID now)
                        insertedSitesMap[folderSite.name] 
                            // Then check original database map (for sites that already existed)
                            ?: dbSitesByName[folderSite.name] 
                            // Fallback to folder site (new site that will be inserted)
                            ?: folderSite
                    }.sortedBy { it.name }
                } catch (e: Exception) {
                    AppLogger.e("MainViewModel", "Error processing finished folders: ${e.message}", e)
                    emptyList()
                }
            } else {
                emptyList()
            }
            
            // Update finished sites StateFlow on main thread
            withContext(Dispatchers.Main) {
                val statuses = finishedSitesList.joinToString { "${it.name}=${it.uploadStatus}" }
                AppLogger.d("UploadCheckbox", "loadSitesFromFolders setting _finishedSites: size=${finishedSitesList.size}, sites=$statuses")
                _finishedSites.value = finishedSitesList
            }
        }
    }
    
    suspend fun createSite(name: String): CreateSiteResult {
        val trimmedName = name.trim()
        AppLogger.i("MainViewModel", "Creating site: name='$trimmedName'")
        if (trimmedName.isBlank()) {
            AppLogger.w("MainViewModel", "Site creation failed: name is empty")
            return CreateSiteResult.Error("Site name cannot be empty")
        }
        
        // Check if site already exists using cached lists (avoid file I/O; use DB/cache)
        if (ongoingSites.first().any { it.name == trimmedName }) {
            return CreateSiteResult.Error("A site with this name already exists")
        }
        if (finishedSites.first().any { it.name == trimmedName }) {
            return CreateSiteResult.Error("A site with this name already exists")
        }

        // Check if storage is configured
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        val folderUriString = settingsPreferences.getFolderUri()
        
        if (folderUriString.isEmpty()) {
            return CreateSiteResult.Error("Storage folder not configured. Please select a folder in Settings.")
        }
        
        // Try to get the TRECollect_logsheets folder (the stored URI should point to TRECollect_logsheets)
        val trecFolder = try {
            folderHelper.getTrecLogsheetsFolder(settingsPreferences)
        } catch (e: Exception) {
            return CreateSiteResult.Error("Error accessing storage folder: ${e.message}")
        }
        
        if (trecFolder == null) {
            return CreateSiteResult.Error("TRECollect_logsheets folder not found. Please reconfigure storage in Settings.")
        }
        
        // CRITICAL: Verify we have the TRECollect_logsheets folder by checking its name
        val folderName = trecFolder.name
            AppLogger.d("MainViewModel", "Retrieved folder name: '$folderName', expected: '${FolderStructureHelper.PARENT_FOLDER_NAME}'")
        
        if (folderName != FolderStructureHelper.PARENT_FOLDER_NAME) {
            // The URI might point to the parent folder, try to find TRECollect_logsheets inside it
            AppLogger.w("MainViewModel", "Folder name mismatch! Looking for TRECollect_logsheets inside '$folderName'...")
            val actualTrecFolder = trecFolder.findFile(FolderStructureHelper.PARENT_FOLDER_NAME)
            if (actualTrecFolder != null && actualTrecFolder.exists()) {
                AppLogger.d("MainViewModel", "Found TRECollect_logsheets inside parent folder")
                // Use the actual TRECollect_logsheets folder
                val verifiedTrecFolder = actualTrecFolder
                return createSiteInFolder(trimmedName, verifiedTrecFolder)
            } else {
                return CreateSiteResult.Error("TRECollect_logsheets folder not found. The stored URI points to '$folderName' instead. Please reconfigure storage in Settings.")
            }
        }
        
        // Continue with the verified TRECollect_logsheets folder
        return createSiteInFolder(trimmedName, trecFolder)
    }

    /**
     * Returns the ongoing folder ready for write, or (null, errorMessage). Reused to avoid duplication.
     */
    private fun getOngoingFolderForWrite(): Pair<DocumentFile?, String?> {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        if (!folderHelper.ensureSubfoldersExist(settingsPreferences)) {
            return Pair(null, "Could not create folder structure for current team/subteam. Please check storage permissions.")
        }
        val ongoingFolder = folderHelper.getOngoingFolder(settingsPreferences)
        if (ongoingFolder == null || !ongoingFolder.exists()) {
            return Pair(null, "Ongoing folder not found. Please reconfigure storage.")
        }
        if (!ongoingFolder.canRead() || !ongoingFolder.canWrite()) {
            return Pair(null, "Cannot access ongoing folder. Please check permissions.")
        }
        return Pair(ongoingFolder, null)
    }

    /**
     * Returns the finished folder ready for write, or (null, errorMessage). Used by finalizeSite.
     */
    private fun getFinishedFolderForWrite(): Pair<DocumentFile?, String?> {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        if (!folderHelper.ensureSubfoldersExist(settingsPreferences)) {
            return Pair(null, "Could not create folder structure for current team/subteam. Please check storage permissions.")
        }
        val finishedFolder = folderHelper.getFinishedFolder(settingsPreferences)
        if (finishedFolder == null || !finishedFolder.exists()) {
            return Pair(null, "Finished folder not found. Please reconfigure storage.")
        }
        if (!finishedFolder.canRead() || !finishedFolder.canWrite()) {
            return Pair(null, "Cannot access finished folder. Please check permissions.")
        }
        return Pair(finishedFolder, null)
    }

    /** Deletes form completions for a site and reloads the site list. Used when storage is unavailable or folder checks fail. */
    private suspend fun deleteFormCompletionsForSiteAndReload(siteName: String) {
        try {
            database.formCompletionDao().deleteCompletionsForSiteByName(siteName)
        } catch (e: Exception) {
            AppLogger.w("MainViewModel", "Could not delete form completions: ${e.message}")
        }
        loadSitesFromFolders()
    }

    /** Updates site to UPLOAD_FAILED in DB and refreshes UI (StateFlow in-place or loadSitesFromFolders). */
    private suspend fun setSiteUploadFailedAndRefresh(site: SamplingSite) {
        val siteToUpdate = if (site.id > 0) site else database.samplingSiteDao().getSiteByName(site.name)
        if (siteToUpdate == null) return
        val failedSite = siteToUpdate.copy(uploadStatus = UploadStatus.UPLOAD_FAILED)
        try {
            database.samplingSiteDao().updateSite(failedSite)
            viewModelScope.launch(Dispatchers.Main) {
                val current = _finishedSites.value.toMutableList()
                val idx = current.indexOfFirst { it.name == site.name }
                if (idx >= 0) {
                    current[idx] = failedSite
                    _finishedSites.value = current
                } else {
                    loadSitesFromFolders(force = true)
                }
            }
            viewModelScope.launch(Dispatchers.IO) {
                loadSitesFromFolders(force = true)
            }
        } catch (e: Exception) {
            AppLogger.e("MainViewModel", "Error updating site upload status to UPLOAD_FAILED: ${e.message}", e)
        }
    }

    /** Returns the site to use for upload (by id or by name); creates a new DB record if missing. */
    private suspend fun getOrCreateSiteForUpload(site: SamplingSite): SamplingSite? {
        if (site.id > 0) return site
        val found = database.samplingSiteDao().getSiteByName(site.name)
        if (found != null) return found
        val newSite = SamplingSite(
            id = 0,
            name = site.name,
            status = SiteStatus.FINISHED,
            uploadStatus = UploadStatus.NOT_UPLOADED,
            createdAt = System.currentTimeMillis()
        )
        return try {
            val id = database.samplingSiteDao().insertSite(newSite)
            newSite.copy(id = id)
        } catch (e: Exception) {
            AppLogger.e("MainViewModel", "Error creating site for upload: ${e.message}", e)
            null
        }
    }

    /** Updates site upload status in DB and refreshes UI. Use for UPLOADING and UPLOADED. */
    private suspend fun updateSiteUploadStatusAndReload(site: SamplingSite, uploadStatus: UploadStatus) {
        AppLogger.d("UploadCheckbox", "updateSiteUploadStatusAndReload called: site=${site.name}, uploadStatus=$uploadStatus")
        val siteToUpdate = getOrCreateSiteForUpload(site) ?: return
        val updated = siteToUpdate.copy(uploadStatus = uploadStatus)
        try {
            database.samplingSiteDao().updateSite(updated)
            withContext(Dispatchers.Main) {
                val current = _finishedSites.value.toMutableList()
                val idx = current.indexOfFirst { it.name == site.name }
                if (idx >= 0) {
                    current[idx] = updated
                } else {
                    current.add(updated)
                    current.sortBy { it.name }
                }
                _finishedSites.value = current
            }
            loadSitesFromFolders(force = true)
            // Do not call loadSitesFromFolders here: we already updated _finishedSites in-place.
            // A full reload from disk can overwrite with empty/stale data (e.g. in tests or when folder is not ready).
        } catch (e: Exception) {
            AppLogger.e("MainViewModel", "Error updating site upload status: ${e.message}", e)
        }
    }

    /** Ensures site is in DB with given status and upload status; returns the site (for finalize and similar flows). */
    private suspend fun ensureSiteInDbWithStatus(site: SamplingSite, status: SiteStatus, uploadStatus: UploadStatus): SamplingSite {
        val updated = site.copy(status = status, uploadStatus = uploadStatus)
        if (site.id > 0) {
            database.samplingSiteDao().updateSite(updated)
            return database.samplingSiteDao().getSiteByName(site.name) ?: updated
        }
        val found = database.samplingSiteDao().getSiteByName(site.name)
        if (found != null) {
            val withStatus = found.copy(status = status, uploadStatus = uploadStatus)
            database.samplingSiteDao().updateSite(withStatus)
            return database.samplingSiteDao().getSiteByName(site.name) ?: withStatus
        }
        val newSite = SamplingSite(
            id = 0,
            name = site.name,
            status = status,
            uploadStatus = uploadStatus,
            createdAt = System.currentTimeMillis()
        )
        val id = database.samplingSiteDao().insertSite(newSite)
        return newSite.copy(id = id)
    }
    
    private suspend fun createSiteInFolder(siteName: String, trecFolder: DocumentFile): CreateSiteResult {
        // Check if TRECollect_logsheets folder exists and is accessible (name already verified by createSite() caller)
        if (!trecFolder.canRead() || !trecFolder.canWrite()) {
            return CreateSiteResult.Error("Cannot access TRECollect_logsheets folder. Please check permissions in Settings.")
        }

        val (ongoingFolder, folderError) = getOngoingFolderForWrite()
        if (folderError != null) return CreateSiteResult.Error(folderError)
        if (ongoingFolder == null) return CreateSiteResult.Error("Ongoing folder not found. Please reconfigure storage.")
        
        // Check if folder for this site already exists (use both findFile and listFiles for reliability)
        var siteFolder = ongoingFolder.findFile(siteName)
        if (siteFolder == null || !siteFolder.exists()) {
            // Also check by listing files (fallback in case findFile() doesn't work reliably)
            try {
                val files = ongoingFolder.listFiles()
                siteFolder = files.firstOrNull { it.name == siteName && it.isDirectory && it.exists() }
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Error listing files to check for existing site: ${e.message}")
            }
        }
        
        if (siteFolder != null && siteFolder.exists()) {
            AppLogger.i("MainViewModel", "Site folder already exists: name='$siteName'")
            return CreateSiteResult.Error("A folder for this site already exists.")
        }
        
        // Create the site folder
        val createdFolder = ongoingFolder.createDirectory(siteName)
        if (createdFolder == null) {
            AppLogger.e("MainViewModel", "Failed to create site folder: name='$siteName'")
            return CreateSiteResult.Error("Could not create site folder in TRECollect_logsheets/ongoing/")
        }
        
        // If created folder name differs from requested, treat as error and remove wrong folder
        if (createdFolder.name != siteName) {
            AppLogger.w("MainViewModel", "Created folder has unexpected name: '${createdFolder.name}' instead of '$siteName'")
            try {
                createdFolder.delete()
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Could not delete wrongly named folder: ${e.message}")
            }
            return CreateSiteResult.Error(
                "Could not create site folder with the requested name (created '${createdFolder.name}' instead)."
            )
        }
        
        AppLogger.i("MainViewModel", "Site created successfully: name='$siteName'")
        
        val settingsPreferences = SettingsPreferences(context)
        // Get team config info for metadata
        val team = settingsPreferences.getSamplingTeam()
        val subteam = settingsPreferences.getSamplingSubteam()
        val downloader = com.trec.trecollect.data.LogsheetDownloader(context)
        val teamConfigFile = downloader.findTeamConfigByTeamAndName(team, subteam.takeIf { it.isNotEmpty() })
        
        var teamConfigId: String? = null
        var teamConfigVersion: String? = null
        
        if (teamConfigFile != null) {
            // Extract team config ID (folder name) and version (filename without .json)
            teamConfigId = teamConfigFile.parentFile?.name
            teamConfigVersion = teamConfigFile.name.removeSuffix(".json")
        }
        
        // Create and save site metadata
        val metadata = SiteMetadata(
            siteName = siteName,
            createdAt = SiteMetadata.getCurrentTimestamp(),
            teamConfigId = teamConfigId ?: "",
            teamConfigVersion = teamConfigVersion ?: ""
        )
        
        val formFileHelper = com.trec.trecollect.data.FormFileHelper(context)
        if (!formFileHelper.saveSiteMetadata(siteName, metadata)) {
            AppLogger.w("MainViewModel", "Could not save site metadata for site: $siteName")
        }
        
        // Insert site into database
        val newSite = try {
            val site = SamplingSite(
                id = 0,
                name = siteName,
                status = SiteStatus.ONGOING,
                uploadStatus = UploadStatus.NOT_UPLOADED,
                createdAt = System.currentTimeMillis()
            )
            val insertedId = database.samplingSiteDao().insertSite(site)
            site.copy(id = insertedId)
        } catch (e: Exception) {
            AppLogger.w("MainViewModel", "Could not insert site into database: ${e.message}")
            // Return site without ID if database insert fails
            SamplingSite(
                id = 0,
                name = siteName,
                status = SiteStatus.ONGOING,
                uploadStatus = UploadStatus.NOT_UPLOADED,
                createdAt = System.currentTimeMillis()
            )
        }
        
        // Reload sites from folders to update the UI
        // Note: File system operations are synchronous, so no delay needed
        loadSitesFromFolders()
        
        return CreateSiteResult.Success(newSite)
    }
    
    sealed class CreateSiteResult {
        data class Success(val site: SamplingSite) : CreateSiteResult()
        data class Error(val message: String) : CreateSiteResult()
    }
    
    fun renameSite(site: SamplingSite, newName: String): RenameSiteResult {
        val trimmedName = newName.trim()
        if (trimmedName.isBlank()) {
            return RenameSiteResult.Error("Site name cannot be empty")
        }
        
        if (trimmedName == site.name) {
            return RenameSiteResult.Error("New name is the same as the current name")
        }

        // Reuse folder lookup util
        val (ongoingFolder, folderError) = getOngoingFolderForWrite()
        if (folderError != null) return RenameSiteResult.Error(folderError)
        if (ongoingFolder == null) return RenameSiteResult.Error("Ongoing folder not found. Please reconfigure storage in Settings.")

        val newSiteFolder = ongoingFolder.findFile(trimmedName)
        if (newSiteFolder != null && newSiteFolder.exists() && newSiteFolder.name != site.name) {
            return RenameSiteResult.Error("A site with this name already exists")
        }
        val (finishedFolder, _) = getFinishedFolderForWrite()
        if (finishedFolder != null) {
            val existingInFinished = finishedFolder.findFile(trimmedName)
            if (existingInFinished != null && existingInFinished.exists()) {
                return RenameSiteResult.Error("A site with this name already exists")
            }
        }
        
        // Find the old site folder
        val oldSiteFolder = ongoingFolder.findFile(site.name)
        if (oldSiteFolder == null || !oldSiteFolder.exists()) {
            // Folder doesn't exist, but we can still proceed
            AppLogger.w("MainViewModel", "Site folder '${site.name}' not found")
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
    
    suspend fun finalizeSite(site: SamplingSite): FinalizeSiteResult {
        AppLogger.i("MainViewModel", "Finalizing site: name='${site.name}', id=${site.id}")
        val settingsPreferences = SettingsPreferences(context)
        if (settingsPreferences.getFolderUri().isEmpty()) {
            AppLogger.w("MainViewModel", "Site finalization failed: storage not configured, site='${site.name}'")
            return FinalizeSiteResult.Error("Storage not configured. Please configure storage in settings.")
        }

        // Reuse folder lookup utils)
        val (ongoingFolder, ongoingError) = getOngoingFolderForWrite()
        if (ongoingError != null) return FinalizeSiteResult.Error(ongoingError)
        if (ongoingFolder == null) return FinalizeSiteResult.Error("Ongoing folder not accessible")
        val (finishedFolder, finishedError) = getFinishedFolderForWrite()
        if (finishedError != null) return FinalizeSiteResult.Error(finishedError)
        if (finishedFolder == null) return FinalizeSiteResult.Error("Finished folder not accessible")
        
        val siteFolder = ongoingFolder.findFile(site.name)
        if (siteFolder == null || !siteFolder.exists()) {
            return FinalizeSiteResult.Error("Site folder '${site.name}' not found in ongoing folder")
        }
        
        val existingFinishedFolder = finishedFolder.findFile(site.name)
        if (existingFinishedFolder != null && existingFinishedFolder.exists()) {
            // Do not auto-rename; ask user to rename manually
            return FinalizeSiteResult.Error(
                "A site with this name already exists in the finished sites. Please rename the site first."
            )
        }
        
        val moveSuccess = moveFolder(siteFolder, finishedFolder)
        if (!moveSuccess) {
            return FinalizeSiteResult.Error("Could not move folder to finished")
        }
        
        // Update site metadata with submission timestamp (before moving folder)
        val formFileHelper = com.trec.trecollect.data.FormFileHelper(context)
        val existingMetadata = formFileHelper.loadSiteMetadata(site.name)
        if (existingMetadata != null) {
            val updatedMetadata = existingMetadata.copy(submittedAt = SiteMetadata.getCurrentTimestamp())
            formFileHelper.saveSiteMetadata(site.name, updatedMetadata)
        } else {
            // Create new metadata if it doesn't exist (shouldn't happen, but handle gracefully)
            val metadata = SiteMetadata(
                siteName = site.name,
                createdAt = SiteMetadata.getCurrentTimestamp(),
                submittedAt = SiteMetadata.getCurrentTimestamp()
            )
            formFileHelper.saveSiteMetadata(site.name, metadata)
        }
        
        val updatedSite = site.copy(status = SiteStatus.FINISHED, uploadStatus = UploadStatus.NOT_UPLOADED)
        val siteForUpload = try {
            ensureSiteInDbWithStatus(site, SiteStatus.FINISHED, UploadStatus.NOT_UPLOADED)
        } catch (e: Exception) {
            AppLogger.e("MainViewModel", "Error ensuring site in database: ${e.message}", e)
            updatedSite
        }

        AppLogger.i("MainViewModel", "Site finalized successfully: name='${site.name}', id=${siteForUpload.id}")
        return FinalizeSiteResult.Success(siteForUpload)
    }
    
    /**
     * Starts automatic upload for a site in the background. Call this from MainActivity when
     * launched with a site that was just finalized (so the upload runs in MainActivity's ViewModel
     * and the checkbox updates when it completes).
     */
    fun startAutomaticUploadForSite(site: SamplingSite) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AppLogger.i("MainViewModel", "Starting automatic upload for site: name='${site.name}'")
                Handler(Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        context.applicationContext,
                        "Uploading ${site.name}...",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                val result = uploadSiteToOwnCloud(site)
                Handler(Looper.getMainLooper()).post {
                    when (result) {
                        is UploadSiteResult.Success -> {
                            android.widget.Toast.makeText(
                                context.applicationContext,
                                "${site.name} uploaded successfully",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        is UploadSiteResult.Error -> {
                            android.widget.Toast.makeText(
                                context.applicationContext,
                                "Upload failed for ${site.name}: ${result.message}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                AppLogger.i("MainViewModel", "Automatic upload completed for site: name='${site.name}', result=$result")
            } catch (e: Exception) {
                AppLogger.e("MainViewModel", "Site upload error: name='${site.name}'", e)
                setSiteUploadFailedAndRefresh(site)
            }
        }
    }
    
    sealed class FinalizeSiteResult {
        data class Success(val siteToUpload: SamplingSite) : FinalizeSiteResult()
        data class Error(val message: String) : FinalizeSiteResult()
    }
    
    suspend fun deleteSite(site: SamplingSite): DeleteSiteResult {
        AppLogger.i("MainViewModel", "Deleting site: name='${site.name}', id=${site.id}")
        // Get storage settings
        val settingsPreferences = SettingsPreferences(context)
        val folderUriString = settingsPreferences.getFolderUri()
        
        if (folderUriString.isEmpty()) {
            deleteFormCompletionsForSiteAndReload(site.name)
            return DeleteSiteResult.Success
        }
        
        val folderHelper = FolderStructureHelper(context)
        
        // Get the ongoing and deleted folders
        val ongoingFolder = try {
            folderHelper.getOngoingFolder(settingsPreferences)
        } catch (e: Exception) {
            AppLogger.e("MainViewModel", "Error accessing ongoing folder: ${e.message}", e)
            // Still delete from database
            database.samplingSiteDao().deleteSite(site)
            return DeleteSiteResult.Success
        }
        
        val deletedFolder = try {
            folderHelper.getDeletedFolder(settingsPreferences)
        } catch (e: Exception) {
            AppLogger.e("MainViewModel", "Error accessing deleted folder: ${e.message}", e)
            // Still delete from database
            database.samplingSiteDao().deleteSite(site)
            return DeleteSiteResult.Success
        }
        
        if (ongoingFolder == null || !ongoingFolder.exists() || !ongoingFolder.canRead()) {
            AppLogger.w("MainViewModel", "Ongoing folder not accessible")
            deleteFormCompletionsForSiteAndReload(site.name)
            return DeleteSiteResult.Success
        }
        if (deletedFolder == null || !deletedFolder.exists() || !deletedFolder.canWrite()) {
            AppLogger.w("MainViewModel", "Deleted folder not accessible")
            deleteFormCompletionsForSiteAndReload(site.name)
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
                            AppLogger.w("MainViewModel", "Could not move folder to deleted, but continuing with database deletion")
                        }
                    } else {
                        AppLogger.w("MainViewModel", "Could not find renamed folder, but continuing with database deletion")
                    }
                } else {
                    AppLogger.w("MainViewModel", "Could not rename folder before moving, but continuing with database deletion")
                }
            } else {
                // Move the folder to deleted
                val moveSuccess = moveFolder(siteFolder, deletedFolder)
                if (!moveSuccess) {
                    android.util.Log.w("MainViewModel", "Could not move folder to deleted, but continuing with database deletion")
                }
            }
        } else {
            AppLogger.w("MainViewModel", "Site folder '${site.name}' not found in ongoing folder")
        }
        
        // Update site metadata with deletion timestamp
        val formFileHelper = com.trec.trecollect.data.FormFileHelper(context)
        // Try to find site folder in ongoing or deleted folder to update metadata
        val siteFolderForMetadata = ongoingFolder.findFile(site.name) 
            ?: deletedFolder.findFile(site.name)
        
        if (siteFolderForMetadata != null && siteFolderForMetadata.exists()) {
            val existingMetadata = formFileHelper.loadSiteMetadata(site.name)
            if (existingMetadata != null) {
                val updatedMetadata = existingMetadata.copy(deletedAt = SiteMetadata.getCurrentTimestamp())
                formFileHelper.saveSiteMetadata(site.name, updatedMetadata)
            } else {
                // Create new metadata if it doesn't exist
                val metadata = SiteMetadata(
                    siteName = site.name,
                    createdAt = SiteMetadata.getCurrentTimestamp(),
                    deletedAt = SiteMetadata.getCurrentTimestamp()
                )
                formFileHelper.saveSiteMetadata(site.name, metadata)
            }
        }
        
        // Delete form completions for this site (using site name)
        try {
            database.formCompletionDao().deleteCompletionsForSiteByName(site.name)
        } catch (e: Exception) {
            AppLogger.w("MainViewModel", "Could not delete form completions: ${e.message}")
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
            AppLogger.e("MainViewModel", "Source folder does not exist or is not a directory")
            return false
        }
        
        if (!destinationFolder.exists() || !destinationFolder.canWrite()) {
            AppLogger.e("MainViewModel", "Destination folder does not exist or is not writable")
            return false
        }
        
        // Check if a folder with the same name already exists in destination
        val folderName = sourceFolder.name ?: return false
        val existingFolder = destinationFolder.findFile(folderName)
        if (existingFolder != null && existingFolder.exists()) {
            AppLogger.w("MainViewModel", "Folder '$folderName' already exists in deleted folder")
            // We could append a timestamp, but for now let's just return false
            return false
        }
        
        // Create the destination folder
        val newFolder = destinationFolder.createDirectory(folderName)
        if (newFolder == null || !newFolder.exists()) {
            AppLogger.e("MainViewModel", "Could not create folder '${sourceFolder.name}' in destination")
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
                AppLogger.w("MainViewModel", "Could not list files in source folder")
                return true // Empty folder, consider it success
            }
            
            for (file in files) {
                val fileName = file.name ?: continue // Skip files/folders without a name
                if (file.isDirectory) {
                    // Create subdirectory in destination
                    val newSubDir = destination.createDirectory(fileName)
                    if (newSubDir == null || !newSubDir.exists()) {
                        AppLogger.e("MainViewModel", "Could not create subdirectory '$fileName'")
                        return false
                    }
                    // Recursively copy subdirectory
                    if (!copyFolderRecursive(file, newSubDir)) {
                        return false
                    }
                } else {
                    // Copy file
                    if (!copyFile(file, destination)) {
                        AppLogger.e("MainViewModel", "Could not copy file '$fileName'")
                        return false
                    }
                }
            }
            return true
        } catch (e: Exception) {
            AppLogger.e("MainViewModel", "Error copying folder: ${e.message}", e)
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
                AppLogger.e("MainViewModel", "Could not create destination file '${sourceFile.name}'")
                return false
            }
            
            // Copy the file content using use blocks for automatic resource management
            context.contentResolver.openInputStream(sourceFile.uri)?.use { inputStream ->
                context.contentResolver.openOutputStream(destinationFile.uri)?.use { outputStream ->
                    inputStream.copyTo(outputStream)
                    return true
                } ?: run {
                    AppLogger.e("MainViewModel", "Could not open output stream for file '${sourceFile.name}'")
                    return false
                }
            } ?: run {
                AppLogger.e("MainViewModel", "Could not open input stream for file '${sourceFile.name}'")
                return false
            }
        } catch (e: Exception) {
            AppLogger.e("MainViewModel", "Error copying file '${sourceFile.name}': ${e.message}", e)
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
        
        try {
            updateSiteUploadStatusAndReload(site, UploadStatus.UPLOADING)
        } catch (e: Exception) {
            AppLogger.e("MainViewModel", "Error updating site upload status to UPLOADING: ${e.message}", e)
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
                setSiteUploadFailedAndRefresh(site)
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
                try {
                    updateSiteUploadStatusAndReload(site, UploadStatus.UPLOADED)
                    val formFileHelper = com.trec.trecollect.data.FormFileHelper(context)
                    val existingMeta = formFileHelper.loadSiteMetadata(site.name)
                    val metaWithUploaded = (existingMeta ?: SiteMetadata(
                        siteName = site.name,
                        createdAt = SiteMetadata.getCurrentTimestamp()
                    )).copy(uploadedAt = SiteMetadata.getCurrentTimestamp())
                    formFileHelper.saveSiteMetadata(site.name, metaWithUploaded)
                } catch (e: Exception) {
                    AppLogger.e("MainViewModel", "Error updating site upload status to UPLOADED: ${e.message}", e)
                }
                AppLogger.i("MainViewModel", "Site upload completed successfully: name='${site.name}', files=${uploadResult.uploadedCount}/${uploadResult.totalCount}")
                return UploadSiteResult.Success(uploadResult.uploadedCount, uploadResult.totalCount)
            } else {
                setSiteUploadFailedAndRefresh(site)
                AppLogger.e("MainViewModel", "Site upload failed: name='${site.name}', error='${uploadResult.errorMessage}'")
                return UploadSiteResult.Error(uploadResult.errorMessage ?: "Upload failed")
            }
        } catch (e: Exception) {
            setSiteUploadFailedAndRefresh(site)
            AppLogger.e("MainViewModel", "Site upload error: name='${site.name}'", e)
            return UploadSiteResult.Error("Upload error: ${e.message}")
        }
    }
    
    /**
     * For instrumented tests only: sets the finished sites list so tests can assert
     * that when upload success is simulated from background, the list updates.
     */
    @VisibleForTesting
    fun testOnlySetFinishedSites(sites: List<SamplingSite>) {
        _finishedSites.value = sites
    }

    /**
     * For instrumented tests only: simulates upload completing successfully from a background
     * scope (same as automatic upload after finalize). Used to verify that finishedSites
     * flow emits the site with UPLOADED so the checkbox updates.
     */
    @VisibleForTesting
    fun testOnlySimulateUploadSuccessFromBackground(site: SamplingSite) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            updateSiteUploadStatusAndReload(site, UploadStatus.UPLOADED)
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

