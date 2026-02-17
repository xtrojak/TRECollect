package com.trec.customlogsheets.data

import android.content.Context
import android.util.Xml
import androidx.documentfile.provider.DocumentFile
import com.trec.customlogsheets.util.AppLogger
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream

/**
 * Helper class for saving and loading form XML files
 */
class FormFileHelper(private val context: Context) {
    
    companion object {
        /**
         * Sanitizes a section name for use in filenames.
         * Replaces spaces with hyphens and removes/sanitizes special characters.
         * Empty or null section names are converted to "default".
         *
         * @param sectionName The section name to sanitize
         * @return Sanitized section name safe for use in filenames
         */
        fun sanitizeSectionName(sectionName: String?): String {
            if (sectionName.isNullOrBlank()) {
                return "default"
            }

            // Replace spaces with hyphens (underscores are used as field separators in filename parsing)
            var sanitized = sectionName.replace(" ", "-")
            
            // Remove or replace special characters that are problematic in filenames
            // Characters to remove: / \ : * ? " < > |
            // Replace with underscore: / \ : | (common path/command separators)
            sanitized = sanitized
                .replace("/", "_")
                .replace("\\", "_")
                .replace(":", "_")
                .replace("|", "_")
                .replace("*", "")
                .replace("?", "")
                .replace("\"", "")
                .replace("<", "")
                .replace(">", "")
            
            // Remove leading/trailing underscores and dots (problematic in filenames)
            sanitized = sanitized.trim('_', '.', '-')
            
            // If empty after sanitization, use "default"
            if (sanitized.isEmpty()) {
                return "default"
            }
            
            return sanitized
        }
        
        /**
         * Generates a filename for a form based on section name, form ID, and order in section.
         * Pattern: ${sectionName}_${formId}_${orderInSection}.xml
         * For dynamic forms: ${sectionName}_${formId}_${orderInSection}_${subIndex}.xml
         * 
         * @param sectionName The section name (will be sanitized)
         * @param formId The form ID
         * @param orderInSection The 0-based index of the form within its section
         * @param subIndex The 0-based sub-index for dynamic forms (null for non-dynamic forms)
         * @return The generated filename
         */
        fun generateFileName(sectionName: String?, formId: String, orderInSection: Int, subIndex: Int? = null): String {
            val sanitizedSection = sanitizeSectionName(sectionName)
            return if (subIndex != null) {
                "${sanitizedSection}_${formId}_${orderInSection}_${subIndex}.xml"
            } else {
                "${sanitizedSection}_${formId}_${orderInSection}.xml"
            }
        }
        
        /**
         * Extracts form ID, orderInSection, and subIndex from a filename.
         * Pattern: ${sectionName}_${formId}_${orderInSection}.xml or ${sectionName}_${formId}_${orderInSection}_${subIndex}.xml
         *
         * When [knownFormId] is provided, the filename is split at "_${knownFormId}_" so that formIds
         * containing underscores (e.g. "horizontal_line") are parsed correctly. When null, parsing
         * assumes formId is a single segment (no underscores).
         *
         * @param fileName The filename to parse
         * @param knownFormId Optional: when the formId is already known (e.g. from XML), pass it to parse correctly when formId contains underscores
         * @return Triple of (formId, orderInSection, subIndex) or null if parsing fails
         */
        fun extractFormIdAndOrderAndSubIndex(fileName: String, knownFormId: String? = null): Triple<String, Int, Int?>? {
            val nameWithoutExt = fileName.removeSuffix(".xml")
            val parts = nameWithoutExt.split("_")

            if (knownFormId != null) {
                val suffix = "_${knownFormId}_"
                val idx = nameWithoutExt.indexOf(suffix)
                if (idx < 0) return null
                val afterFormId = nameWithoutExt.substring(idx + suffix.length)
                val afterParts = afterFormId.split("_")
                return when {
                    afterParts.size == 1 && afterParts[0].all { it.isDigit() } ->
                        Triple(knownFormId, afterParts[0].toInt(), null)
                    afterParts.size == 2 && afterParts[0].all { it.isDigit() } && afterParts[1].all { it.isDigit() } ->
                        Triple(knownFormId, afterParts[0].toInt(), afterParts[1].toInt())
                    else -> null
                }
            }

            if (parts.size < 2) return null
            val last = parts.last()
            if (!last.all { it.isDigit() }) return null

            return if (parts.size >= 3 && parts[parts.size - 2].all { it.isDigit() }) {
                val orderInSection = parts[parts.size - 2].toInt()
                val subIndex = parts.last().toInt()
                val formId = parts[parts.size - 3]
                Triple(formId, orderInSection, subIndex)
            } else {
                val orderInSection = parts.last().toInt()
                val formId = parts[parts.size - 2]
                Triple(formId, orderInSection, null)
            }
        }
        
        /**
         * Gets the order index of a form within its section.
         * 
         * @param context The context
         * @param formId The form ID
         * @return The 0-based index of the form in its section, or null if form not found
         */
        fun getOrderInSection(context: Context, formId: String): Int? {
            val forms = PredefinedForms.getForms(context)
            val formConfig = forms.firstOrNull { it.id == formId } ?: return null
            
            val section = formConfig.section
            val formsInSection = PredefinedForms.getFormsBySection(context, section)
            
            return formsInSection.indexOfFirst { it.id == formId }.takeIf { it >= 0 }
        }
    }
    
