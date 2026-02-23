package com.trec.trecollect.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.widget.Toast
import com.trec.trecollect.BuildConfig
import com.trec.trecollect.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Downloads logsheets, team configs, and images from ownCloud
 */
class LogsheetDownloader(private val context: Context) {
    /**
     * Callback interface for download progress updates
     */
    interface DownloadProgressCallback {
        fun onPhaseStarted(phase: String) // e.g., "Logsheets", "Team Configs", "Images"
        fun onFileProgress(current: Int, total: Int, fileName: String)
        fun onPhaseCompleted(phase: String, downloaded: Int, failed: Int)
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val baseWebDavUrl: String = BuildConfig.OWNCLOUD_URL.trim().trimEnd('/')
    private val logsheetsToken: String = BuildConfig.OWNCLOUD_LOGSHEETS_TOKEN
    
    private val logsheetsBaseUrl: String = baseWebDavUrl
    
    companion object {
        private const val TAG = "LogsheetDownloader"
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L

        /**
         * Compares two version strings (e.g. "1.0.0", "2.1.3"). Returns negative if v1 < v2, zero if equal, positive if v1 > v2.
         */
        private fun compareVersionStrings(v1: String, v2: String): Int {
            val parts1 = v1.split(".").mapNotNull { it.toIntOrNull() }
            val parts2 = v2.split(".").mapNotNull { it.toIntOrNull() }
            for (i in 0 until maxOf(parts1.size, parts2.size)) {
                val part1 = parts1.getOrNull(i) ?: 0
                val part2 = parts2.getOrNull(i) ?: 0
                val c = part1.compareTo(part2)
                if (c != 0) return c
            }
            return 0
        }
    }

    /**
     * Storage directories in app's internal storage
     */
    private val logsheetsDir: File = File(context.filesDir, "logsheets")
    private val teamsDir: File = File(context.filesDir, "teams")
    private val imagesDir: File = File(context.filesDir, "images")
    
    /**
     * Creates Basic Authentication header for logsheets ownCloud share
     */
    private fun createAuthHeader(): String {
        val credentials = "$logsheetsToken:"
        val encoded = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        return "Basic $encoded"
    }
    
