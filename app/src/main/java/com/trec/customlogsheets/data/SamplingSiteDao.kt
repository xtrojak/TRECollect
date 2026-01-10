package com.trec.customlogsheets.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SamplingSiteDao {
    @Query("SELECT * FROM sampling_sites WHERE status = :status ORDER BY createdAt DESC")
    fun getSitesByStatus(status: SiteStatus): Flow<List<SamplingSite>>
    
    @Query("SELECT * FROM sampling_sites WHERE id = :id")
    suspend fun getSiteById(id: Long): SamplingSite?
    
    @Query("SELECT * FROM sampling_sites WHERE name = :name LIMIT 1")
    suspend fun getSiteByName(name: String): SamplingSite?
    
    @Query("SELECT * FROM sampling_sites ORDER BY createdAt DESC")
    fun getAllSites(): Flow<List<SamplingSite>>
    
    @Insert
    suspend fun insertSite(site: SamplingSite): Long
    
    @Update
    suspend fun updateSite(site: SamplingSite)
    
    @Delete
    suspend fun deleteSite(site: SamplingSite)
}

