package com.trec.customlogsheets.ui

import android.text.InputFilter
import android.text.Spanned
import android.text.TextWatcher
import android.widget.EditText

/**
 * Helper class to handle input masking for time fields (mm:ss, HH:mm:ss)
 * Prevents deletion of colons and limits section lengths
 */
class MaskInputHelper(
    private val editText: EditText,
    private val mask: String
) {
    private var isUpdating = false
    
    init {
        // Set input filter to prevent colon deletion and limit total length
        // Section length limits are handled by TextWatcher formatting
        val existingFilters = editText.filters ?: emptyArray()
        editText.filters = existingFilters + arrayOf(
            LengthFilter(mask.length),
            ColonProtectionFilter(mask)
        )
        
        // Add text watcher to format input
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isUpdating) return
                
                val text = s?.toString() ?: ""
                if (text.isEmpty()) return
                
                // Check if formatting is needed:
                // 1. Wrong number of colons
                // 2. Section has too many digits
                val colonCount = text.count { it == ':' }
                val expectedColonCount = mask.count { it == ':' }
                val sections = parseMaskSections(mask)
                
                var needsFormatting = false
                if (colonCount != expectedColonCount) {
                    needsFormatting = true
                } else {
                    // Check if any section exceeds its limit
                    var sectionIndex = 0
                    var sectionStart = 0
                    for (i in mask.indices) {
                        if (mask[i] == ':') {
                            val sectionLength = sections.getOrNull(sectionIndex) ?: 0
                            val sectionDigits = text.substring(sectionStart, i).count { it.isDigit() }
                            if (sectionDigits > sectionLength) {
                                needsFormatting = true
                                break
                            }
                            sectionStart = i + 1
                            sectionIndex++
                        }
                    }
                    // Check last section
                    if (!needsFormatting && sectionStart < text.length) {
                        val sectionLength = sections.getOrNull(sectionIndex) ?: 0
                        val sectionDigits = text.substring(sectionStart).count { it.isDigit() }
                        if (sectionDigits > sectionLength) {
                            needsFormatting = true
                        }
                    }
                }
                
                if (needsFormatting) {
                    val formatted = formatText(text, mask)
                    if (formatted != text) {
                        isUpdating = true
                        val cursorPos = editText.selectionStart
                        editText.setText(formatted)
                        // Try to maintain cursor position
                        val newCursorPos = minOf(cursorPos, formatted.length)
                        editText.setSelection(newCursorPos)
                        isUpdating = false
                    }
                }
            }
        })
    }
    
    /**
     * Parse mask to identify sections (e.g., "mm:ss" -> [2, 2], "HH:mm:ss" -> [2, 2, 2])
     */
    private fun parseMaskSections(mask: String): List<Int> {
        val sections = mutableListOf<Int>()
        var currentSectionLength = 0
        
        for (char in mask) {
            if (char == ':') {
                if (currentSectionLength > 0) {
                    sections.add(currentSectionLength)
                    currentSectionLength = 0
                }
            } else {
                currentSectionLength++
            }
        }
        if (currentSectionLength > 0) {
            sections.add(currentSectionLength)
        }
        
        return sections
    }
    
    /**
     * Format input text according to mask, preserving digit positions relative to colons
     * and limiting each section to its maximum length
     */
    private fun formatText(text: String, mask: String): String {
        val sections = parseMaskSections(mask)
        val result = StringBuilder()
        var sectionIndex = 0
        
        // Parse input by sections (respecting colon positions)
        val inputParts = if (text.contains(':')) {
            text.split(':')
        } else {
            // No colons, parse sequentially by section lengths
            val allDigits = text.filter { it.isDigit() }
            val parts = mutableListOf<String>()
            var digitIdx = 0
            for (sectionLength in sections) {
                if (digitIdx < allDigits.length) {
                    val endIdx = minOf(digitIdx + sectionLength, allDigits.length)
                    parts.add(allDigits.substring(digitIdx, endIdx))
                    digitIdx = endIdx
                } else {
                    parts.add("")
                }
            }
            parts
        }
        
        // Build formatted string
        for (char in mask) {
            if (char == ':') {
                result.append(':')
                sectionIndex++
            } else {
                val sectionLength = sections.getOrNull(sectionIndex) ?: 0
                val inputSection = inputParts.getOrNull(sectionIndex) ?: ""
                val sectionDigits = inputSection.filter { it.isDigit() }
                
                // Calculate current position within this section
                val posInSection = if (result.contains(':')) {
                    val lastColon = result.lastIndexOf(':')
                    result.length - lastColon - 1
                } else {
                    result.length
                }
                
                if (posInSection < sectionDigits.length && posInSection < sectionLength) {
                    result.append(sectionDigits[posInSection])
                } else if (posInSection < sectionLength) {
                    result.append(' ')
                }
            }
        }
        
        return result.toString()
    }
    
    /**
     * InputFilter to limit total length to mask length
     */
    private class LengthFilter(private val maxLength: Int) : InputFilter {
        override fun filter(
            source: CharSequence?,
            start: Int,
            end: Int,
            dest: Spanned?,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            val currentLength = dest?.length ?: 0
            val newLength = currentLength - (dend - dstart) + (end - start)
            
            if (newLength > maxLength) {
                // Calculate how many characters we can accept
                val allowedLength = maxLength - (currentLength - (dend - dstart))
                if (allowedLength <= 0) {
                    return ""
                }
                return source?.subSequence(0, minOf(allowedLength, source.length))
            }
            
            return null // Accept the input
        }
    }
    
    /**
     * InputFilter to prevent deletion of colons
     */
    private class ColonProtectionFilter(private val mask: String) : InputFilter {
        override fun filter(
            source: CharSequence?,
            start: Int,
            end: Int,
            dest: Spanned?,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            // If deleting (source is empty or null), check if we're trying to delete a colon
            if (source.isNullOrEmpty()) {
                val destStr = dest?.toString() ?: ""
                // Check if deletion range includes a colon position
                for (i in dstart until dend) {
                    if (i < mask.length && mask[i] == ':') {
                        // Prevent deletion of colon - but allow deletion of spaces and digits
                        return ""
                    }
                }
                // Allow deletion of spaces and digits
                return null
            }
            
            // If inserting, prevent inserting colon at non-colon positions
            source?.forEachIndexed { index, char ->
                if (char == ':') {
                    val insertPos = dstart + index
                    if (insertPos >= mask.length || mask[insertPos] != ':') {
                        // Trying to insert colon at wrong position - remove colons from input
                        return source.filter { it != ':' }
                    }
                }
            }
            
            // Allow digits and spaces to be inserted
            return null // Accept the input
        }
    }
    
    /**
     * InputFilter to limit each section to its maximum length
     */
    private class SectionLengthFilter(
        private val mask: String,
        private val sections: List<Int>
    ) : InputFilter {
        override fun filter(
            source: CharSequence?,
            start: Int,
            end: Int,
            dest: Spanned?,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            if (source.isNullOrEmpty()) {
                return null // Deletion is handled by ColonProtectionFilter
            }
            
            val destStr = dest?.toString() ?: ""
            
            // Find which section the insertion is in
            val sectionInfo = findSectionForPosition(dstart, mask)
            if (sectionInfo == null) {
                // Not in a section (maybe at a colon position) - allow if it's a digit that will be formatted
                return null
            }
            
            val (sectionIndex, sectionStart, sectionLength) = sectionInfo
            
            // Count digits currently in this section
            var currentDigitCount = 0
            for (i in sectionStart until minOf(sectionStart + sectionLength, destStr.length)) {
                if (destStr[i].isDigit()) {
                    currentDigitCount++
                }
            }
            
            // Extract digits from source
            val sourceDigits = source.toString().filter { it.isDigit() }
            
            // Count how many digits we're replacing (including spaces that will be replaced)
            val replacingCount = dend - dstart
            val availableSlots = sectionLength - currentDigitCount + replacingCount
            
            // If we're trying to add more digits than the section allows
            if (sourceDigits.length > availableSlots && availableSlots > 0) {
                // Allow only as many digits as fit
                val allowedDigits = sourceDigits.substring(0, availableSlots)
                // Return the allowed digits plus any non-digit characters from source (like spaces)
                val nonDigits = source.toString().filter { !it.isDigit() && it != ':' }
                return allowedDigits + nonDigits
            }
            
            // Allow the input - TextWatcher will format it properly
            return null
        }
        
        private fun findSectionForPosition(position: Int, mask: String): Triple<Int, Int, Int>? {
            // Find which section this position belongs to by checking mask structure
            var sectionIndex = 0
            var sectionStart = 0
            
            for (i in mask.indices) {
                if (mask[i] == ':') {
                    // Check if position is before this colon (in current section)
                    if (position < i) {
                        val sectionLength = sections.getOrNull(sectionIndex) ?: return null
                        return Triple(sectionIndex, sectionStart, sectionLength)
                    }
                    // Move to next section
                    sectionStart = i + 1
                    sectionIndex++
                }
            }
            
            // Check if position is in the last section
            if (position >= sectionStart && position < mask.length) {
                val sectionLength = sections.getOrNull(sectionIndex) ?: return null
                return Triple(sectionIndex, sectionStart, sectionLength)
            }
            
            return null
        }
    }
}
