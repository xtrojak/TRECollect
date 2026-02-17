package com.trec.customlogsheets.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for FormFileHelper companion: filename parsing, sanitization, and generation.
 */
class FormFileHelperTest {

    // --- sanitizeSectionName ---

    @Test
    fun sanitizeSectionName_nullOrBlank_returnsDefault() {
        assertEquals("default", FormFileHelper.sanitizeSectionName(null))
        assertEquals("default", FormFileHelper.sanitizeSectionName(""))
        assertEquals("default", FormFileHelper.sanitizeSectionName("   "))
    }

    @Test
    fun sanitizeSectionName_spacesReplacedWithHyphens() {
        assertEquals("a-b-c", FormFileHelper.sanitizeSectionName("a b c"))
    }

    @Test
    fun sanitizeSectionName_specialCharsRemovedOrReplaced() {
        assertEquals("abc", FormFileHelper.sanitizeSectionName("a*b?c"))
        assertEquals("a_b", FormFileHelper.sanitizeSectionName("a/b"))
    }

    // --- generateFileName ---

    @Test
    fun generateFileName_basicPattern() {
        assertEquals("default_form1_0.xml", FormFileHelper.generateFileName(null, "form1", 0, null))
        assertEquals("section_form1_1.xml", FormFileHelper.generateFileName("section", "form1", 1, null))
    }

    @Test
    fun generateFileName_dynamicWithSubIndex() {
        assertEquals("default_form1_0_2.xml", FormFileHelper.generateFileName(null, "form1", 0, 2))
        assertEquals("my-section_form1_0_0.xml", FormFileHelper.generateFileName("my section", "form1", 0, 0))
    }

    // --- extractFormIdAndOrderAndSubIndex ---

    @Test
    fun extractFormIdAndOrderAndSubIndex_withoutKnownFormId_simpleSection() {
        assertEquals(
            Triple("form1", 0, null),
            FormFileHelper.extractFormIdAndOrderAndSubIndex("default_form1_0.xml", null)
        )
        assertEquals(
            Triple("form1", 1, null),
            FormFileHelper.extractFormIdAndOrderAndSubIndex("section_form1_1.xml", null)
        )
    }

    @Test
    fun extractFormIdAndOrderAndSubIndex_withoutKnownFormId_dynamic() {
        assertEquals(
            Triple("form1", 0, 2),
            FormFileHelper.extractFormIdAndOrderAndSubIndex("default_form1_0_2.xml", null)
        )
    }

    @Test
    fun extractFormIdAndOrderAndSubIndex_withKnownFormId_simple() {
        assertEquals(
            Triple("form1", 0, null),
            FormFileHelper.extractFormIdAndOrderAndSubIndex("default_form1_0.xml", "form1")
        )
        assertEquals(
            Triple("my_form", 0, null),
            FormFileHelper.extractFormIdAndOrderAndSubIndex("default_my_form_0.xml", "my_form")
        )
    }

    @Test
    fun extractFormIdAndOrderAndSubIndex_withKnownFormId_dynamic() {
        assertEquals(
            Triple("form1", 0, 2),
            FormFileHelper.extractFormIdAndOrderAndSubIndex("default_form1_0_2.xml", "form1")
        )
        assertEquals(
            Triple("horizontal_line", 1, 0),
            FormFileHelper.extractFormIdAndOrderAndSubIndex("section_horizontal_line_1_0.xml", "horizontal_line")
        )
    }

    @Test
    fun extractFormIdAndOrderAndSubIndex_knownFormIdMismatch_returnsNull() {
        assertNull(FormFileHelper.extractFormIdAndOrderAndSubIndex("default_form1_0.xml", "other_form"))
    }

    @Test
    fun extractFormIdAndOrderAndSubIndex_invalidFormat_returnsNull() {
        assertNull(FormFileHelper.extractFormIdAndOrderAndSubIndex("not-an-xml.txt", null))
        assertNull(FormFileHelper.extractFormIdAndOrderAndSubIndex("single.xml", null))
        assertNull(FormFileHelper.extractFormIdAndOrderAndSubIndex("no_digits.xml", null))
    }

    @Test
    fun extractFormIdAndOrderAndSubIndex_roundTrip_withGenerateFileName() {
        val section = "my-section"
        val formId = "test_form"
        val orderInSection = 2
        val subIndex: Int? = 1
        val fileName = FormFileHelper.generateFileName(section, formId, orderInSection, subIndex)
        val extracted = FormFileHelper.extractFormIdAndOrderAndSubIndex(fileName, formId)
        assertEquals(Triple(formId, orderInSection, subIndex), extracted)
    }
}
