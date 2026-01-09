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
}
