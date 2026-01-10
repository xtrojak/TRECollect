package com.trec.customlogsheets.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SamplingSite data class.
 * Tests data class behavior, equality, and default values.
 */
class SamplingSiteTest {

    @Test
    fun `SamplingSite has correct default values`() {
        val site = SamplingSite(
            name = "Test Site",
            status = SiteStatus.ONGOING
        )

        assertEquals(0L, site.id)
        assertEquals("Test Site", site.name)
        assertEquals(SiteStatus.ONGOING, site.status)
        assertEquals(UploadStatus.NOT_UPLOADED, site.uploadStatus)
        assertTrue(site.createdAt > 0)
    }

    @Test
    fun `SamplingSite equality works correctly`() {
        val timestamp = System.currentTimeMillis()
        val site1 = SamplingSite(
            id = 1L,
            name = "Test Site",
            status = SiteStatus.ONGOING,
            uploadStatus = UploadStatus.NOT_UPLOADED,
            createdAt = timestamp
        )
        val site2 = SamplingSite(
            id = 1L,
            name = "Test Site",
            status = SiteStatus.ONGOING,
            uploadStatus = UploadStatus.NOT_UPLOADED,
            createdAt = timestamp
        )

        assertEquals(site1, site2)
        assertEquals(site1.hashCode(), site2.hashCode())
    }

    @Test
    fun `SamplingSite with different IDs are not equal`() {
        val timestamp = System.currentTimeMillis()
        val site1 = SamplingSite(
            id = 1L,
            name = "Test Site",
            status = SiteStatus.ONGOING,
            createdAt = timestamp
        )
        val site2 = SamplingSite(
            id = 2L,
            name = "Test Site",
            status = SiteStatus.ONGOING,
            createdAt = timestamp
        )

        assertNotEquals(site1, site2)
    }

    @Test
    fun `SamplingSite copy creates new instance with modified values`() {
        val original = SamplingSite(
            id = 1L,
            name = "Original Site",
            status = SiteStatus.ONGOING,
            uploadStatus = UploadStatus.NOT_UPLOADED,
            createdAt = 1000L
        )

        val updated = original.copy(
            status = SiteStatus.FINISHED,
            uploadStatus = UploadStatus.UPLOADED
        )

        assertEquals(1L, updated.id)
        assertEquals("Original Site", updated.name)
        assertEquals(SiteStatus.FINISHED, updated.status)
        assertEquals(UploadStatus.UPLOADED, updated.uploadStatus)
        assertEquals(1000L, updated.createdAt)

        // Original should be unchanged
        assertEquals(SiteStatus.ONGOING, original.status)
        assertEquals(UploadStatus.NOT_UPLOADED, original.uploadStatus)
    }

    @Test
    fun `SiteStatus enum has correct values`() {
        val values = SiteStatus.values()
        assertEquals(2, values.size)
        assertTrue(values.contains(SiteStatus.ONGOING))
        assertTrue(values.contains(SiteStatus.FINISHED))
    }

    @Test
    fun `UploadStatus enum has correct values`() {
        val values = UploadStatus.values()
        assertEquals(4, values.size)
        assertTrue(values.contains(UploadStatus.NOT_UPLOADED))
        assertTrue(values.contains(UploadStatus.UPLOADING))
        assertTrue(values.contains(UploadStatus.UPLOADED))
        assertTrue(values.contains(UploadStatus.UPLOAD_FAILED))
    }

    @Test
    fun `UploadStatus ordinals are sequential`() {
        assertEquals(0, UploadStatus.NOT_UPLOADED.ordinal)
        assertEquals(1, UploadStatus.UPLOADING.ordinal)
        assertEquals(2, UploadStatus.UPLOADED.ordinal)
        assertEquals(3, UploadStatus.UPLOAD_FAILED.ordinal)
    }

    @Test
    fun `SiteStatus ordinals are sequential`() {
        assertEquals(0, SiteStatus.ONGOING.ordinal)
        assertEquals(1, SiteStatus.FINISHED.ordinal)
    }

    @Test
    fun `SamplingSite with different names are not equal`() {
        val timestamp = System.currentTimeMillis()
        val site1 = SamplingSite(
            id = 1L,
            name = "Site 1",
            status = SiteStatus.ONGOING,
            createdAt = timestamp
        )
        val site2 = SamplingSite(
            id = 1L,
            name = "Site 2",
            status = SiteStatus.ONGOING,
            createdAt = timestamp
        )

        assertNotEquals(site1, site2)
    }