    /**
     * Saves form data as XML file in the site's folder
     * Draft status is determined by the submittedAt field in the XML content
     * Filename pattern: ${sectionName}_${formId}_${orderInSection}.xml or ${sectionName}_${formId}_${orderInSection}_${subIndex}.xml for dynamic forms
     * @param formData The form data to save
     * @param orderInSection The 0-based index of this specific form instance in its section (required when same formId appears multiple times)
     * @param subIndex The 0-based sub-index for dynamic forms (null for non-dynamic forms)
     * @return true if successful, false otherwise
     */
    fun saveFormData(formData: FormData, orderInSection: Int? = null, subIndex: Int? = null): Boolean {
        val (ongoingSiteFolder, _) = getSiteFolders(formData.siteName)
        val siteFolder = ongoingSiteFolder?.takeIf { it.exists() && it.canWrite() } ?: return false
        
        // Get form config to determine section
        val formConfig = PredefinedForms.getFormConfig(context, formData.formId)
            ?: run {
                AppLogger.e("FormFileHelper", "Form config not found for formId: ${formData.formId}")
                return false
            }
        
        // Get order in section (0-based)
        // If provided, use it; otherwise calculate it (for backward compatibility)
        val actualOrderInSection = orderInSection ?: getOrderInSection(context, formData.formId)
            ?: run {
                AppLogger.e("FormFileHelper", "Could not determine order in section for formId: ${formData.formId}")
                return false
            }
        
        // Generate filename
        val fileName = generateFileName(formConfig.section, formData.formId, actualOrderInSection, subIndex)
        
        AppLogger.d("FormFileHelper", "Saving form: site=${formData.siteName}, formId=${formData.formId}, orderInSection=$actualOrderInSection, subIndex=$subIndex, fileName=$fileName")
        
        // Create or update the XML file
        val existingFile = siteFolder.findFile(fileName)
        val isNewFile = existingFile == null || !existingFile.exists()
        
        // Verify that formData has a valid version
        if (formData.logsheetVersion.isEmpty()) {
            AppLogger.e("FormFileHelper", "FormData missing logsheetVersion for formId: ${formData.formId}")
            return false
        }
        
        // If updating an existing file, verify logsheetVersion matches so saved data stays in sync
        // with form definitions. A mismatch should not occur in normal flow; it may indicate a bug
        // or a legacy file from an older app version. We reject the save to avoid corrupting data.
        if (!isNewFile) {
            existingFile?.let { file ->
                try {
                    val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
                    val existingXml = inputStream?.bufferedReader().use { it?.readText() ?: "" }
                    inputStream?.close()
                    val existingFormData = FormData.fromXml(existingXml)
                    if (existingFormData == null || existingFormData.logsheetVersion.isEmpty()) {
                        AppLogger.e("FormFileHelper", "Existing file missing logsheetVersion for formId: ${formData.formId}")
                        return false
                    }
                    if (existingFormData.logsheetVersion != formData.logsheetVersion) {
                        AppLogger.e("FormFileHelper", "Version mismatch: existing=${existingFormData.logsheetVersion}, new=${formData.logsheetVersion}")
                        return false
                    }
                } catch (e: Exception) {
                    AppLogger.e("FormFileHelper", "Could not read existing file to verify version: ${e.message}", e)
                    return false
                }
            } ?: run {
                AppLogger.e("FormFileHelper", "Existing file is null for formId: ${formData.formId}")
                return false
            }
        }
        
        // Use the version from formData (already set)
        val formDataWithVersion = formData
        
        val file = if (existingFile != null && existingFile.exists()) {
            existingFile
        } else {
            siteFolder.createFile("text/xml", fileName)
        }
        
        if (file == null || !file.exists()) {
            AppLogger.e("FormFileHelper", "Failed to create/access file: $fileName for site=${formData.siteName}, form=${formData.formId}")
            return false
        }
        
        // Write XML content
        return try {
            val xmlContent = formDataWithVersion.toXml()
            val outputStream: OutputStream? = context.contentResolver.openOutputStream(file.uri)
            if (outputStream == null) {
                AppLogger.e("FormFileHelper", "openOutputStream returned null for file: $fileName")
                // If this was a newly created file, delete it to avoid leaving empty XML on disk
                if (isNewFile) {
                    try {
                        file.delete()
                    } catch (_: Exception) {
                    }
                }
                return false
            }
            
            outputStream.use { it.write(xmlContent.toByteArray()) }
            
            val status = if (formData.submittedAt != null) "submitted" else "draft"
            if (isNewFile) {
                AppLogger.i("FormFileHelper", "Created $status form: site=${formData.siteName}, form=${formData.formId}, file=$fileName")
            } else {
                AppLogger.i("FormFileHelper", "Updated $status form: site=${formData.siteName}, form=${formData.formId}, file=$fileName")
            }
            true
        } catch (e: Exception) {
            AppLogger.e("FormFileHelper", "Error saving form: site=${formData.siteName}, form=${formData.formId}, file=$fileName", e)
            android.util.Log.e("FormFileHelper", "Error saving form: ${e.message}", e)
            // If this was a newly created file, delete it so we don't leave partial/empty XML
            if (isNewFile && existingFile == null) {
                try {
                    val fileToDelete = siteFolder.findFile(fileName)
                    fileToDelete?.delete()
                } catch (_: Exception) {
                }
            }
            false
        }
    }
    
