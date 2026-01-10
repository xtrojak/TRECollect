package com.trec.customlogsheets.ui

import android.content.Context
import com.trec.customlogsheets.data.AppDatabase
import com.trec.customlogsheets.data.SamplingSite
import com.trec.customlogsheets.data.SamplingSiteDao
import com.trec.customlogsheets.data.SiteStatus
import com.trec.customlogsheets.data.UploadStatus
import android.content.SharedPreferences
import com.trec.customlogsheets.data.SettingsPreferences
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

        assertTrue(result is MainViewModel.CreateSiteResult.Success)
        assertEquals(site, result.site)
    }

    @Test
    fun `CreateSiteResult Error contains message`() {
        val message = "Error message"
        val result = MainViewModel.CreateSiteResult.Error(message)

        assertTrue(result is MainViewModel.CreateSiteResult.Error)
        assertEquals(message, result.message)
    }

    @Test
    fun `UploadSiteResult Success contains counts`() {
        val result = MainViewModel.UploadSiteResult.Success(5, 5)

        assertTrue(result is MainViewModel.UploadSiteResult.Success)
        assertEquals(5, result.uploadedCount)
        assertEquals(5, result.totalCount)
    }

    @Test
    fun `UploadSiteResult Error contains message`() {
        val message = "Upload failed"
        val result = MainViewModel.UploadSiteResult.Error(message)

        assertTrue(result is MainViewModel.UploadSiteResult.Error)
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

        assertTrue(result is MainViewModel.CreateSiteResult.Error)
        assertEquals("", result.message)
    }
    
    @Test
    fun `CreateSiteResult Error with very long message`() {
        val longMessage = "A".repeat(1000)
        val result = MainViewModel.CreateSiteResult.Error(longMessage)

        assertTrue(result is MainViewModel.CreateSiteResult.Error)
        assertEquals(longMessage, result.message)
    }
    
    @Test
    fun `UploadSiteResult Success with zero counts`() {
        val result = MainViewModel.UploadSiteResult.Success(0, 0)

        assertTrue(result is MainViewModel.UploadSiteResult.Success)
        assertEquals(0, result.uploadedCount)
        assertEquals(0, result.totalCount)
    }
    
    @Test
    fun `UploadSiteResult Success with uploadedCount greater than totalCount`() {
        // Edge case: uploadedCount > totalCount (shouldn't happen but test it)
        val result = MainViewModel.UploadSiteResult.Success(10, 5)

        assertTrue(result is MainViewModel.UploadSiteResult.Success)
        assertEquals(10, result.uploadedCount)
        assertEquals(5, result.totalCount)
    }
    
    @Test
    fun `UploadSiteResult Error with null message`() {
        // Note: message is not nullable, but test empty string
        val result = MainViewModel.UploadSiteResult.Error("")

        assertTrue(result is MainViewModel.UploadSiteResult.Error)
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

        assertTrue(result is MainViewModel.CreateSiteResult.Success)
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

        assertTrue(result is MainViewModel.CreateSiteResult.Success)
        assertEquals(-1L, result.site.id)
    }
    
    @Test
    fun `CreateSiteResult Success with site having empty name`() {
        val site = SamplingSite(
            name = "",
            status = SiteStatus.ONGOING
        )
        val result = MainViewModel.CreateSiteResult.Success(site)

        assertTrue(result is MainViewModel.CreateSiteResult.Success)
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
