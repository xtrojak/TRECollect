package com.trec.customlogsheets.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trec.customlogsheets.data.AppDatabase
import com.trec.customlogsheets.data.FormCompletion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for FormCompletionDao.
 * Tests CRUD operations for FormCompletion entities.
 */
@RunWith(AndroidJUnit4::class)
class FormCompletionDaoTest {
    
    private lateinit var database: AppDatabase
    private lateinit var dao: com.trec.customlogsheets.data.FormCompletionDao
    
    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).build()
        dao = database.formCompletionDao()
    }
    
    @After
    fun closeDatabase() {
        database.close()
    }
    
    @Test
    fun insertCompletion_stores_completion() = runBlocking {
        val completion = FormCompletion(
            siteName = "Test Site",
            formId = "form1",
            completedAt = System.currentTimeMillis()
        )
        
        dao.insertCompletion(completion)
        
        val retrieved = dao.getCompletion("Test Site", "form1")
        assertNotNull(retrieved)
        assertEquals("Test Site", retrieved!!.siteName)
        assertEquals("form1", retrieved.formId)
    }
    
    @Test
    fun insertCompletion_with_onConflict_replace_updates_existing() = runBlocking {
        val completion1 = FormCompletion(
            siteName = "Test Site",
            formId = "form1",
            completedAt = 1000L
        )
        
        dao.insertCompletion(completion1)
        
        val completion2 = FormCompletion(
            siteName = "Test Site",
            formId = "form1",
            completedAt = 2000L
        )
        
        // Insert with same siteName and formId should replace
        dao.insertCompletion(completion2)
        
        val retrieved = dao.getCompletion("Test Site", "form1")
        assertNotNull(retrieved)
        assertEquals(2000L, retrieved!!.completedAt) // Should have updated timestamp
    }
    
    @Test
    fun getCompletion_returns_correct_completion() = runBlocking {
        val completion = FormCompletion(
            siteName = "Find Me",
            formId = "form2",
            completedAt = 1234567890L
        )
        
        dao.insertCompletion(completion)
        val retrieved = dao.getCompletion("Find Me", "form2")
        
        assertNotNull(retrieved)
        assertEquals("Find Me", retrieved!!.siteName)
        assertEquals("form2", retrieved.formId)
        assertEquals(1234567890L, retrieved.completedAt)
    }
    
    @Test
    fun getCompletion_returns_null_for_nonexistent() = runBlocking {
        val retrieved = dao.getCompletion("Nonexistent", "form1")
        assertNull(retrieved)
    }
    
    @Test
    fun getCompletionsForSite_returns_all_completions_for_site() = runBlocking {
        val completions = listOf(
            FormCompletion(siteName = "Site A", formId = "form1", completedAt = 1000L),
            FormCompletion(siteName = "Site A", formId = "form2", completedAt = 2000L),
            FormCompletion(siteName = "Site A", formId = "form3", completedAt = 3000L),
            FormCompletion(siteName = "Site B", formId = "form1", completedAt = 4000L)
        )
        
        completions.forEach { dao.insertCompletion(it) }
        
        val siteACompletions = dao.getCompletionsForSite("Site A").first()
        assertEquals(3, siteACompletions.size)
        assertTrue(siteACompletions.all { it.siteName == "Site A" })
    }
    
    @Test
    fun getCompletionsForSite_returns_empty_list_when_no_completions() = runBlocking {
        val completions = dao.getCompletionsForSite("Empty Site").first()
        assertTrue(completions.isEmpty())
    }
    
    @Test
    fun getCompletedFormIds_returns_only_form_ids() = runBlocking {
        val completions = listOf(
            FormCompletion(siteName = "Site A", formId = "form1", completedAt = 1000L),
            FormCompletion(siteName = "Site A", formId = "form2", completedAt = 2000L),
            FormCompletion(siteName = "Site A", formId = "form3", completedAt = 3000L)
        )
        
        completions.forEach { dao.insertCompletion(it) }
        
        val formIds = dao.getCompletedFormIds("Site A")
        assertEquals(3, formIds.size)
        assertTrue(formIds.contains("form1"))
        assertTrue(formIds.contains("form2"))
        assertTrue(formIds.contains("form3"))
    }
    
    @Test
    fun getCompletedFormIds_returns_empty_list_when_no_completions() = runBlocking {
        val formIds = dao.getCompletedFormIds("Empty Site")
        assertTrue(formIds.isEmpty())
    }
    
    @Test
    fun deleteCompletion_removes_completion() = runBlocking {
        val completion = FormCompletion(
            siteName = "To Delete",
            formId = "form1",
            completedAt = System.currentTimeMillis()
        )
        
        dao.insertCompletion(completion)
        val beforeDelete = dao.getCompletion("To Delete", "form1")
        assertNotNull(beforeDelete)
        
        dao.deleteCompletion(beforeDelete!!)
        
        val afterDelete = dao.getCompletion("To Delete", "form1")
        assertNull(afterDelete)
    }
    
    @Test
    fun deleteCompletionsForSiteByName_removes_all_completions_for_site() = runBlocking {
        val completions = listOf(
            FormCompletion(siteName = "Site A", formId = "form1", completedAt = 1000L),
            FormCompletion(siteName = "Site A", formId = "form2", completedAt = 2000L),
            FormCompletion(siteName = "Site B", formId = "form1", completedAt = 3000L)
        )
        
        completions.forEach { dao.insertCompletion(it) }
        
        dao.deleteCompletionsForSiteByName("Site A")
        
        val siteACompletions = dao.getCompletionsForSite("Site A").first()
        val siteBCompletions = dao.getCompletionsForSite("Site B").first()
        
        assertTrue(siteACompletions.isEmpty())
        assertEquals(1, siteBCompletions.size) // Site B should still have its completion
    }
    
    @Test
    fun unique_index_prevents_duplicate_siteName_formId() = runBlocking {
        // The unique index on (siteName, formId) should prevent duplicates
        // But with OnConflictStrategy.REPLACE, it will replace instead
        val completion1 = FormCompletion(
            siteName = "Unique Site",
            formId = "form1",
            completedAt = 1000L
        )
        
        val completion2 = FormCompletion(
            siteName = "Unique Site",
            formId = "form1",
            completedAt = 2000L
        )
        
        dao.insertCompletion(completion1)
        dao.insertCompletion(completion2) // Should replace, not create duplicate
        
        val allCompletions = dao.getCompletionsForSite("Unique Site").first()
        assertEquals(1, allCompletions.size) // Only one completion
        assertEquals(2000L, allCompletions[0].completedAt) // Should have updated timestamp
    }
    
    @Test
    fun same_formId_different_sites_allowed() = runBlocking {
        val completion1 = FormCompletion(
            siteName = "Site A",
            formId = "form1",
            completedAt = 1000L
        )
        
        val completion2 = FormCompletion(
            siteName = "Site B",
            formId = "form1", // Same formId, different site
            completedAt = 2000L
        )
        
        dao.insertCompletion(completion1)
        dao.insertCompletion(completion2)
        
        val siteA = dao.getCompletion("Site A", "form1")
        val siteB = dao.getCompletion("Site B", "form1")
        
        assertNotNull(siteA)
        assertNotNull(siteB)
        assertEquals("Site A", siteA!!.siteName)
        assertEquals("Site B", siteB!!.siteName)
    }
    
    @Test
    fun completedAt_defaults_to_current_time() = runBlocking {
        val beforeInsert = System.currentTimeMillis()
        val completion = FormCompletion(
            siteName = "Time Test",
            formId = "form1"
        )
        
        dao.insertCompletion(completion)
        val afterInsert = System.currentTimeMillis()
        
        val retrieved = dao.getCompletion("Time Test", "form1")!!
        assertTrue(retrieved.completedAt >= beforeInsert)
        assertTrue(retrieved.completedAt <= afterInsert)
    }
    
    @Test
    fun multiple_forms_per_site() = runBlocking {
        val forms = listOf("form1", "form2", "form3", "form4", "form5")
        
        forms.forEach { formId ->
            dao.insertCompletion(
                FormCompletion(
                    siteName = "Multi Form Site",
                    formId = formId,
                    completedAt = System.currentTimeMillis()
                )
            )
        }
        
        val completions = dao.getCompletionsForSite("Multi Form Site").first()
        assertEquals(5, completions.size)
        
        val formIds = dao.getCompletedFormIds("Multi Form Site")
        assertEquals(5, formIds.size)
        forms.forEach { assertTrue(formIds.contains(it)) }
    }
}
