package com.trec.customlogsheets.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FormCompletionDao {
    @Query("SELECT * FROM form_completions WHERE siteId = :siteId")
    fun getCompletionsForSite(siteId: Long): Flow<List<FormCompletion>>
    
    @Query("SELECT * FROM form_completions WHERE siteId = :siteId AND formId = :formId LIMIT 1")
    suspend fun getCompletion(siteId: Long, formId: String): FormCompletion?
    
    @Query("SELECT formId FROM form_completions WHERE siteId = :siteId")
    suspend fun getCompletedFormIds(siteId: Long): List<String>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompletion(completion: FormCompletion)
    
    @Delete
    suspend fun deleteCompletion(completion: FormCompletion)
    
    @Query("DELETE FROM form_completions WHERE siteId = :siteId")
    suspend fun deleteCompletionsForSite(siteId: Long)
}

