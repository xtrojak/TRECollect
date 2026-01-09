package com.trec.customlogsheets.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Converters class.
 * Tests enum conversion logic for Room database TypeConverters.
 */
class ConvertersTest {
    private lateinit var converters: Converters

    @Before
    fun setUp() {
        converters = Converters()
    }

    @Test
    fun `fromSiteStatus converts ONGOING to 0`() {
        val result = converters.fromSiteStatus(SiteStatus.ONGOING)
        assertEquals(0, result)
    }

    @Test
    fun `fromSiteStatus converts FINISHED to 1`() {
        val result = converters.fromSiteStatus(SiteStatus.FINISHED)
        assertEquals(1, result)
    }

    @Test
    fun `toSiteStatus converts 0 to ONGOING`() {
        val result = converters.toSiteStatus(0)
        assertEquals(SiteStatus.ONGOING, result)
    }

    @Test
    fun `toSiteStatus converts 1 to FINISHED`() {
        val result = converters.toSiteStatus(1)
        assertEquals(SiteStatus.FINISHED, result)
    }

    @Test
    fun `toSiteStatus handles invalid ordinal by defaulting to ONGOING`() {
        val result = converters.toSiteStatus(-1)
        assertEquals(SiteStatus.ONGOING, result)
    }

    @Test
    fun `toSiteStatus handles out of range ordinal by defaulting to ONGOING`() {
        val result = converters.toSiteStatus(999)
        assertEquals(SiteStatus.ONGOING, result)
    }

    @Test
    fun `fromUploadStatus converts NOT_UPLOADED to 0`() {
        val result = converters.fromUploadStatus(UploadStatus.NOT_UPLOADED)
        assertEquals(0, result)
    }

    @Test
    fun `fromUploadStatus converts UPLOADING to 1`() {
        val result = converters.fromUploadStatus(UploadStatus.UPLOADING)
        assertEquals(1, result)
    }

    @Test
    fun `fromUploadStatus converts UPLOADED to 2`() {
        val result = converters.fromUploadStatus(UploadStatus.UPLOADED)
        assertEquals(2, result)
    }

    @Test
    fun `fromUploadStatus converts UPLOAD_FAILED to 3`() {
        val result = converters.fromUploadStatus(UploadStatus.UPLOAD_FAILED)
        assertEquals(3, result)
    }

    @Test
    fun `toUploadStatus converts 0 to NOT_UPLOADED`() {
        val result = converters.toUploadStatus(0)
        assertEquals(UploadStatus.NOT_UPLOADED, result)
    }

    @Test
    fun `toUploadStatus converts 1 to UPLOADING`() {
        val result = converters.toUploadStatus(1)
        assertEquals(UploadStatus.UPLOADING, result)
    }

    @Test
    fun `toUploadStatus converts 2 to UPLOADED`() {
        val result = converters.toUploadStatus(2)
        assertEquals(UploadStatus.UPLOADED, result)
    }

    @Test
    fun `toUploadStatus converts 3 to UPLOAD_FAILED`() {
        val result = converters.toUploadStatus(3)
        assertEquals(UploadStatus.UPLOAD_FAILED, result)
    }

    @Test
    fun `toUploadStatus handles invalid ordinal by defaulting to NOT_UPLOADED`() {
        val result = converters.toUploadStatus(-1)
        assertEquals(UploadStatus.NOT_UPLOADED, result)
    }

    @Test
    fun `toUploadStatus handles out of range ordinal by defaulting to NOT_UPLOADED`() {
        val result = converters.toUploadStatus(999)
        assertEquals(UploadStatus.NOT_UPLOADED, result)
    }

    @Test
    fun `round trip conversion for SiteStatus preserves value`() {
        val original = SiteStatus.FINISHED
        val ordinal = converters.fromSiteStatus(original)
        val converted = converters.toSiteStatus(ordinal)
        assertEquals(original, converted)
    }

    @Test
    fun `round trip conversion for UploadStatus preserves value`() {
        val original = UploadStatus.UPLOADED
        val ordinal = converters.fromUploadStatus(original)
        val converted = converters.toUploadStatus(ordinal)
        assertEquals(original, converted)
    }

    @Test
    fun `all SiteStatus values can be converted`() {
        SiteStatus.values().forEach { status ->
            val ordinal = converters.fromSiteStatus(status)
            val converted = converters.toSiteStatus(ordinal)
            assertEquals("Failed for $status", status, converted)
        }
    }

    @Test
    fun `all UploadStatus values can be converted`() {
        UploadStatus.values().forEach { status ->
            val ordinal = converters.fromUploadStatus(status)
            val converted = converters.toUploadStatus(ordinal)
            assertEquals("Failed for $status", status, converted)
        }
    }
}
