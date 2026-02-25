package com.trec.trecollect.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FormCompletionDao {
    @Query("SELECT * FROM form_completions WHERE siteName = :siteName")
    fun getCompletionsForSite(siteName: String): Flow<List<FormCompletion>>
    
    @Query("SELECT * FROM form_completions WHERE siteName = :siteName AND formId = :formId LIMIT 1")
    suspend fun getCompletion(siteName: String, formId: String): FormCompletion?
    
    @Query("SELECT formId FROM form_completions WHERE siteName = :siteName")
    suspend fun getCompletedFormIds(siteName: String): List<String>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompletion(completion: FormCompletion)
    
    @Delete
    suspend fun deleteCompletion(completion: FormCompletion)
    
    @Query("DELETE FROM form_completions WHERE siteName = :siteName")
    suspend fun deleteCompletionsForSiteByName(siteName: String)
}

