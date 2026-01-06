package com.trec.customlogsheets.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trec.customlogsheets.data.AppDatabase
import com.trec.customlogsheets.data.SamplingSite
import com.trec.customlogsheets.data.SiteStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class MainViewModel(private val database: AppDatabase) : ViewModel() {
    val ongoingSites: Flow<List<SamplingSite>> = database.samplingSiteDao().getSitesByStatus(SiteStatus.ONGOING)
    val finishedSites: Flow<List<SamplingSite>> = database.samplingSiteDao().getSitesByStatus(SiteStatus.FINISHED)
    
    fun createSite(name: String) {
        if (name.isNotBlank()) {
            viewModelScope.launch {
                database.samplingSiteDao().insertSite(
                    SamplingSite(name = name.trim(), status = SiteStatus.ONGOING)
                )
            }
        }
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

class MainViewModelFactory(private val database: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(database) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

