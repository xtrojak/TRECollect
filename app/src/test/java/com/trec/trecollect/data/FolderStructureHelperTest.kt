package com.trec.trecollect.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for FolderStructureHelper constants and utility logic.
 * Tests folder name constants and basic validation.
 */
class FolderStructureHelperTest {

    @Test
    fun `folder name constants are defined correctly`() {
        assertEquals("TRECollect_logsheets", FolderStructureHelper.PARENT_FOLDER_NAME)
        assertEquals("ongoing", FolderStructureHelper.ONGOING_FOLDER)
        assertEquals("finished", FolderStructureHelper.FINISHED_FOLDER)
        assertEquals("deleted", FolderStructureHelper.DELETED_FOLDER)
    }

    @Test
    fun `folder name constants are not empty`() {
        assertTrue(FolderStructureHelper.PARENT_FOLDER_NAME.isNotEmpty())
        assertTrue(FolderStructureHelper.ONGOING_FOLDER.isNotEmpty())
        assertTrue(FolderStructureHelper.FINISHED_FOLDER.isNotEmpty())
        assertTrue(FolderStructureHelper.DELETED_FOLDER.isNotEmpty())
    }

    @Test
    fun `folder name constants are different from each other`() {
        val folderNames = setOf(
            FolderStructureHelper.PARENT_FOLDER_NAME,
            FolderStructureHelper.ONGOING_FOLDER,
            FolderStructureHelper.FINISHED_FOLDER,
            FolderStructureHelper.DELETED_FOLDER
        )

        assertEquals(4, folderNames.size)
    }

    @Test
    fun `folder names do not contain path separators`() {
        assertFalse(FolderStructureHelper.PARENT_FOLDER_NAME.contains("/"))
        assertFalse(FolderStructureHelper.ONGOING_FOLDER.contains("/"))
        assertFalse(FolderStructureHelper.FINISHED_FOLDER.contains("/"))
        assertFalse(FolderStructureHelper.DELETED_FOLDER.contains("/"))
    }

    @Test
    fun `folder names are trimmed`() {
        assertEquals(
            FolderStructureHelper.PARENT_FOLDER_NAME,
            FolderStructureHelper.PARENT_FOLDER_NAME.trim()
        )
        assertEquals(
            FolderStructureHelper.ONGOING_FOLDER,
            FolderStructureHelper.ONGOING_FOLDER.trim()
        )
        assertEquals(
            FolderStructureHelper.FINISHED_FOLDER,
            FolderStructureHelper.FINISHED_FOLDER.trim()
        )
        assertEquals(
            FolderStructureHelper.DELETED_FOLDER,
            FolderStructureHelper.DELETED_FOLDER.trim()
        )
    }
}