    /**
     * Checks if device has network connectivity
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    
    /**
     * Downloads all logsheets, team configs, and images
     * @param progressCallback Optional callback for progress updates
     * Returns true if successful, false otherwise
     */
    suspend fun downloadAll(progressCallback: DownloadProgressCallback? = null): Boolean = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "No network connection available", Toast.LENGTH_LONG).show()
            }
            return@withContext false
        }
        
        try {
            // Create dirs only if they don't exist (mkdirs behaviour)
            logsheetsDir.mkdirs()
            teamsDir.mkdirs()
            imagesDir.mkdirs()
            
            var success = true
            val downloadedFiles = mutableListOf<String>()
            val failedFiles = mutableListOf<String>()

            suspend fun runPhase(phaseName: String, listingErrorSuffix: String, logLabel: String, block: suspend () -> DownloadResult) {
                AppLogger.i(TAG, "Downloading $logLabel...")
                withContext(Dispatchers.Main) { progressCallback?.onPhaseStarted(phaseName) }
                val result = block()
                downloadedFiles.addAll(result.downloaded)
                failedFiles.addAll(result.failed)
                if (!result.success && result.failed.isEmpty()) failedFiles.add(listingErrorSuffix)
                withContext(Dispatchers.Main) { progressCallback?.onPhaseCompleted(phaseName, result.downloaded.size, result.failed.size) }
                if (!result.success || result.failed.isNotEmpty()) {
                    if (result.failed.isNotEmpty()) AppLogger.w(TAG, "Failed to download some $logLabel")
                    else AppLogger.w(TAG, "$phaseName phase failed (e.g. listing/connection error)")
                    success = false
                }
            }

            runPhase("Logsheets", "logsheets: (listing/connection error)", "logsheets") { downloadLogsheets(progressCallback) }
            runPhase("Team Configs", "team configs: (listing/connection error)", "team configs") { downloadTeamConfigs(progressCallback) }
            runPhase("Images", "images: (listing/connection error)", "images") { downloadImages(progressCallback) }
            
            // Print summary
            AppLogger.i(TAG, "=== Download Summary ===")
            if (downloadedFiles.isNotEmpty()) {
                AppLogger.i(TAG, "Downloaded ${downloadedFiles.size} file(s):")
                downloadedFiles.forEach { file ->
                    AppLogger.i(TAG, "  ✓ $file")
                }
            }
            if (failedFiles.isNotEmpty()) {
                AppLogger.w(TAG, "Failed ${failedFiles.size} file(s):")
                failedFiles.forEach { file ->
                    AppLogger.w(TAG, "  ✗ $file")
                }
            }
            AppLogger.i(TAG, "======================")
            
            if (success) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Logsheets updated successfully", Toast.LENGTH_SHORT).show()
                }
                AppLogger.i(TAG, "All logsheets downloaded successfully")
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Some logsheets failed to download. Check logs for details.", Toast.LENGTH_LONG).show()
                }
            }
            
            return@withContext success
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error downloading logsheets: ${e.message}", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error downloading logsheets: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return@withContext false
        }
    }
    
    /**
     * Result of a download operation
     */
    private data class DownloadResult(
        val success: Boolean,
        val downloaded: List<String>,
        val failed: List<String>
    )

    /**
     * Parameters for downloading versioned JSON files (logsheets or team configs).
     * Used to refactor downloadLogsheets and downloadTeamConfigs into one function (#42).
     */
    private data class VersionedDownloadParams(
        val remoteFolder: String,
        val localDir: File,
        val itemLabel: String,
        val emptyFoldersMessage: String,
        val listingErrorMessage: String
    )

    /**
     * Downloads versioned JSON files from a remote folder (logsheets or team configs).
     * Lists folders, picks latest version per folder, downloads only missing files.
     */
    private suspend fun downloadVersionedJsonFiles(params: VersionedDownloadParams, progressCallback: DownloadProgressCallback? = null): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val folders = listRemoteFolders(params.remoteFolder)
            if (folders.isEmpty()) {
                AppLogger.w(TAG, params.emptyFoldersMessage)
                return@withContext DownloadResult(false, emptyList(), emptyList())
            }
            var totalFiles = 0
            val filesToDownload = mutableListOf<Pair<String, String>>()
            for (folderName in folders) {
                try {
                    val versionFiles = listRemoteFiles("${params.remoteFolder}/$folderName")
                    val versions = versionFiles.mapNotNull { filename ->
                        if (filename.endsWith(".json")) filename.removeSuffix(".json") else null
                    }
                    if (versions.isNotEmpty()) {
                        val latestVersion = versions.maxWithOrNull(Comparator { a, b -> compareVersionStrings(a, b) })
                        if (latestVersion != null) {
                            val localFile = File(File(params.localDir, folderName), "$latestVersion.json")
                            if (!localFile.exists()) {
                                totalFiles++
                                filesToDownload.add(Pair(folderName, "$latestVersion.json"))
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Skip errors during counting
                }
            }
            val items = filesToDownload.map { (folderName, fileName) ->
                "$folderName/$fileName" to "$folderName/$fileName"
            }
            return@withContext downloadFileList(params.remoteFolder, params.localDir, items, params.itemLabel, progressCallback, totalFiles)
        } catch (e: Exception) {
            AppLogger.e(TAG, "${params.listingErrorMessage}: ${e.message}", e)
            return@withContext DownloadResult(false, emptyList(), emptyList())
        }
    }

    /**
     * Downloads a list of files from a remote base path into a local directory.
     * Reused for logsheets, team configs, and images (#43).
     * @param items List of (pathSuffix, displayLabel) e.g. ("folder/file.json", "folder/file.json") or ("image.png", "image.png")
     */
    private suspend fun downloadFileList(
        remoteBasePath: String,
        localDir: File,
        items: List<Pair<String, String>>,
        itemLabel: String,
        progressCallback: DownloadProgressCallback? = null,
        totalFiles: Int = items.size
    ): DownloadResult = withContext(Dispatchers.IO) {
        var successCount = 0
        var failCount = 0
        val downloaded = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var currentFile = 0
        for ((pathSuffix, displayLabel) in items) {
            try {
                val remotePath = "$remoteBasePath/$pathSuffix"
                val localFile = File(localDir, pathSuffix)
                localFile.parentFile?.mkdirs()
                currentFile++
                withContext(Dispatchers.Main) {
                    progressCallback?.onFileProgress(currentFile, totalFiles, displayLabel)
                }
                if (downloadFile(remotePath, localFile)) {
                    downloaded.add("$itemLabel: $displayLabel")
                    successCount++
                } else {
                    failed.add("$itemLabel: $displayLabel")
                    failCount++
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error downloading $itemLabel $displayLabel: ${e.message}", e)
                failed.add("$itemLabel: $displayLabel")
                failCount++
            }
        }
        AppLogger.i(TAG, "Downloaded $successCount $itemLabel(s), $failCount failed")
        DownloadResult(successCount > 0 || failCount == 0, downloaded, failed)
    }
    
    /**
     * Downloads all logsheets from logsheets/ directory
     */
    private suspend fun downloadLogsheets(progressCallback: DownloadProgressCallback? = null): DownloadResult =
        downloadVersionedJsonFiles(
            VersionedDownloadParams(
                remoteFolder = "logsheets",
                localDir = logsheetsDir,
                itemLabel = "logsheet",
                emptyFoldersMessage = "No logsheet folders found",
                listingErrorMessage = "Error listing logsheets"
            ),
            progressCallback
        )
    
    /**
     * Downloads all team configs from teams/ directory
     */
    private suspend fun downloadTeamConfigs(progressCallback: DownloadProgressCallback? = null): DownloadResult =
        downloadVersionedJsonFiles(
            VersionedDownloadParams(
                remoteFolder = "teams",
                localDir = teamsDir,
                itemLabel = "team config",
                emptyFoldersMessage = "No team folders found",
                listingErrorMessage = "Error listing team configs"
            ),
            progressCallback
        )
    
    /**
     * Downloads all images from images/ directory.
     * Reuses downloadFileList for consistency with logsheets and team configs (#43).
     */
    private suspend fun downloadImages(progressCallback: DownloadProgressCallback? = null): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val imageFiles = listRemoteFiles("images")
            if (imageFiles.isEmpty()) {
                AppLogger.w(TAG, "No images found")
                return@withContext DownloadResult(false, emptyList(), emptyList())
            }
            val filesToDownload = imageFiles.filter { imageName ->
                !File(imagesDir, imageName).exists()
            }
            val items = filesToDownload.map { it to it }
            return@withContext downloadFileList("images", imagesDir, items, "image", progressCallback, filesToDownload.size)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error listing images: ${e.message}", e)
            return@withContext DownloadResult(false, emptyList(), emptyList())
        }
    }
    
    /**
     * Lists folders in a remote directory using PROPFIND
     */
    private suspend fun listRemoteFolders(remotePath: String): List<String> = withContext(Dispatchers.IO) {
        val folders = mutableListOf<String>()
        
        try {
            // Construct URL same way as OwnCloudManager - no URL encoding needed
            val url = "$logsheetsBaseUrl/$remotePath"
            
            AppLogger.d(TAG, "Listing folders in: $url (path: $remotePath)")
            
            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", null)
                .addHeader("Depth", "1")
                .addHeader("Authorization", createAuthHeader())
                .addHeader("User-Agent", "TRECollect/1.0")
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (response.isSuccessful && response.code == 207) {
                AppLogger.d(TAG, "PROPFIND response for $remotePath: code=${response.code}")
                if (responseBody != null) {
                    AppLogger.d(TAG, "Response body (full): $responseBody")
                    // Parse XML response to extract folder names
                    folders.addAll(parsePropfindResponse(responseBody, remotePath, isFolder = true))
                }
                AppLogger.d(TAG, "Found ${folders.size} folders in $remotePath: $folders")
            } else {
                AppLogger.w(TAG, "PROPFIND failed for $remotePath: ${response.code} ${response.message}")
                if (responseBody != null) {
                    AppLogger.w(TAG, "Error response: ${responseBody.take(500)}")
                }
            }
            
            response.close()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error listing folders in $remotePath: ${e.message}", e)
        }
        
        return@withContext folders
    }
    
    /**
     * Lists files in a remote directory using PROPFIND
     */
    private suspend fun listRemoteFiles(remotePath: String): List<String> = withContext(Dispatchers.IO) {
        val files = mutableListOf<String>()
        
        try {
            // Construct URL same way as OwnCloudManager - no URL encoding needed
            val url = "$logsheetsBaseUrl/$remotePath"
            
            AppLogger.d(TAG, "Listing files in: $url (path: $remotePath)")
            
            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", null)
                .addHeader("Depth", "1")
                .addHeader("Authorization", createAuthHeader())
                .addHeader("User-Agent", "TRECollect/1.0")
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (response.isSuccessful && response.code == 207) {
                AppLogger.d(TAG, "PROPFIND response for $remotePath: code=${response.code}")
                if (responseBody != null) {
                    AppLogger.d(TAG, "Response body (full): $responseBody")
                    // Parse XML response to extract file names
                    files.addAll(parsePropfindResponse(responseBody, remotePath, isFolder = false))
                }
                AppLogger.d(TAG, "Found ${files.size} files in $remotePath: $files")
            } else {
                AppLogger.w(TAG, "PROPFIND failed for $remotePath: ${response.code} ${response.message}")
                if (responseBody != null) {
                    AppLogger.w(TAG, "Error response: ${responseBody.take(500)}")
                }
            }
            
            response.close()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error listing files in $remotePath: ${e.message}", e)
        }
        
        return@withContext files
    }
    
    /**
     * Parses PROPFIND XML response to extract folder or file names
     */
    private fun parsePropfindResponse(xml: String, basePath: String, isFolder: Boolean): List<String> {
        val items = mutableListOf<String>()
        
        try {
            AppLogger.d(TAG, "Parsing PROPFIND response for $basePath (isFolder=$isFolder)")
            
            // Use regex to extract hrefs and collection status
            // Pattern: <d:response>...</d:response> blocks
            val responsePattern = "(?s)<d:response>.*?</d:response>".toRegex()
            val responses = responsePattern.findAll(xml)
            
            // Normalize basePath for comparison (remove leading/trailing slashes)
            val normalizedBasePath = basePath.trim('/')
            
            // Expected base href pattern: /public.php/webdav/{basePath}/
            val expectedBaseHref = "/public.php/webdav/$normalizedBasePath/"
            
            for (responseMatch in responses) {
                val responseXml = responseMatch.value
                
                // Extract href
                val hrefMatch = "<d:href>([^<]+)</d:href>".toRegex().find(responseXml)
                    ?: continue

                var href = hrefMatch.groupValues[1]
                val originalHref = href
                
                // Normalize href (remove trailing slash for comparison)
                if (href.endsWith("/")) {
                    href = href.dropLast(1)
                }
                
                // Check if it's a collection (folder)
                val isCollection = "<d:collection/>".toRegex().containsMatchIn(responseXml) ||
                                   "<d:collection></d:collection>".toRegex().containsMatchIn(responseXml)
                
                // Skip the base path itself
                if (href == expectedBaseHref.trimEnd('/') || href.endsWith("/$normalizedBasePath")) {
                    AppLogger.d(TAG, "Skipping base path itself: $href")
                    continue
                }
                
                // Check if this href is a child of the base path
                // Expected: /public.php/webdav/{basePath}/{itemName}
                val expectedPrefix = "/public.php/webdav/$normalizedBasePath/"
                if (!href.startsWith(expectedPrefix)) {
                    AppLogger.d(TAG, "Skipping href not under base path: $href (expected prefix: $expectedPrefix)")
                    continue
                }
                
                // Extract the item name (the part after the base path)
                // Remove any trailing slashes and get just the filename/foldername
                val itemName = href.substringAfter(expectedPrefix).trimEnd('/')
                
                // Skip empty item names (this would be the base path itself)
                if (itemName.isEmpty()) {
                    AppLogger.d(TAG, "Skipping empty item name (base path itself): $originalHref")
                    continue
                }
                
                // For folders: add if it's a collection or has no extension
                // For files: add if it's NOT a collection and has an extension
                if (isFolder) {
                    if (isCollection || !itemName.contains(".")) {
                        items.add(itemName)
                        AppLogger.d(TAG, "Found folder: $itemName (href: $originalHref, isCollection: $isCollection)")
                    } else {
                        AppLogger.d(TAG, "Skipping non-folder item: $itemName (has extension but not collection)")
                    }
                } else {
                    if (!isCollection && itemName.contains(".")) {
                        items.add(itemName)
                        AppLogger.d(TAG, "Found file: $itemName (href: $originalHref, isCollection: $isCollection)")
                    } else {
                        AppLogger.d(TAG, "Skipping non-file item: $itemName (isCollection: $isCollection, hasExtension: ${itemName.contains(".")})")
                    }
                }
            }
            
            AppLogger.d(TAG, "Parsed ${items.size} items from PROPFIND response: $items")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error parsing PROPFIND response: ${e.message}", e)
            AppLogger.e(TAG, "XML was: ${xml.take(1000)}", e)
        }
        
        return items.distinct()
    }
    
    /**
     * Downloads a file from remote path to local file
     */
    @Suppress("UNUSED_VARIABLE")
    private suspend fun downloadFile(remotePath: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        var delayMs = INITIAL_RETRY_DELAY_MS
        
        repeat(MAX_RETRIES) { attempt ->
            try {
                // Construct URL same way as OwnCloudManager - no URL encoding needed
                val url = "$logsheetsBaseUrl/$remotePath"
                
                AppLogger.d(TAG, "Downloading: $url (attempt ${attempt + 1}/$MAX_RETRIES)")
                
                val request = Request.Builder()
                    .url(url)
                    .get() // Use GET method for downloading files
                    .addHeader("Authorization", createAuthHeader())
                    .addHeader("User-Agent", "TRECollect/1.0")
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    response.body?.let { body ->
                        // Ensure parent directory exists
                        localFile.parentFile?.mkdirs()
                        
                        // Write to file - use bytes for binary files (images), text for JSON
                        val isImage = remotePath.endsWith(".png", ignoreCase = true) ||
                                     remotePath.endsWith(".jpg", ignoreCase = true) ||
                                     remotePath.endsWith(".jpeg", ignoreCase = true)
                        
                        if (isImage) {
                            // For images, use bytes
                            localFile.writeBytes(body.bytes())
                        } else {
                            // For JSON and text files, use string
                            val text = body.string()
                            localFile.writeText(text)
                        }
                        
                        AppLogger.d(TAG, "Downloaded $remotePath to ${localFile.absolutePath}")
                        response.close()
                        return@withContext true
                    } ?: run {
                        AppLogger.w(TAG, "Download response body is null for $remotePath")
                        response.close()
                    }
                } else {
                    val errorBody = response.body?.string()
                    AppLogger.w(TAG, "Failed to download $remotePath: ${response.code} ${response.message}")
                    if (errorBody != null) {
                        AppLogger.w(TAG, "Error response: ${errorBody.take(500)}")
                    }
                    response.close()
                }
            } catch (e: SocketTimeoutException) {
                lastException = e
                AppLogger.w(TAG, "Timeout downloading $remotePath (attempt ${attempt + 1}/$MAX_RETRIES): ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(10000L)
                }
            } catch (e: IOException) {
                lastException = e
                AppLogger.w(TAG, "IO error downloading $remotePath (attempt ${attempt + 1}/$MAX_RETRIES): ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(10000L)
                }
            } catch (e: Exception) {
                lastException = e
                AppLogger.e(TAG, "Error downloading $remotePath: ${e.message}", e)
                return@withContext false
            }
        }
        
        AppLogger.e(TAG, "Failed to download $remotePath after $MAX_RETRIES attempts: ${lastException?.message}")
        return@withContext false
    }
    
    /**
     * Checks if logsheets have been downloaded (checks if directories exist and have files)
     */
    fun hasDownloadedLogsheets(): Boolean {
        return logsheetsDir.exists() && logsheetsDir.listFiles()?.any { it.isDirectory && it.listFiles()?.isNotEmpty() == true } == true &&
               teamsDir.exists() && teamsDir.listFiles()?.any { it.isDirectory && it.listFiles()?.isNotEmpty() == true } == true
    }
    
    /**
     * Gets the local file path for a logsheet (returns the latest version)
     */
    fun getLogsheetFile(logsheetId: String): File? {
        val logsheetFolder = File(logsheetsDir, logsheetId)
        if (!logsheetFolder.exists() || !logsheetFolder.isDirectory) {
            return null
        }
        
        // Find the latest version file
        val versionFiles = logsheetFolder.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: return null
        if (versionFiles.isEmpty()) return null
        
        // Get the latest version by comparing version numbers
        val latestFile = versionFiles.maxWithOrNull(Comparator { f1, f2 ->
            compareVersionStrings(f1.name.removeSuffix(".json"), f2.name.removeSuffix(".json"))
        })
        
        return latestFile
    }
    
    /**
     * Gets a specific version of a logsheet file
     * @param logsheetId The logsheet ID (folder name)
     * @param version The version string (e.g., "1.0.0")
     * @return The logsheet file if found, null otherwise
     */
    fun getLogsheetFile(logsheetId: String, version: String): File? {
        val logsheetFolder = File(logsheetsDir, logsheetId)
        if (!logsheetFolder.exists() || !logsheetFolder.isDirectory) {
            return null
        }
        
        // Find the specific version file
        val versionFile = File(logsheetFolder, "$version.json")
        return if (versionFile.exists() && versionFile.isFile) {
            versionFile
        } else {
            null
        }
    }
    
    /**
     * Gets the version of the latest logsheet file
     * @param logsheetId The logsheet ID (folder name)
     * @return The version string (e.g., "1.0.0") if found, null otherwise
     */
    fun getLatestLogsheetVersion(logsheetId: String): String? {
        val latestFile = getLogsheetFile(logsheetId)
        return latestFile?.name?.removeSuffix(".json")
    }
    
    /**
     * Gets a specific version of a team config by folder ID and version
     * @param teamId The team config folder ID
     * @param version The version string (e.g., "1.0.0")
     * @return The team config file if found, null otherwise
     */
    fun getTeamConfigFile(teamId: String, version: String): File? {
        val teamFolder = File(teamsDir, teamId)
        if (!teamFolder.exists() || !teamFolder.isDirectory) {
            return null
        }
        
        // Find the specific version file
        val versionFile = File(teamFolder, "$version.json")
        return if (versionFile.exists() && versionFile.isFile) {
            versionFile
        } else {
            null
        }
    }
    
    /**
     * Finds a team config file by matching team and name/subteam fields.
     * Subteam is required: in every case there must be a subteam (from config/data); no hardcoded team-specific behaviour.
     * Returns the latest version file that matches the criteria, or null if subteam is blank or no match.
     */
    fun findTeamConfigByTeamAndName(team: String, subteam: String?): File? {
        if (subteam.isNullOrBlank()) return null
        if (!teamsDir.exists() || !teamsDir.isDirectory) {
            return null
        }
        
        val teamFolders = teamsDir.listFiles()?.filter { it.isDirectory } ?: return null
        
        for (teamFolder in teamFolders) {
            // Find the latest version file in this folder
            val versionFiles = teamFolder.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: continue
            if (versionFiles.isEmpty()) continue
            
            val latestFile = versionFiles.maxWithOrNull(Comparator { f1, f2 ->
                compareVersionStrings(f1.name.removeSuffix(".json"), f2.name.removeSuffix(".json"))
            }) ?: continue
            
            try {
                val configJson = latestFile.readText()
                val configObj = org.json.JSONObject(configJson)
                val configTeam = configObj.optString("team", "")
                val configName = configObj.optString("name", "")
                if (configTeam == team && configName.equals(subteam, ignoreCase = true)) {
                    return latestFile
                }
            } catch (e: Exception) {
                // Skip invalid JSON files
                continue
            }
        }
        
        return null
    }
    
    /**
     * Gets the local file path for an image
     */
    fun getImageFile(imagePath: String): File? {
        // imagePath format: "images/filename.png" or just "filename.png"
        val fileName = if (imagePath.startsWith("images/")) {
            imagePath.substringAfter("images/")
        } else {
            imagePath
        }
        val file = File(imagesDir, fileName)
        return if (file.exists()) file else null
    }
    
    /**
     * Discovers all available teams by scanning downloaded team configs.
     * Returns distinct "team" values from config JSON files; empty if none downloaded.
     */
    fun getAvailableTeams(): List<String> {
        if (!teamsDir.exists() || !teamsDir.isDirectory) {
            return emptyList()
        }
        val teams = mutableSetOf<String>()
        val teamFolders = teamsDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        for (teamFolder in teamFolders) {
            val versionFiles = teamFolder.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: continue
            if (versionFiles.isEmpty()) continue
            val latestFile = versionFiles.maxWithOrNull(Comparator { f1, f2 ->
                compareVersionStrings(f1.name.removeSuffix(".json"), f2.name.removeSuffix(".json"))
            }) ?: continue
            try {
                val configObj = org.json.JSONObject(latestFile.readText())
                val configTeam = configObj.optString("team", "")
                if (configTeam.isNotEmpty()) teams.add(configTeam)
            } catch (e: Exception) {
                continue
            }
        }
        return teams.sorted()
    }

    /**
     * Discovers all available subteams for a given team by scanning downloaded team configs
     * Returns a list of subteam names (the "name" field from team configs)
     */
    fun getAvailableSubteams(team: String): List<String> {
        if (!teamsDir.exists() || !teamsDir.isDirectory) {
            return emptyList()
        }
        
        val subteams = mutableSetOf<String>()
        val teamFolders = teamsDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        
        for (teamFolder in teamFolders) {
            // Find the latest version file in this folder
            val versionFiles = teamFolder.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: continue
            if (versionFiles.isEmpty()) continue
            
            val latestFile = versionFiles.maxWithOrNull(Comparator { f1, f2 ->
                compareVersionStrings(f1.name.removeSuffix(".json"), f2.name.removeSuffix(".json"))
            }) ?: continue
            
            // Read the config file and check if it matches the team
            try {
                val configJson = latestFile.readText()
                val configObj = org.json.JSONObject(configJson)
                val configTeam = configObj.optString("team", "")
                val configName = configObj.optString("name", "")
                
                // Match team field
                if (configTeam == team && configName.isNotEmpty()) {
                    subteams.add(configName)
                }
            } catch (e: Exception) {
                // Skip invalid JSON files
                continue
            }
        }
        
        return subteams.sorted()
    }
}