    /**
     * Loads form data from a single file. Returns null if formId doesn't match or filter doesn't match.
     * @param filterDraft true = only draft, false = only submitted, null = accept any
     */
    private fun loadFormDataFromFile(file: DocumentFile, formId: String, filterDraft: Boolean?): FormData? {
        if (!file.exists() || !file.canRead()) return null
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
            val xmlContent = inputStream?.bufferedReader().use { it?.readText() ?: "" }
            val formData = FormData.fromXml(xmlContent)
            if (formData == null || formData.formId != formId) return null
            when (filterDraft) {
                null -> formData
                true -> if (formData.submittedAt == null) formData else null
                false -> if (formData.submittedAt != null) formData else null
            }
        } catch (e: Exception) {
            AppLogger.e("FormFileHelper", "Error loading form from file: ${e.message}", e)
            null
        }
    }

    /**
     * Loads form data from a site folder (ongoing or finished). Tries findFile(fileName) and parses if found.
     */
    private fun loadFormDataFromSiteFolder(siteFolder: DocumentFile?, fileName: String, formId: String, filterDraft: Boolean?): FormData? {
        if (siteFolder == null || !siteFolder.exists() || !siteFolder.canRead()) return null
        val file = siteFolder.findFile(fileName) ?: return null
        return loadFormDataFromFile(file, formId, filterDraft)
    }

    /**
     * Returns (ongoing site folder, finished site folder) for the given site name. Used to avoid repeating folder lookup in every function.
     */
    private fun getSiteFolders(siteName: String): Pair<DocumentFile?, DocumentFile?> {
        val settingsPreferences = SettingsPreferences(context)
        val folderHelper = FolderStructureHelper(context)
        val ongoingSiteFolder = folderHelper.getOngoingFolder(settingsPreferences)?.findFile(siteName)
        val finishedSiteFolder = folderHelper.getFinishedFolder(settingsPreferences)?.findFile(siteName)
        return Pair(ongoingSiteFolder, finishedSiteFolder)
    }

    /**
     * Returns a single site folder for read (ongoing first if present, otherwise finished). Null if neither exists and is readable.
     */
    private fun getSiteFolderForRead(siteName: String): DocumentFile? {
        val (ongoing, finished) = getSiteFolders(siteName)
        return when {
            ongoing != null && ongoing.exists() && ongoing.canRead() -> ongoing
            finished != null && finished.exists() && finished.canRead() -> finished
            else -> null
        }
    }

    /**
     * Returns a single site folder for write (ongoing first if present and writable, otherwise finished). Null if neither exists and is writable.
     */
    private fun getSiteFolderForWrite(siteName: String): DocumentFile? {
        val (ongoing, finished) = getSiteFolders(siteName)
        return when {
            ongoing != null && ongoing.exists() && ongoing.canWrite() -> ongoing
            finished != null && finished.exists() && finished.canWrite() -> finished
            else -> null
        }
    }

    /**
     * Loads form data from XML file. Single implementation for both draft/submitted filtering and "any" loading.
     * @param siteName The name of the site
     * @param formId The ID of the form
     * @param orderInSection The 0-based index of this specific form instance in its section (required when same formId appears multiple times)
     * @param subIndex The 0-based sub-index for dynamic forms (null for non-dynamic forms)
     * @param loadDraft If true, loads only draft; if false, loads only submitted. Ignored when acceptAny is true.
     * @param checkFinished If true, also checks the finished folder (for finalized sites)
     * @param acceptAny If true, returns the first form found (draft or submitted) without filtering
     * @param cachedFiles If non-null, look up file in this list first (avoids findFile() when listing was already done)
     * @return FormData if found, null otherwise
     */
    fun loadFormData(
        siteName: String,
        formId: String,
        orderInSection: Int? = null,
        subIndex: Int? = null,
        loadDraft: Boolean = false,
        checkFinished: Boolean = true,
        acceptAny: Boolean = false,
        cachedFiles: List<DocumentFile>? = null
    ): FormData? {
        val formConfig = PredefinedForms.getFormConfig(context, formId) ?: return null
        val actualOrderInSection = orderInSection ?: getOrderInSection(context, formId) ?: return null
        val fileName = generateFileName(formConfig.section, formId, actualOrderInSection, subIndex)
        val filterDraft: Boolean? = if (acceptAny) null else loadDraft

        AppLogger.d("FormFileHelper", "Loading form: site=$siteName, formId=$formId, orderInSection=$actualOrderInSection, subIndex=$subIndex, fileName=$fileName, loadDraft=$loadDraft, acceptAny=$acceptAny")

        // If cached file list provided, try there first
        if (cachedFiles != null) {
            val file = cachedFiles.firstOrNull { it.name == fileName }
            if (file != null) {
                loadFormDataFromFile(file, formId, filterDraft)?.let { return it }
            }
            if (!checkFinished) return null
        }

        val (ongoingSiteFolder, finishedSiteFolder) = getSiteFolders(siteName)

        // Try the likely folder first: drafts in ongoing, submitted in finished; when acceptAny, try ongoing then finished
        val (firstFolder, secondFolder) = if (loadDraft || acceptAny) {
            Pair(ongoingSiteFolder, if (checkFinished) finishedSiteFolder else null)
        } else {
            Pair(if (checkFinished) finishedSiteFolder else null, ongoingSiteFolder)
        }

        loadFormDataFromSiteFolder(firstFolder, fileName, formId, filterDraft)?.let { return it }
        loadFormDataFromSiteFolder(secondFolder, fileName, formId, filterDraft)?.let { return it }
        return null
    }

    /**
     * Loads form data without filtering by draft/submitted status. Returns the first form found (draft or submitted).
     * Convenience for callers that will check status after loading; avoids loading the file twice.
     */
    fun loadFormDataAny(siteName: String, formId: String, orderInSection: Int? = null, subIndex: Int? = null, checkFinished: Boolean = true): FormData? {
        return loadFormData(siteName, formId, orderInSection, subIndex, loadDraft = false, checkFinished = checkFinished, acceptAny = true)
    }

    /**
     * Overload that accepts cached file list to avoid slow findFile() calls.
     */
    fun loadFormDataAny(siteName: String, formId: String, orderInSection: Int? = null, subIndex: Int? = null, checkFinished: Boolean = true, cachedFiles: List<DocumentFile>? = null): FormData? {
        return loadFormData(siteName, formId, orderInSection, subIndex, loadDraft = false, checkFinished = checkFinished, acceptAny = true, cachedFiles = cachedFiles)
    }
    
    /**
     * Checks if a form has been submitted (not just saved as draft). Uses a single load and [submittedAt];
     * see [hasDraft] for the inverse when a file exists (mutually exclusive).
     * @param orderInSection The 0-based index of this specific form instance in its section (optional, will be calculated if not provided)
     */
    fun isFormSubmitted(siteName: String, formId: String, orderInSection: Int? = null, subIndex: Int? = null): Boolean {
        return loadFormDataAny(siteName, formId, orderInSection, subIndex)?.submittedAt != null
    }

    /**
     * Checks if a form has a draft version (saved but not submitted). When a file exists it is either draft or submitted;
     * this and [isFormSubmitted] are mutually exclusive and both false when no file exists.
     * @param orderInSection The 0-based index of this specific form instance in its section (optional, will be calculated if not provided)
     * @param subIndex The 0-based sub-index for dynamic forms (null for non-dynamic forms)
     */
    fun hasDraft(siteName: String, formId: String, orderInSection: Int? = null, subIndex: Int? = null): Boolean {
        val formData = loadFormDataAny(siteName, formId, orderInSection, subIndex)
        return formData != null && formData.submittedAt == null
    }
    
    /**
     * OPTIMIZATION: Lightweight parser that only reads formId and submittedAt from the root <form> tag
     * without parsing the entire XML file. This is much faster than full FormData.fromXml parsing.
     */
    private fun parseFormHeaderOnly(inputStream: InputStream): Pair<String?, String?>? {
        return try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, null)
            
            var formId: String? = null
            var submittedAt: String? = null
            var eventType = parser.eventType
            
            // Only parse until we get the form tag attributes, then stop
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "form") {
                            formId = parser.getAttributeValue(null, "formId")
                            submittedAt = parser.getAttributeValue(null, "submittedAt")
                            // We got what we need, break early
                            break
                        }
                    }
                }
                eventType = parser.next()
            }
            
            if (formId != null) {
                Pair(formId, submittedAt)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Data class to hold both status map and file list for caching
     */
    data class FormStatusResult(
        val statusMap: Map<String, Pair<Boolean, Boolean>>,
        val fileList: List<DocumentFile> // Cached file list for reuse
    )
    
    /**
     * OPTIMIZATION: Returns both status map and cached file list to avoid multiple listFiles() calls
     * @param siteName The name of the site
     * @param checkFinished If true, also checks the finished folder (for finalized sites)
     */
    fun getAllFormStatusesWithCache(siteName: String, checkFinished: Boolean = true): FormStatusResult {
        val statusMap = mutableMapOf<String, Pair<Boolean, Boolean>>()
        val allFiles = mutableListOf<DocumentFile>()
        val (ongoingSiteFolder, finishedSiteFolder) = getSiteFolders(siteName)

        // Helper function to process files in a folder
        fun processFolder(siteFolder: DocumentFile?) {
            if (siteFolder != null && siteFolder.exists() && siteFolder.canRead()) {
                val files = siteFolder.listFiles()
                
                // OPTIMIZATION: Use more efficient filtering - check isFile first (cheaper check)
                val xmlFiles = files.filter { file ->
                    file.isFile && file.name?.endsWith(".xml") == true
                }
                allFiles.addAll(xmlFiles)
                
                xmlFiles.forEach { file ->
                    try {
                        val fileName = file.name ?: return@forEach
                        
                        // OPTIMIZATION: Only read formId and submittedAt from XML header, not full file
                        // Use buffered input stream for better performance
                        val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
                        val headerInfo = inputStream?.buffered()?.use { parseFormHeaderOnly(it) }
                        
                        if (headerInfo == null) {
                            return@forEach
                        }
                        
                        val (formId, submittedAt) = headerInfo
                        if (formId == null) {
                            return@forEach
                        }
                        
                        // Parse filename to extract orderInSection and subIndex using known formId
                        val extracted = extractFormIdAndOrderAndSubIndex(fileName, formId)
                            ?: return@forEach // Invalid format, skip

                        val (_, orderInSection, subIndex) = extracted
                        val key = if (subIndex != null) {
                            "${formId}_${orderInSection}_${subIndex}"
                        } else {
                            "${formId}_${orderInSection}"
                        }
                        val isSubmitted = submittedAt != null
                        val hasDraft = submittedAt == null
                        
                        // Update status map - if already exists, merge (submitted takes precedence)
                        val existing = statusMap[key]
                        if (existing == null) {
                            statusMap[key] = Pair(isSubmitted, hasDraft)
                        } else {
                            // If we find a submitted version, it takes precedence
                            statusMap[key] = Pair(isSubmitted || existing.first, hasDraft || existing.second)
                        }
                    } catch (e: Exception) {
                        AppLogger.e("FormFileHelper", "Error reading form file: ${file.name}", e)
                    }
                }
            }
        }

        processFolder(ongoingSiteFolder)
        if (checkFinished) processFolder(finishedSiteFolder)
        return FormStatusResult(statusMap, allFiles)
    }
    
    /**
     * Deletes the form file for this instance (draft or submitted). There is at most one file per (siteName, formId, orderInSection, subIndex).
     * @param siteName The name of the site
     * @param formId The ID of the form
     * @param orderInSection The 0-based index of this specific form instance in its section (optional, will be calculated if not provided)
     * @param subIndex The 0-based sub-index for dynamic forms (null for non-dynamic forms)
     * @return true if the file was deleted or did not exist, false on error
     */
    fun deleteForm(siteName: String, formId: String, orderInSection: Int? = null, subIndex: Int? = null): Boolean {
        val formConfig = PredefinedForms.getFormConfig(context, formId) ?: return false
        val actualOrderInSection = orderInSection ?: getOrderInSection(context, formId) ?: return false
        val fileName = generateFileName(formConfig.section, formId, actualOrderInSection, subIndex)
        val (ongoingSiteFolder, finishedSiteFolder) = getSiteFolders(siteName)
        val siteFolders = listOfNotNull(ongoingSiteFolder, finishedSiteFolder).filter { it.exists() && it.canWrite() }
        if (siteFolders.isEmpty()) {
            AppLogger.w("FormFileHelper", "Site folder not found or not writable: $siteName")
            return false
        }

        AppLogger.d("FormFileHelper", "Attempting to delete form: site=$siteName, form=$formId, orderInSection=$actualOrderInSection, subIndex=$subIndex, fileName=$fileName")

        for (siteFolder in siteFolders) {
            val file = siteFolder.findFile(fileName) ?: continue
            if (!file.exists()) continue
            return try {
                val deleted = file.delete()
                if (deleted) {
                    AppLogger.i("FormFileHelper", "Deleted form: site=$siteName, form=$formId, file=$fileName")
                    true
                } else {
                    AppLogger.w("FormFileHelper", "Failed to delete form file (delete() returned false): site=$siteName, form=$formId, file=$fileName")
                    false
                }
            } catch (e: Exception) {
                AppLogger.e("FormFileHelper", "Error deleting form: site=$siteName, form=$formId, file=$fileName", e)
                false
            }
        }

        AppLogger.d("FormFileHelper", "Form file not found (already deleted?): site=$siteName, form=$formId, file=$fileName")
        return true
    }
    
    /**
     * Saves site metadata to site_metadata.xml in the site folder
     * @param siteName The name of the site
     * @param metadata The site metadata to save
     * @return true if successful, false otherwise
     */
    fun saveSiteMetadata(siteName: String, metadata: SiteMetadata): Boolean {
        val siteFolder = getSiteFolderForWrite(siteName) ?: run {
            AppLogger.e("FormFileHelper", "Site folder not found or not writable: $siteName")
            return false
        }
        
        val fileName = "site_metadata.xml"
        val existingFile = siteFolder.findFile(fileName)
        val file = if (existingFile != null && existingFile.exists()) {
            existingFile
        } else {
            siteFolder.createFile("text/xml", fileName)
        }
        
        if (file == null || !file.exists()) {
            AppLogger.e("FormFileHelper", "Failed to create/access site metadata file: $fileName for site=$siteName")
            return false
        }
        
        return try {
            val xmlContent = SiteMetadata.toXml(metadata)
            val outputStream: OutputStream? = context.contentResolver.openOutputStream(file.uri)
            outputStream?.use { it.write(xmlContent.toByteArray()) }
            val success = outputStream != null
            if (success) {
                AppLogger.i("FormFileHelper", "Saved site metadata: site=$siteName, teamConfig=${metadata.teamConfigId}/${metadata.teamConfigVersion}")
            }
            success
        } catch (e: Exception) {
            AppLogger.e("FormFileHelper", "Error saving site metadata: site=$siteName", e)
            false
        }
    }
    
    /**
     * Loads site metadata from site_metadata.xml in the site folder
     * @param siteName The name of the site
     * @return SiteMetadata if found, null otherwise
     */
    fun loadSiteMetadata(siteName: String): SiteMetadata? {
        val siteFolder = getSiteFolderForRead(siteName) ?: return null
        
        val fileName = "site_metadata.xml"
        val file = siteFolder.findFile(fileName)
        
        if (file == null || !file.exists() || !file.canRead()) {
            return null
        }
        
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(file.uri)
            val xmlContent = inputStream?.bufferedReader().use { it?.readText() ?: "" }
            inputStream?.close()
            SiteMetadata.fromXml(xmlContent)
        } catch (e: Exception) {
            AppLogger.e("FormFileHelper", "Error loading site metadata: site=$siteName", e)
            null
        }
    }
    
    /**
     * Gets all dynamic instances of a form (by sub-index).
     * @param siteName The name of the site
     * @param formId The form ID
     * @param orderInSection The 0-based index of the form within its section
     * @return List of sub-indices that have saved forms (draft or submitted), sorted ascending
     */
    fun getDynamicFormInstances(siteName: String, formId: String, orderInSection: Int): List<Int> {
        PredefinedForms.getFormConfig(context, formId) ?: return emptyList()
        val (ongoingSiteFolder, finishedSiteFolder) = getSiteFolders(siteName)
        val instances = mutableSetOf<Int>()
        
        // Check both ongoing and finished folders
        listOfNotNull(ongoingSiteFolder, finishedSiteFolder).forEach outer@{ siteFolder ->
            if (siteFolder.exists() && siteFolder.canRead()) {
                val files = siteFolder.listFiles()

                files.forEach { file ->
                    val fileName = file.name ?: return@forEach
                    if (file.isFile && fileName.endsWith(".xml") && fileName != "site_metadata.xml") {
                        // Use known formId to parse filename more accurately
                        val extracted = extractFormIdAndOrderAndSubIndex(fileName, formId)
                        if (extracted != null) {
                            val (fileFormId, fileOrderInSection, fileSubIndex) = extracted
                            if (fileFormId == formId && fileOrderInSection == orderInSection && fileSubIndex != null) {
                                instances.add(fileSubIndex)
                            }
                        }
                    }
                }
            }
        }

        return instances.sorted()
    }

    /**
     * OPTIMIZATION: Get dynamic form instances from cached file list (avoids listFiles() call)
     */
    fun getDynamicFormInstancesFromCache(files: List<DocumentFile>, formId: String, orderInSection: Int): List<Int> {
        val instances = mutableSetOf<Int>()
        
        files.forEach { file ->
            val fileName = file.name ?: return@forEach
            if (fileName.endsWith(".xml") && fileName != "site_metadata.xml") {
                // Use known formId to parse filename more accurately
                val extracted = extractFormIdAndOrderAndSubIndex(fileName, formId)
                if (extracted != null) {
                    val (fileFormId, fileOrderInSection, fileSubIndex) = extracted
                    if (fileFormId == formId && fileOrderInSection == orderInSection && fileSubIndex != null) {
                        instances.add(fileSubIndex)
                    }
                }
            }
        }
        
        return instances.sorted()
    }
    
    /**
     * Checks if all existing dynamic instances of a form are saved (draft or submitted).
     * This determines if a new instance can be added.
     * @param siteName The name of the site
     * @param formId The form ID
     * @param orderInSection The 0-based index of the form within its section
     * @return true if all instances are saved, false if any instance is unsaved
     */
    fun canAddDynamicFormInstance(siteName: String, formId: String, orderInSection: Int): Boolean {
        val instances = getDynamicFormInstances(siteName, formId, orderInSection)
        if (instances.isEmpty()) {
            return true // No instances yet, can add first one
        }
        
        // Check if all instances have at least a draft or submitted form
        return instances.all { subIndex ->
            val draft = loadFormData(siteName, formId, orderInSection, subIndex, loadDraft = true, checkFinished = true)
            val submitted = loadFormData(siteName, formId, orderInSection, subIndex, loadDraft = false, checkFinished = true)
            draft != null || submitted != null
        }
    }
    
    /**
     * OPTIMIZATION: Check if can add dynamic form instance using cached status map (avoids loading XML files)
     */
    fun canAddDynamicFormInstanceFromStatus(statusMap: Map<String, Pair<Boolean, Boolean>>, formId: String, orderInSection: Int, instances: List<Int>): Boolean {
        if (instances.isEmpty()) {
            return true // No instances yet, can add first one
        }
        
        // Check if all instances have at least a draft or submitted form using status map
        return instances.all { subIndex ->
            val key = "${formId}_${orderInSection}_${subIndex}"
            val status = statusMap[key]
            status != null && (status.first || status.second) // isSubmitted || hasDraft
        }
    }
    
    /**
     * Deletes a dynamic form instance and reindexes all remaining instances.
     * @param siteName The name of the site
     * @param formId The form ID
     * @param orderInSection The 0-based index of the form within its section
     * @param subIndexToDelete The sub-index of the instance to delete
     * @return true if successful, false otherwise
     */
    fun deleteDynamicFormInstance(siteName: String, formId: String, orderInSection: Int, subIndexToDelete: Int): Boolean {
        val formConfig = PredefinedForms.getFormConfig(context, formId) ?: return false
        val (ongoingSiteFolder, finishedSiteFolder) = getSiteFolders(siteName)
        val siteFolders = listOfNotNull(ongoingSiteFolder, finishedSiteFolder).filter { it.exists() && it.canWrite() }
        if (siteFolders.isEmpty()) {
            AppLogger.e("FormFileHelper", "Site folder not found or not writable: $siteName")
            return false
        }
        
        // Get all instances
        val allInstances = getDynamicFormInstances(siteName, formId, orderInSection)
        if (!allInstances.contains(subIndexToDelete)) {
            AppLogger.w("FormFileHelper", "Sub-index $subIndexToDelete not found for form $formId")
            return false
        }
        
        // Delete the file(s) for this sub-index (both draft and submitted if they exist)
        var deleted = false
        siteFolders.forEach { siteFolder ->
            val fileNameToDelete = generateFileName(formConfig.section, formId, orderInSection, subIndexToDelete)
            val fileToDelete = siteFolder.findFile(fileNameToDelete)
            if (fileToDelete != null && fileToDelete.exists()) {
                try {
                    if (fileToDelete.delete()) {
                        deleted = true
                        AppLogger.d("FormFileHelper", "Deleted file: $fileNameToDelete")
                    }
                } catch (e: Exception) {
                    AppLogger.e("FormFileHelper", "Error deleting file $fileNameToDelete: ${e.message}", e)
                }
            }
        }
        
        if (!deleted) {
            AppLogger.w("FormFileHelper", "No file found to delete for sub-index $subIndexToDelete")
            return false
        }
        
        // Reindex remaining instances
        val remainingInstances = allInstances.filter { it != subIndexToDelete && it > subIndexToDelete }
        if (remainingInstances.isEmpty()) {
            return true // No reindexing needed
        }
        
        // Rename files in descending order to avoid conflicts
        remainingInstances.sortedDescending().forEach { oldSubIndex ->
            val newSubIndex = oldSubIndex - 1
            val oldFileName = generateFileName(formConfig.section, formId, orderInSection, oldSubIndex)
            val newFileName = generateFileName(formConfig.section, formId, orderInSection, newSubIndex)
            
            siteFolders.forEach { siteFolder ->
                val oldFile = siteFolder.findFile(oldFileName)
                if (oldFile != null && oldFile.exists()) {
                    try {
                        // Read the content
                        val inputStream = context.contentResolver.openInputStream(oldFile.uri)
                        val content = inputStream?.bufferedReader().use { it?.readText() ?: "" }
                        inputStream?.close()
                        
                        if (content.isNotEmpty()) {
                            // Create new file with new name
                            val newFile = siteFolder.createFile("text/xml", newFileName)
                            if (newFile != null) {
                                val outputStream = context.contentResolver.openOutputStream(newFile.uri)
                                outputStream?.use { it.write(content.toByteArray()) }
                                outputStream?.close()
                                
                                // Delete old file
                                oldFile.delete()
                                AppLogger.d("FormFileHelper", "Reindexed: $oldFileName -> $newFileName")
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.e("FormFileHelper", "Error reindexing file $oldFileName: ${e.message}", e)
                    }
                }
            }
        }
        
        return true
    }
}