    @Test
    fun `SamplingSite with different status are not equal`() {
        val timestamp = System.currentTimeMillis()
        val site1 = SamplingSite(
            id = 1L,
            name = "Test Site",
            status = SiteStatus.ONGOING,
            createdAt = timestamp
        )
        val site2 = SamplingSite(
            id = 1L,
            name = "Test Site",
            status = SiteStatus.FINISHED,
            createdAt = timestamp
        )

        assertNotEquals(site1, site2)
    }

    @Test
    fun `SamplingSite with different uploadStatus are not equal`() {
        val timestamp = System.currentTimeMillis()
        val site1 = SamplingSite(
            id = 1L,
            name = "Test Site",
            status = SiteStatus.FINISHED,
            uploadStatus = UploadStatus.NOT_UPLOADED,
            createdAt = timestamp
        )
        val site2 = SamplingSite(
            id = 1L,
            name = "Test Site",
            status = SiteStatus.FINISHED,
            uploadStatus = UploadStatus.UPLOADED,
            createdAt = timestamp
        )

        assertNotEquals(site1, site2)
    }

    @Test
    fun `SamplingSite with different createdAt are not equal`() {
        val site1 = SamplingSite(
            id = 1L,
            name = "Test Site",
            status = SiteStatus.ONGOING,
            createdAt = 1000L
        )
        val site2 = SamplingSite(
            id = 1L,
            name = "Test Site",
            status = SiteStatus.ONGOING,
            createdAt = 2000L
        )

        assertNotEquals(site1, site2)
    }

    @Test
    fun `SamplingSite copy with all fields`() {
        val original = SamplingSite(
            id = 1L,
            name = "Original Site",
            status = SiteStatus.ONGOING,
            uploadStatus = UploadStatus.NOT_UPLOADED,
            createdAt = 1000L
        )

        val updated = original.copy(
            id = 2L,
            name = "Updated Site",
            status = SiteStatus.FINISHED,
            uploadStatus = UploadStatus.UPLOADED,
            createdAt = 2000L
        )

        assertEquals(2L, updated.id)
        assertEquals("Updated Site", updated.name)
        assertEquals(SiteStatus.FINISHED, updated.status)
        assertEquals(UploadStatus.UPLOADED, updated.uploadStatus)
        assertEquals(2000L, updated.createdAt)
    }

    @Test
    fun `SamplingSite with all upload statuses`() {
        val statuses = listOf(
            UploadStatus.NOT_UPLOADED,
            UploadStatus.UPLOADING,
            UploadStatus.UPLOADED,
            UploadStatus.UPLOAD_FAILED
        )

        statuses.forEach { uploadStatus ->
            val site = SamplingSite(
                name = "Test Site",
                status = SiteStatus.FINISHED,
                uploadStatus = uploadStatus
            )
            assertEquals(uploadStatus, site.uploadStatus)
        }
    }

    @Test
    fun `SamplingSite with all site statuses`() {
        val statuses = listOf(
            SiteStatus.ONGOING,
            SiteStatus.FINISHED
        )

        statuses.forEach { status ->
            val site = SamplingSite(
                name = "Test Site",
                status = status
            )
            assertEquals(status, site.status)
        }
    }

    @Test
    fun `SamplingSite createdAt is set automatically`() {
        val before = System.currentTimeMillis()
        val site = SamplingSite(
            name = "Test Site",
            status = SiteStatus.ONGOING
        )
        val after = System.currentTimeMillis()

        assertTrue("createdAt should be between before and after", 
            site.createdAt >= before && site.createdAt <= after)
    }

    @Test
    fun `SamplingSite with explicit createdAt`() {
        val explicitTime = 1234567890L
        val site = SamplingSite(
            name = "Test Site",
            status = SiteStatus.ONGOING,
            createdAt = explicitTime
        )

        assertEquals(explicitTime, site.createdAt)
    }

    @Test
    fun `SamplingSite hashCode is consistent`() {
        val site = SamplingSite(
            id = 1L,
            name = "Test Site",
            status = SiteStatus.ONGOING,
            uploadStatus = UploadStatus.NOT_UPLOADED,
            createdAt = 1000L
        )

        val hashCode1 = site.hashCode()
        val hashCode2 = site.hashCode()

        assertEquals(hashCode1, hashCode2)
    }

    @Test
    fun `SamplingSite with empty name`() {
        val site = SamplingSite(
            name = "",
            status = SiteStatus.ONGOING
        )

        assertEquals("", site.name)
        assertEquals(SiteStatus.ONGOING, site.status)
    }
    
    // Edge cases and error conditions
    
