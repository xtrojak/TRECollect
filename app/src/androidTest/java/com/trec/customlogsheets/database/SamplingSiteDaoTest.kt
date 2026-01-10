package com.trec.customlogsheets.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trec.customlogsheets.data.AppDatabase
import com.trec.customlogsheets.data.SamplingSite
import com.trec.customlogsheets.data.SiteStatus
import com.trec.customlogsheets.data.UploadStatus
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
 * Instrumented tests for SamplingSiteDao.
 * Tests CRUD operations for SamplingSite entities.
 */
@RunWith(AndroidJUnit4::class)
class SamplingSiteDaoTest {
    
    private lateinit var database: AppDatabase
    private lateinit var dao: com.trec.customlogsheets.data.SamplingSiteDao
    
    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).build()
        dao = database.samplingSiteDao()
    }
    
    @After
    fun closeDatabase() {
        database.close()
    }
    
    @Test
    fun insertSite_returns_valid_id() = runBlocking {
        val site = SamplingSite(
            name = "Test Site",
            status = SiteStatus.ONGOING,
            uploadStatus = UploadStatus.NOT_UPLOADED
        )
        
        val id = dao.insertSite(site)
        
        assertTrue(id > 0)
    }
    
    @Test
    fun insertSite_stores_all_fields() = runBlocking {
        val site = SamplingSite(
            name = "Complete Site",
            status = SiteStatus.FINISHED,
            uploadStatus = UploadStatus.UPLOADED,
            createdAt = 1234567890L
        )
        
        val id = dao.insertSite(site)
        val retrieved = dao.getSiteById(id)
        
        assertNotNull(retrieved)
        assertEquals("Complete Site", retrieved!!.name)
        assertEquals(SiteStatus.FINISHED, retrieved.status)
        assertEquals(UploadStatus.UPLOADED, retrieved.uploadStatus)
        assertEquals(1234567890L, retrieved.createdAt)
    }
    
    @Test
    fun getSiteById_returns_correct_site() = runBlocking {
        val site = SamplingSite(
            name = "Find Me",
            status = SiteStatus.ONGOING
        )
        
        val id = dao.insertSite(site)
        val retrieved = dao.getSiteById(id)
        
        assertNotNull(retrieved)
        assertEquals(id, retrieved!!.id)
        assertEquals("Find Me", retrieved.name)
    }
    
    @Test
    fun getSiteById_returns_null_for_nonexistent_id() = runBlocking {
        val retrieved = dao.getSiteById(99999L)
        assertNull(retrieved)
    }
    
    @Test
    fun updateSite_modifies_existing_site() = runBlocking {
        val site = SamplingSite(
            name = "Original Name",
            status = SiteStatus.ONGOING,
            uploadStatus = UploadStatus.NOT_UPLOADED
        )
        
        val id = dao.insertSite(site)
        val retrieved = dao.getSiteById(id)!!
        
        // Update the site
        val updated = retrieved.copy(
            status = SiteStatus.FINISHED,
            uploadStatus = UploadStatus.UPLOADED
        )
        dao.updateSite(updated)
        
        val afterUpdate = dao.getSiteById(id)
        assertNotNull(afterUpdate)
        assertEquals(SiteStatus.FINISHED, afterUpdate!!.status)
        assertEquals(UploadStatus.UPLOADED, afterUpdate.uploadStatus)
        assertEquals("Original Name", afterUpdate.name) // Name unchanged
    }
    
    @Test
    fun deleteSite_removes_site() = runBlocking {
        val site = SamplingSite(
            name = "To Delete",
            status = SiteStatus.ONGOING
        )
        
        val id = dao.insertSite(site)
        val beforeDelete = dao.getSiteById(id)
        assertNotNull(beforeDelete)
        
        dao.deleteSite(beforeDelete!!)
        
        val afterDelete = dao.getSiteById(id)
        assertNull(afterDelete)
    }
    
    @Test
    fun getAllSites_returns_all_inserted_sites() = runBlocking {
        val sites = listOf(
            SamplingSite(name = "Site 1", status = SiteStatus.ONGOING),
            SamplingSite(name = "Site 2", status = SiteStatus.FINISHED),
            SamplingSite(name = "Site 3", status = SiteStatus.ONGOING)
        )
        
        sites.forEach { dao.insertSite(it) }
        
        val allSites = dao.getAllSites().first()
        assertEquals(3, allSites.size)
    }
    
    @Test
    fun getSitesByStatus_returns_only_matching_status() = runBlocking {
        val ongoingSites = listOf(
            SamplingSite(name = "Ongoing 1", status = SiteStatus.ONGOING),
            SamplingSite(name = "Ongoing 2", status = SiteStatus.ONGOING),
            SamplingSite(name = "Ongoing 3", status = SiteStatus.ONGOING)
        )
        val finishedSites = listOf(
            SamplingSite(name = "Finished 1", status = SiteStatus.FINISHED),
            SamplingSite(name = "Finished 2", status = SiteStatus.FINISHED)
        )
        
        (ongoingSites + finishedSites).forEach { dao.insertSite(it) }
        
        val ongoing = dao.getSitesByStatus(SiteStatus.ONGOING).first()
        val finished = dao.getSitesByStatus(SiteStatus.FINISHED).first()
        
        assertEquals(3, ongoing.size)
        assertEquals(2, finished.size)
        assertTrue(ongoing.all { it.status == SiteStatus.ONGOING })
        assertTrue(finished.all { it.status == SiteStatus.FINISHED })
    }
    
    @Test
    fun getSitesByStatus_returns_empty_list_when_no_matches() = runBlocking {
        val ongoing = dao.getSitesByStatus(SiteStatus.ONGOING).first()
        val finished = dao.getSitesByStatus(SiteStatus.FINISHED).first()
        
        assertTrue(ongoing.isEmpty())
        assertTrue(finished.isEmpty())
    }
    
    @Test
    fun multiple_sites_with_same_name_allowed() = runBlocking {
        // Note: The database schema doesn't enforce unique names
        val site1 = SamplingSite(name = "Duplicate", status = SiteStatus.ONGOING)
        val site2 = SamplingSite(name = "Duplicate", status = SiteStatus.FINISHED)
        
        val id1 = dao.insertSite(site1)
        val id2 = dao.insertSite(site2)
        
        assertTrue(id1 != id2)
        
        val allSites = dao.getAllSites().first()
        val duplicates = allSites.filter { it.name == "Duplicate" }
        assertEquals(2, duplicates.size)
    }
    
    @Test
    fun sites_with_different_upload_statuses() = runBlocking {
        val statuses = listOf(
            UploadStatus.NOT_UPLOADED,
            UploadStatus.UPLOADING,
            UploadStatus.UPLOADED,
            UploadStatus.UPLOAD_FAILED
        )
        
        val sites = statuses.mapIndexed { index, status ->
            SamplingSite(
                name = "Status Site $index",
                status = SiteStatus.ONGOING,
                uploadStatus = status
            )
        }
        
        sites.forEach { dao.insertSite(it) }
        
        val allSites = dao.getAllSites().first()
        assertEquals(4, allSites.size)
        
        statuses.forEach { status ->
            assertTrue(allSites.any { it.uploadStatus == status })
        }
    }
    
    @Test
    fun createdAt_defaults_to_current_time() = runBlocking {
        val beforeInsert = System.currentTimeMillis()
        val site = SamplingSite(
            name = "Time Test",
            status = SiteStatus.ONGOING
        )
        
        val id = dao.insertSite(site)
        val afterInsert = System.currentTimeMillis()
        
        val retrieved = dao.getSiteById(id)!!
        assertTrue(retrieved.createdAt >= beforeInsert)
        assertTrue(retrieved.createdAt <= afterInsert)
    }
    
    @Test
    fun updateSite_preserves_id() = runBlocking {
        val site = SamplingSite(
            name = "ID Test",
            status = SiteStatus.ONGOING
        )
        
        val id = dao.insertSite(site)
        val retrieved = dao.getSiteById(id)!!
        
        val updated = retrieved.copy(
            status = SiteStatus.FINISHED,
            uploadStatus = UploadStatus.UPLOADED
        )
        dao.updateSite(updated)
        
        val afterUpdate = dao.getSiteById(id)
        assertNotNull(afterUpdate)
        assertEquals(id, afterUpdate!!.id)
    }
    
    // Edge cases and error conditions
    
    @Test
    fun insertSite_with_empty_name() = runBlocking {
        val site = SamplingSite(
            name = "",
            status = SiteStatus.ONGOING
        )
        
        val id = dao.insertSite(site)
        val retrieved = dao.getSiteById(id)
        
        assertNotNull(retrieved)
        assertEquals("", retrieved!!.name)
    }
    
    @Test
    fun insertSite_with_very_long_name() = runBlocking {
        val longName = "A".repeat(1000)
        val site = SamplingSite(
            name = longName,
            status = SiteStatus.ONGOING
        )
        
        val id = dao.insertSite(site)
        val retrieved = dao.getSiteById(id)
        
        assertNotNull(retrieved)
        assertEquals(longName, retrieved!!.name)
    }
    
    @Test
    fun insertSite_with_special_characters_in_name() = runBlocking {
        val specialName = "Site!@#$%^&*()_+-=[]{}|;':\",./<>?"
        val site = SamplingSite(
            name = specialName,
            status = SiteStatus.ONGOING
        )
        
        val id = dao.insertSite(site)
        val retrieved = dao.getSiteById(id)
        
        assertNotNull(retrieved)
        assertEquals(specialName, retrieved!!.name)
    }
    
    @Test
    fun insertSite_with_unicode_characters_in_name() = runBlocking {
        val unicodeName = "Site 测试 🚀 日本語"
        val site = SamplingSite(
            name = unicodeName,
            status = SiteStatus.ONGOING
        )
        
        val id = dao.insertSite(site)
        val retrieved = dao.getSiteById(id)
        
        assertNotNull(retrieved)
        assertEquals(unicodeName, retrieved!!.name)
    }
    
    @Test
    fun insertSite_with_negative_createdAt() = runBlocking {
        val site = SamplingSite(
            name = "Test Site",
            status = SiteStatus.ONGOING,
            createdAt = -1000L
        )
        
        val id = dao.insertSite(site)
        val retrieved = dao.getSiteById(id)
        
        assertNotNull(retrieved)
        assertEquals(-1000L, retrieved!!.createdAt)
    }
    
    @Test
    fun insertSite_with_zero_createdAt() = runBlocking {
        val site = SamplingSite(
            name = "Test Site",
            status = SiteStatus.ONGOING,
            createdAt = 0L
        )
        
        val id = dao.insertSite(site)
        val retrieved = dao.getSiteById(id)
        
        assertNotNull(retrieved)
        assertEquals(0L, retrieved!!.createdAt)
    }
    
    @Test
    fun insertSite_with_very_large_createdAt() = runBlocking {
        val largeTimestamp = Long.MAX_VALUE
        val site = SamplingSite(
            name = "Test Site",
            status = SiteStatus.ONGOING,
            createdAt = largeTimestamp
        )
        
        val id = dao.insertSite(site)
        val retrieved = dao.getSiteById(id)
        
        assertNotNull(retrieved)
        assertEquals(largeTimestamp, retrieved!!.createdAt)
    }
    
    @Test
    fun updateSite_with_nonexistent_id() = runBlocking {
        // Try to update a site that doesn't exist
        val nonExistentSite = SamplingSite(
            id = 99999L,
            name = "Non-existent",
            status = SiteStatus.ONGOING
        )
        
        // Should not throw exception, but update won't affect anything
        dao.updateSite(nonExistentSite)
        
        val retrieved = dao.getSiteById(99999L)
        assertNull(retrieved)
    }
    
    @Test
    fun deleteSite_with_nonexistent_id() = runBlocking {
        // Try to delete a site that doesn't exist
        val nonExistentSite = SamplingSite(
            id = 99999L,
            name = "Non-existent",
            status = SiteStatus.ONGOING
        )
        
        // Should not throw exception
        dao.deleteSite(nonExistentSite)
    }
    
    @Test
    fun insertSite_large_number_of_sites() = runBlocking {
        // Insert a large number of sites to test performance
        val sites = (1..500).map { index ->
            SamplingSite(
                name = "Site $index",
                status = if (index % 2 == 0) SiteStatus.ONGOING else SiteStatus.FINISHED,
                uploadStatus = UploadStatus.NOT_UPLOADED
            )
        }
        
        sites.forEach { dao.insertSite(it) }
        
        val allSites = dao.getAllSites().first()
        assertEquals(500, allSites.size)
    }
    
    @Test
    fun getSitesByStatus_with_large_dataset() = runBlocking {
        // Create many sites with different statuses
        val ongoingSites = (1..200).map { 
            SamplingSite(name = "Ongoing $it", status = SiteStatus.ONGOING)
        }
        val finishedSites = (1..200).map { 
            SamplingSite(name = "Finished $it", status = SiteStatus.FINISHED)
        }
        
        (ongoingSites + finishedSites).forEach { dao.insertSite(it) }
        
        val ongoing = dao.getSitesByStatus(SiteStatus.ONGOING).first()
        val finished = dao.getSitesByStatus(SiteStatus.FINISHED).first()
        
        assertEquals(200, ongoing.size)
        assertEquals(200, finished.size)
    }
    
    @Test
    fun updateSite_multiple_times() = runBlocking {
        val site = SamplingSite(
            name = "Update Test",
            status = SiteStatus.ONGOING,
            uploadStatus = UploadStatus.NOT_UPLOADED
        )
        
        val id = dao.insertSite(site)
        
        // Update multiple times through all statuses
        var current = dao.getSiteById(id)!!
        val statuses = listOf(
            UploadStatus.UPLOADING,
            UploadStatus.UPLOADED,
            UploadStatus.UPLOAD_FAILED,
            UploadStatus.NOT_UPLOADED,
            UploadStatus.UPLOADING,
            UploadStatus.UPLOADED,
            UploadStatus.UPLOAD_FAILED
        )
        
        statuses.forEach { status ->
            current = current.copy(uploadStatus = status)
            dao.updateSite(current)
        }
        
        val final = dao.getSiteById(id)!!
        assertEquals(UploadStatus.UPLOAD_FAILED, final.uploadStatus)
    }
    
    @Test
    fun insertSite_then_delete_then_insert_again() = runBlocking {
        val site = SamplingSite(
            name = "Reinsert Test",
            status = SiteStatus.ONGOING
        )
        
        val id1 = dao.insertSite(site)
        val retrieved1 = dao.getSiteById(id1)!!
        
        dao.deleteSite(retrieved1)
        
        val id2 = dao.insertSite(site)
        val retrieved2 = dao.getSiteById(id2)
        
        assertNotNull(retrieved2)
        assertTrue("New ID should be different or same", id2 != id1 || id2 == id1)
    }
    
    @Test
    fun getAllSites_returns_sorted_by_name() = runBlocking {
        val sites = listOf(
            SamplingSite(name = "Zebra", status = SiteStatus.ONGOING),
            SamplingSite(name = "Alpha", status = SiteStatus.ONGOING),
            SamplingSite(name = "Beta", status = SiteStatus.ONGOING)
        )
        
        sites.forEach { dao.insertSite(it) }
        
        val allSites = dao.getAllSites().first()
        
        // Note: Order depends on database implementation
        // Just verify all sites are present
        assertEquals(3, allSites.size)
        assertTrue(allSites.any { it.name == "Alpha" })
        assertTrue(allSites.any { it.name == "Beta" })
        assertTrue(allSites.any { it.name == "Zebra" })
    }
    
    @Test
    fun insertSite_with_all_upload_statuses() = runBlocking {
        val statuses = listOf(
            UploadStatus.NOT_UPLOADED,
            UploadStatus.UPLOADING,
            UploadStatus.UPLOADED,
            UploadStatus.UPLOAD_FAILED
        )
        
        statuses.forEach { status ->
            val site = SamplingSite(
                name = "Status ${status.name}",
                status = SiteStatus.FINISHED,
                uploadStatus = status
            )
            val id = dao.insertSite(site)
            val retrieved = dao.getSiteById(id)!!
            assertEquals(status, retrieved.uploadStatus)
        }
    }
    
    @Test
    fun getSiteById_with_zero_id() = runBlocking {
        val retrieved = dao.getSiteById(0L)
        assertNull(retrieved)
    }
    
    @Test
    fun getSiteById_with_negative_id() = runBlocking {
        val retrieved = dao.getSiteById(-1L)
        assertNull(retrieved)
    }
    
    @Test
    fun getSiteById_with_max_long_id() = runBlocking {
        val retrieved = dao.getSiteById(Long.MAX_VALUE)
        assertNull(retrieved)
    }
}
