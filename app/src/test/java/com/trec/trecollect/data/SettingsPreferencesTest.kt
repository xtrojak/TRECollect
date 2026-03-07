package com.trec.trecollect.data

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.util.UUID

/**
 * Unit tests for SettingsPreferences class.
 * Tests preference storage and retrieval with mocked SharedPreferences.
 */
class SettingsPreferencesTest {
    private lateinit var mockContext: Context
    private lateinit var mockSharedPreferences: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var settingsPreferences: SettingsPreferences

    @Before
    fun setUp() {
        mockContext = mock()
        mockSharedPreferences = mock()
        mockEditor = mock()
        
        whenever(mockContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE))
            .thenReturn(mockSharedPreferences)
        whenever(mockSharedPreferences.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putString(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putLong(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putBoolean(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.apply()).then {}
        
        settingsPreferences = SettingsPreferences(mockContext)
    }

    @Test
    fun `getSubmissionPath returns empty string by default`() {
        whenever(mockSharedPreferences.getString("submission_path", "")).thenReturn("")
        
        val result = settingsPreferences.getSubmissionPath()
        
        assertEquals("", result)
    }

    @Test
    fun `getSubmissionPath returns stored value`() {
        val expectedPath = "/storage/path"
        whenever(mockSharedPreferences.getString("submission_path", "")).thenReturn(expectedPath)
        
        val result = settingsPreferences.getSubmissionPath()
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `setSubmissionPath stores value`() {
        val path = "/storage/path"
        
        settingsPreferences.setSubmissionPath(path)
        
        verify(mockEditor).putString("submission_path", path)
        verify(mockEditor).apply()
    }

    @Test
    fun `getFolderUri returns empty string by default`() {
        whenever(mockSharedPreferences.getString("folder_uri", "")).thenReturn("")
        
        val result = settingsPreferences.getFolderUri()
        
        assertEquals("", result)
    }

    @Test
    fun `getFolderUri returns stored value`() {
        val expectedUri = "content://uri/path"
        whenever(mockSharedPreferences.getString("folder_uri", "")).thenReturn(expectedUri)
        
        val result = settingsPreferences.getFolderUri()
        
        assertEquals(expectedUri, result)
    }

    @Test
    fun `setFolderUri stores value`() {
        val uri = "content://uri/path"
        
        settingsPreferences.setFolderUri(uri)
        
        verify(mockEditor).putString("folder_uri", uri)
        verify(mockEditor).apply()
    }

    @Test
    fun `getSamplingTeam returns empty string by default`() {
        whenever(mockSharedPreferences.getString("sampling_team", ""))
            .thenReturn("")
        
        val result = settingsPreferences.getSamplingTeam()
        
        assertEquals("", result)
    }

    @Test
    fun `getSamplingTeam returns stored value`() {
        val team = "LSI"
        whenever(mockSharedPreferences.getString("sampling_team", ""))
            .thenReturn(team)
        
        val result = settingsPreferences.getSamplingTeam()
        
        assertEquals(team, result)
    }

    @Test
    fun `setSamplingTeam stores value`() {
        val team = "Custom Team"
        
        settingsPreferences.setSamplingTeam(team)
        
        verify(mockEditor).putString("sampling_team", team)
        verify(mockEditor).apply()
    }

    @Test
    fun `getMapExpiryDays returns default value`() {
        whenever(mockSharedPreferences.getLong("map_expiry_days", SettingsPreferences.DEFAULT_MAP_EXPIRY_DAYS))
            .thenReturn(SettingsPreferences.DEFAULT_MAP_EXPIRY_DAYS)
        
        val result = settingsPreferences.getMapExpiryDays()
        
        assertEquals(SettingsPreferences.DEFAULT_MAP_EXPIRY_DAYS, result)
    }

    @Test
    fun `getMapExpiryDays returns stored value`() {
        val days = 60L
        whenever(mockSharedPreferences.getLong("map_expiry_days", SettingsPreferences.DEFAULT_MAP_EXPIRY_DAYS))
            .thenReturn(days)
        
        val result = settingsPreferences.getMapExpiryDays()
        
        assertEquals(days, result)
    }

    @Test
    fun `setMapExpiryDays stores value`() {
        val days = 60L
        
        settingsPreferences.setMapExpiryDays(days)
        
        verify(mockEditor).putLong("map_expiry_days", days)
        verify(mockEditor).apply()
    }

    @Test
    fun `getAppUuid generates new UUID when not exists`() {
        whenever(mockSharedPreferences.getString("app_uuid", null)).thenReturn(null)
        whenever(mockEditor.putString(eq("app_uuid"), any())).thenReturn(mockEditor)
        val settingsPrefsRelease = SettingsPreferences(mockContext, isDevBuildOverride = { false })
        val result = settingsPrefsRelease.getAppUuid()
        
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
        try {
            UUID.fromString(result)
        } catch (e: IllegalArgumentException) {
            fail("Generated UUID is not valid: $result")
        }
        verify(mockEditor).putString("app_uuid", result)
        verify(mockEditor).apply()
    }

    @Test
    fun `getAppUuid returns existing UUID`() {
        val existingUuid = UUID.randomUUID().toString()
        whenever(mockSharedPreferences.getString("app_uuid", null)).thenReturn(existingUuid)
        val settingsPrefsRelease = SettingsPreferences(mockContext, isDevBuildOverride = { false })
        val result = settingsPrefsRelease.getAppUuid()
        
        assertEquals(existingUuid, result)
        verify(mockEditor, never()).putString(eq("app_uuid"), any())
    }

    @Test
    fun `getAppUuid returns dev-debug when dev build`() {
        whenever(mockSharedPreferences.getString("app_uuid", null)).thenReturn(null)
        val result = settingsPreferences.getAppUuid()
        assertEquals("dev-debug", result)
        verify(mockEditor, never()).putString(eq("app_uuid"), any())
    }

    @Test
    fun `isOwnCloudFolderVerified returns false by default`() {
        whenever(mockSharedPreferences.getBoolean("owncloud_folder_verified", false))
            .thenReturn(false)
        
        val result = settingsPreferences.isOwnCloudFolderVerified()
        
        assertFalse(result)
    }

    @Test
    fun `isOwnCloudFolderVerified returns stored value`() {
        whenever(mockSharedPreferences.getBoolean("owncloud_folder_verified", false))
            .thenReturn(true)
        
        val result = settingsPreferences.isOwnCloudFolderVerified()
        
        assertTrue(result)
    }

    @Test
    fun `setOwnCloudFolderVerified stores value`() {
        settingsPreferences.setOwnCloudFolderVerified(true)
        
        verify(mockEditor).putBoolean("owncloud_folder_verified", true)
        verify(mockEditor).apply()
    }

    @Test
    fun `setOwnCloudFolderVerified can set to false`() {
        settingsPreferences.setOwnCloudFolderVerified(false)
        
        verify(mockEditor).putBoolean("owncloud_folder_verified", false)
        verify(mockEditor).apply()
    }

    @Test
    fun `getSubmissionPath handles null return`() {
        whenever(mockSharedPreferences.getString("submission_path", "")).thenReturn(null)
        
        val result = settingsPreferences.getSubmissionPath()
        
        assertEquals("", result)
    }

    @Test
    fun `getFolderUri handles null return`() {
        whenever(mockSharedPreferences.getString("folder_uri", "")).thenReturn(null)
        
        val result = settingsPreferences.getFolderUri()
        
        assertEquals("", result)
    }

    @Test
    fun `getSamplingTeam handles null return`() {
        whenever(mockSharedPreferences.getString("sampling_team", ""))
            .thenReturn(null)
        
        val result = settingsPreferences.getSamplingTeam()
        
        assertEquals("", result)
    }
    
    @Test
    fun `isSamplingTeamSet returns false when empty`() {
        whenever(mockSharedPreferences.getString("sampling_team", ""))
            .thenReturn("")
        
        val result = settingsPreferences.isSamplingTeamSet()
        
        assertFalse(result)
    }
    
    @Test
    fun `isSamplingTeamSet returns true when set`() {
        whenever(mockSharedPreferences.getString("sampling_team", ""))
            .thenReturn("LSI")
        
        val result = settingsPreferences.isSamplingTeamSet()
        
        assertTrue(result)
    }
    
    @Test
    fun `getSamplingSubteam returns empty string by default`() {
        whenever(mockSharedPreferences.getString("sampling_subteam", ""))
            .thenReturn("")
        
        val result = settingsPreferences.getSamplingSubteam()
        
        assertEquals("", result)
    }
    
    @Test
    fun `getSamplingSubteam returns stored value`() {
        val subteam = "Soil"
        whenever(mockSharedPreferences.getString("sampling_subteam", ""))
            .thenReturn(subteam)
        
        val result = settingsPreferences.getSamplingSubteam()
        
        assertEquals(subteam, result)
    }
    
    @Test
    fun `setSamplingSubteam stores value`() {
        val subteam = "Soil"
        
        settingsPreferences.setSamplingSubteam(subteam)
        
        verify(mockEditor).putString("sampling_subteam", subteam)
        verify(mockEditor).apply()
    }
    
    @Test
    fun `isSamplingSubteamSet returns false for team without subteam`() {
        whenever(mockSharedPreferences.getString("sampling_team", ""))
            .thenReturn("LSI")
        whenever(mockSharedPreferences.getString("sampling_subteam", ""))
            .thenReturn("")
        
        val result = settingsPreferences.isSamplingSubteamSet()
        
        assertFalse(result) // All teams now require subteam
    }
    
    @Test
    fun `isSamplingSubteamSet returns true for team with subteam`() {
        whenever(mockSharedPreferences.getString("sampling_team", ""))
            .thenReturn("LSI")
        whenever(mockSharedPreferences.getString("sampling_subteam", ""))
            .thenReturn("Soil")
        
        val result = settingsPreferences.isSamplingSubteamSet()
        
        assertTrue(result)
    }
    
    @Test
    fun `isSamplingSubteamSet returns true for AML with subteam`() {
        whenever(mockSharedPreferences.getString("sampling_team", ""))
            .thenReturn("AML")
        whenever(mockSharedPreferences.getString("sampling_subteam", ""))
            .thenReturn("AML - placeholder")
        
        val result = settingsPreferences.isSamplingSubteamSet()
        
        assertTrue(result)
    }
}