    @Test
    fun `SamplingSite with very long name`() {
        val longName = "A".repeat(1000)
        val site = SamplingSite(
            name = longName,
            status = SiteStatus.ONGOING
        )

        assertEquals(longName, site.name)
        assertEquals(1000, site.name.length)
    }
    
    @Test
    fun `SamplingSite with special characters in name`() {
        val specialName = "Site!@#$%^&*()_+-=[]{}|;':\",./<>?"
        val site = SamplingSite(
            name = specialName,
            status = SiteStatus.ONGOING
        )

        assertEquals(specialName, site.name)
    }
    
    @Test
    fun `SamplingSite with unicode characters in name`() {
        val unicodeName = "Site 测试 🚀 日本語"
        val site = SamplingSite(
            name = unicodeName,
            status = SiteStatus.ONGOING
        )

        assertEquals(unicodeName, site.name)
    }
    
    @Test
    fun `SamplingSite with whitespace-only name`() {
        val whitespaceName = "   \t\n   "
        val site = SamplingSite(
            name = whitespaceName,
            status = SiteStatus.ONGOING
        )

        assertEquals(whitespaceName, site.name)
    }
    
    @Test
    fun `SamplingSite with negative ID`() {
        val site = SamplingSite(
            id = -1L,
            name = "Test Site",
            status = SiteStatus.ONGOING
        )

        assertEquals(-1L, site.id)
    }
    
    @Test
    fun `SamplingSite with maximum long ID`() {
        val site = SamplingSite(
            id = Long.MAX_VALUE,
            name = "Test Site",
            status = SiteStatus.ONGOING
        )

        assertEquals(Long.MAX_VALUE, site.id)
    }
    
    @Test
    fun `SamplingSite with zero createdAt`() {
        val site = SamplingSite(
            name = "Test Site",
            status = SiteStatus.ONGOING,
            createdAt = 0L
        )

        assertEquals(0L, site.createdAt)
    }
    
    @Test
    fun `SamplingSite with negative createdAt`() {
        val site = SamplingSite(
            name = "Test Site",
            status = SiteStatus.ONGOING,
            createdAt = -1000L
        )

        assertEquals(-1000L, site.createdAt)
    }
    
    @Test
    fun `SamplingSite with very large createdAt`() {
        val largeTimestamp = Long.MAX_VALUE
        val site = SamplingSite(
            name = "Test Site",
            status = SiteStatus.ONGOING,
            createdAt = largeTimestamp
        )

        assertEquals(largeTimestamp, site.createdAt)
    }
    
    @Test
    fun `SamplingSite copy with null-like values preserves structure`() {
        val original = SamplingSite(
            id = 1L,
            name = "Original",
            status = SiteStatus.ONGOING,
            uploadStatus = UploadStatus.NOT_UPLOADED,
            createdAt = 1000L
        )

        // Copy with same values
        val copy = original.copy()

        assertEquals(original, copy)
        assertNotSame(original, copy) // Should be different instance
    }
    
    @Test
    fun `SamplingSite hashCode handles edge cases`() {
        val site1 = SamplingSite(name = "", status = SiteStatus.ONGOING)
        val site2 = SamplingSite(name = "", status = SiteStatus.ONGOING)
        
        // Sites with same values should have same hashCode
        assertEquals(site1.hashCode(), site2.hashCode())
    }
    
    @Test
    fun `SamplingSite equality with all status combinations`() {
        val baseSite = SamplingSite(
            id = 1L,
            name = "Test",
            status = SiteStatus.ONGOING,
            uploadStatus = UploadStatus.NOT_UPLOADED,
            createdAt = 1000L
        )
        
        // Test all status combinations
        SiteStatus.values().forEach { siteStatus ->
            UploadStatus.values().forEach { uploadStatus ->
                val testSite = baseSite.copy(
                    status = siteStatus,
                    uploadStatus = uploadStatus
                )
                assertEquals(siteStatus, testSite.status)
                assertEquals(uploadStatus, testSite.uploadStatus)
            }
        }
    }
    
    @Test
    fun `SamplingSite with newline characters in name`() {
        val nameWithNewlines = "Site\nWith\nNewlines"
        val site = SamplingSite(
            name = nameWithNewlines,
            status = SiteStatus.ONGOING
        )

        assertEquals(nameWithNewlines, site.name)
        assertTrue(site.name.contains("\n"))
    }
    
    @Test
    fun `SamplingSite with tab characters in name`() {
        val nameWithTabs = "Site\tWith\tTabs"
        val site = SamplingSite(
            name = nameWithTabs,
            status = SiteStatus.ONGOING
        )

        assertEquals(nameWithTabs, site.name)
        assertTrue(site.name.contains("\t"))
    }
}
