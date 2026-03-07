package com.trec.trecollect.ui

import android.content.Context
import com.trec.trecollect.data.AppDatabase
import com.trec.trecollect.data.SamplingSite
import com.trec.trecollect.data.SamplingSiteDao
import com.trec.trecollect.data.SiteStatus
import com.trec.trecollect.data.UploadStatus
import android.content.SharedPreferences
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Unit tests for MainViewModel.
 * Tests business logic with mocked dependencies.
 * Note: Full ViewModel testing requires complex Android mocking, so we focus on testable business logic.
 */
class MainViewModelTest {
    private lateinit var mockDatabase: AppDatabase
    private lateinit var mockDao: SamplingSiteDao
    private lateinit var mockContext: Context
    private lateinit var mockSharedPreferences: SharedPreferences
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        mockDatabase = mock()
        mockDao = mock()
        mockContext = mock()
        mockSharedPreferences = mock()
        
        whenever(mockDatabase.samplingSiteDao()).thenReturn(mockDao)
        whenever(mockDao.getAllSites()).thenReturn(flowOf(emptyList()))
        
        // Mock SharedPreferences to prevent initialization errors
        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(mockSharedPreferences)
        whenever(mockSharedPreferences.getString(any(), any())).thenReturn("")
        
        viewModel = MainViewModel(mockDatabase, mockContext)
    }

    @Test
    fun `CreateSiteResult Success contains site`() {
        val site = SamplingSite(
            id = 1L,
            name = "Test Site",
            status = SiteStatus.ONGOING,
            uploadStatus = UploadStatus.NOT_UPLOADED
        )
        val result = MainViewModel.CreateSiteResult.Success(site)

        assertEquals(site, result.site)
    }

    @Test
    fun `CreateSiteResult Error contains message`() {
        val message = "Error message"
        val result = MainViewModel.CreateSiteResult.Error(message)

        assertEquals(message, result.message)
    }

    @Test
    fun `UploadSiteResult Success contains counts`() {
        val result = MainViewModel.UploadSiteResult.Success(5, 5)

        assertEquals(5, result.uploadedCount)
        assertEquals(5, result.totalCount)
    }

    @Test
    fun `UploadSiteResult Error contains message`() {
        val message = "Upload failed"
        val result = MainViewModel.UploadSiteResult.Error(message)

        assertEquals(message, result.message)
    }

    @Test
    fun `ongoingSites StateFlow is accessible`() {
        val flow = viewModel.ongoingSites

        assertNotNull(flow)
    }

    @Test
    fun `finishedSites StateFlow is accessible`() {
        val flow = viewModel.finishedSites

        assertNotNull(flow)
    }
    
    // Edge cases and error conditions
    
    @Test
    fun `CreateSiteResult Error with empty message`() {
        val result = MainViewModel.CreateSiteResult.Error("")

        assertEquals("", result.message)
    }
    
    @Test
    fun `CreateSiteResult Error with very long message`() {
        val longMessage = "A".repeat(1000)
        val result = MainViewModel.CreateSiteResult.Error(longMessage)

        assertEquals(longMessage, result.message)
    }
    
    @Test
    fun `UploadSiteResult Success with zero counts`() {
        val result = MainViewModel.UploadSiteResult.Success(0, 0)

        assertEquals(0, result.uploadedCount)
        assertEquals(0, result.totalCount)
    }
    
    @Test
    fun `UploadSiteResult Success with uploadedCount greater than totalCount`() {
        // Edge case: uploadedCount > totalCount (shouldn't happen but test it)
        val result = MainViewModel.UploadSiteResult.Success(10, 5)

        assertEquals(10, result.uploadedCount)
        assertEquals(5, result.totalCount)
    }
    
    @Test
    fun `UploadSiteResult Error with null message`() {
        // Note: message is not nullable, but test empty string
        val result = MainViewModel.UploadSiteResult.Error("")

        assertEquals("", result.message)
    }
    
    @Test
    fun `CreateSiteResult Success with site having zero ID`() {
        val site = SamplingSite(
            id = 0L,
            name = "Test Site",
            status = SiteStatus.ONGOING
        )
        val result = MainViewModel.CreateSiteResult.Success(site)

        assertEquals(0L, result.site.id)
    }
    
    @Test
    fun `CreateSiteResult Success with site having negative ID`() {
        val site = SamplingSite(
            id = -1L,
            name = "Test Site",
            status = SiteStatus.ONGOING
        )
        val result = MainViewModel.CreateSiteResult.Success(site)

        assertEquals(-1L, result.site.id)
    }
    
    @Test
    fun `CreateSiteResult Success with site having empty name`() {
        val site = SamplingSite(
            name = "",
            status = SiteStatus.ONGOING
        )
        val result = MainViewModel.CreateSiteResult.Success(site)

        assertEquals("", result.site.name)
    }
    
    @Test
    fun `StateFlows are initialized as empty lists`() {
        val ongoing = viewModel.ongoingSites.value
        val finished = viewModel.finishedSites.value

        assertNotNull(ongoing)
        assertNotNull(finished)
        assertTrue("Ongoing sites should be empty initially", ongoing.isEmpty())
        assertTrue("Finished sites should be empty initially", finished.isEmpty())
    }
    
    @Test
    fun `StateFlows are not null`() {
        assertNotNull(viewModel.ongoingSites)
        assertNotNull(viewModel.finishedSites)
    }
}
