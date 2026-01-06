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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainViewModel(
    private val database: AppDatabase,
    private val context: Context
) : ViewModel() {
    val ongoingSites: Flow<List<SamplingSite>> = database.samplingSiteDao().getSitesByStatus(SiteStatus.ONGOING)
    val finishedSites: Flow<List<SamplingSite>> = database.samplingSiteDao().getSitesByStatus(SiteStatus.FINISHED)
    
    suspend fun createSite(name: String): CreateSiteResult {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return CreateSiteResult.Error("Site name cannot be empty")
        }
        
        // Check if site already exists in database
        val existingSites = database.samplingSiteDao().getAllSites().first()
        if (existingSites.any { it.name.equals(trimmedName, ignoreCase = true) }) {
            return CreateSiteResult.Error("A site with this name already exists")
        }
        
        // Check if folder already exists
        val settingsPreferences = SettingsPreferences(context)
        val folderUriString = settingsPreferences.getFolderUri()
        
        if (folderUriString.isEmpty()) {
            return CreateSiteResult.Error("Storage folder not configured. Please select a folder in Settings.")
        }
        
        val folderHelper = FolderStructureHelper(context)
        
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
            return CreateSiteResult.Error("Could not create site folder in TREC_logsheets/ongoing/")
        }
        
        // Create the site in database
        database.samplingSiteDao().insertSite(
            SamplingSite(name = siteName, status = SiteStatus.ONGOING)
        )
        
        return CreateSiteResult.Success
    }
    
    sealed class CreateSiteResult {
        object Success : CreateSiteResult()
        data class Error(val message: String) : CreateSiteResult()
    }
    
    fun renameSite(site: SamplingSite, newName: String) {
        if (newName.isNotBlank()) {
            viewModelScope.launch {
                database.samplingSiteDao().updateSite(
                    site.copy(name = newName.trim())
                )
            }
        }
    }
    
    fun finishSite(site: SamplingSite) {
        viewModelScope.launch {
            database.samplingSiteDao().updateSite(
                site.copy(status = SiteStatus.FINISHED)
            )
        }
    }
    
    fun deleteSite(site: SamplingSite) {
        viewModelScope.launch {
            database.samplingSiteDao().deleteSite(site)
            // Form completions will be deleted automatically due to CASCADE foreign key
        }
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

