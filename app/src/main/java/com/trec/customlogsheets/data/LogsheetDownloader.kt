package com.trec.customlogsheets.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.trec.customlogsheets.R
import com.trec.customlogsheets.util.AppLogger
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val baseWebDavUrl: String = context.getString(R.string.owncloud_url).trimEnd('/')
    private val logsheetsToken: String = context.getString(R.string.owncloud_logsheets_token)
    
    private val logsheetsBaseUrl: String = baseWebDavUrl
    
    companion object {
        private const val TAG = "LogsheetDownloader"
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
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
     * Returns true if successful, false otherwise
     */
    suspend fun downloadAll(): Boolean = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "No network connection available", Toast.LENGTH_LONG).show()
            }
            return@withContext false
        }
        
        try {
            // Ensure directories exist
            logsheetsDir.mkdirs()
            teamsDir.mkdirs()
            imagesDir.mkdirs()
            
            var success = true
            
            // Download logsheets
            AppLogger.i(TAG, "Downloading logsheets...")
            val logsheetsSuccess = downloadLogsheets()
            if (!logsheetsSuccess) {
                AppLogger.w(TAG, "Failed to download some logsheets")
                success = false
            }
            
            // Download team configs
            AppLogger.i(TAG, "Downloading team configs...")
            val teamsSuccess = downloadTeamConfigs()
            if (!teamsSuccess) {
                AppLogger.w(TAG, "Failed to download some team configs")
                success = false
            }
            
            // Download images
            AppLogger.i(TAG, "Downloading images...")
            val imagesSuccess = downloadImages()
            if (!imagesSuccess) {
                AppLogger.w(TAG, "Failed to download some images")
                success = false
            }
            
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
     * Downloads all logsheets from logsheets/ directory
     */
    private suspend fun downloadLogsheets(): Boolean = withContext(Dispatchers.IO) {
        try {
            // List logsheet folders
            val logsheetFolders = listRemoteFolders("logsheets")
            if (logsheetFolders.isEmpty()) {
                AppLogger.w(TAG, "No logsheet folders found")
                return@withContext false
            }
            
            var successCount = 0
            var failCount = 0
            
            for (folderName in logsheetFolders) {
                try {
                    // List versions in this logsheet folder
                    val versionFiles = listRemoteFiles("logsheets/$folderName")
                    if (versionFiles.isEmpty()) {
                        AppLogger.w(TAG, "No versions found for logsheet $folderName")
                        failCount++
                        continue
                    }
                    
                    // Extract version numbers from filenames (e.g., "1.0.0.json" -> "1.0.0")
                    val versions = versionFiles.mapNotNull { filename ->
                        if (filename.endsWith(".json")) {
                            filename.removeSuffix(".json")
                        } else {
                            null
                        }
                    }
                    
                    if (versions.isEmpty()) {
                        AppLogger.w(TAG, "No valid version files found for logsheet $folderName")
                        failCount++
                        continue
                    }
                    
                    // Get latest version (compare semantic versions)
                    val latestVersion = versions.maxWithOrNull(Comparator { v1, v2 ->
                        val parts1 = v1.split(".").mapNotNull { it.toIntOrNull() }
                        val parts2 = v2.split(".").mapNotNull { it.toIntOrNull() }
                        // Compare version parts numerically
                        for (i in 0 until maxOf(parts1.size, parts2.size)) {
                            val part1 = parts1.getOrNull(i) ?: 0
                            val part2 = parts2.getOrNull(i) ?: 0
                            val comparison = part1.compareTo(part2)
                            if (comparison != 0) return@Comparator comparison
                        }
                        0
                    })
                    if (latestVersion == null) {
                        AppLogger.w(TAG, "Could not determine latest version for logsheet $folderName")
                        failCount++
                        continue
                    }
                    
                    // Download only the latest version - preserve folder structure and version name
                    val remotePath = "logsheets/$folderName/$latestVersion.json"
                    val logsheetFolder = File(logsheetsDir, folderName)
                    logsheetFolder.mkdirs()
                    val localFile = File(logsheetFolder, "$latestVersion.json")
                    
                    // Skip if file already exists
                    if (localFile.exists()) {
                        AppLogger.d(TAG, "Logsheet $folderName version $latestVersion already exists, skipping")
                        successCount++
                        continue
                    }
                    
                    if (downloadFile(remotePath, localFile)) {
                        AppLogger.d(TAG, "Downloaded logsheet $folderName version $latestVersion")
                        successCount++
                    } else {
                        AppLogger.w(TAG, "Failed to download logsheet $folderName")
                        failCount++
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error downloading logsheet $folderName: ${e.message}", e)
                    failCount++
                }
            }
            
            AppLogger.i(TAG, "Downloaded $successCount logsheets, $failCount failed")
            return@withContext successCount > 0
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error listing logsheets: ${e.message}", e)
            return@withContext false
        }
    }
    
    /**
     * Downloads all team configs from teams/ directory
     */
    private suspend fun downloadTeamConfigs(): Boolean = withContext(Dispatchers.IO) {
        try {
            // List team folders
            val teamFolders = listRemoteFolders("teams")
            if (teamFolders.isEmpty()) {
                AppLogger.w(TAG, "No team folders found")
                return@withContext false
            }
            
            var successCount = 0
            var failCount = 0
            
            for (folderName in teamFolders) {
                try {
                    // List versions in this team folder
                    val versionFiles = listRemoteFiles("teams/$folderName")
                    if (versionFiles.isEmpty()) {
                        AppLogger.w(TAG, "No versions found for team $folderName")
                        failCount++
                        continue
                    }
                    
                    // Extract version numbers from filenames
                    val versions = versionFiles.mapNotNull { filename ->
                        if (filename.endsWith(".json")) {
                            filename.removeSuffix(".json")
                        } else {
                            null
                        }
                    }
                    
                    if (versions.isEmpty()) {
                        AppLogger.w(TAG, "No valid version files found for team $folderName")
                        failCount++
                        continue
                    }
                    
                    // Get latest version (compare semantic versions)
                    val latestVersion = versions.maxWithOrNull(Comparator { v1, v2 ->
                        val parts1 = v1.split(".").mapNotNull { it.toIntOrNull() }
                        val parts2 = v2.split(".").mapNotNull { it.toIntOrNull() }
                        // Compare version parts numerically
                        for (i in 0 until maxOf(parts1.size, parts2.size)) {
                            val part1 = parts1.getOrNull(i) ?: 0
                            val part2 = parts2.getOrNull(i) ?: 0
                            val comparison = part1.compareTo(part2)
                            if (comparison != 0) return@Comparator comparison
                        }
                        0
                    })
                    if (latestVersion == null) {
                        AppLogger.w(TAG, "Could not determine latest version for team $folderName")
                        failCount++
                        continue
                    }
                    
                    // Download only the latest version - preserve folder structure and version name
                    val remotePath = "teams/$folderName/$latestVersion.json"
                    val teamFolder = File(teamsDir, folderName)
                    teamFolder.mkdirs()
                    val localFile = File(teamFolder, "$latestVersion.json")
                    
                    // Skip if file already exists
                    if (localFile.exists()) {
                        AppLogger.d(TAG, "Team config $folderName version $latestVersion already exists, skipping")
                        successCount++
                        continue
                    }
                    
                    if (downloadFile(remotePath, localFile)) {
                        AppLogger.d(TAG, "Downloaded team config $folderName version $latestVersion")
                        successCount++
                    } else {
                        AppLogger.w(TAG, "Failed to download team config $folderName")
                        failCount++
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error downloading team config $folderName: ${e.message}", e)
                    failCount++
                }
            }
            
            AppLogger.i(TAG, "Downloaded $successCount team configs, $failCount failed")
            return@withContext successCount > 0
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error listing team configs: ${e.message}", e)
            return@withContext false
        }
    }
    
    /**
     * Downloads all images from images/ directory
     */
    private suspend fun downloadImages(): Boolean = withContext(Dispatchers.IO) {
        try {
            // List image files
            val imageFiles = listRemoteFiles("images")
            if (imageFiles.isEmpty()) {
                AppLogger.w(TAG, "No images found")
                return@withContext false
            }
            
            var successCount = 0
            var failCount = 0
            
            for (imageName in imageFiles) {
                try {
                    val remotePath = "images/$imageName"
                    val localFile = File(imagesDir, imageName)
                    
                    // Skip if file already exists
                    if (localFile.exists()) {
                        AppLogger.d(TAG, "Image $imageName already exists, skipping")
                        successCount++
                        continue
                    }
                    
                    if (downloadFile(remotePath, localFile)) {
                        AppLogger.d(TAG, "Downloaded image $imageName")
                        successCount++
                    } else {
                        AppLogger.w(TAG, "Failed to download image $imageName")
                        failCount++
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error downloading image $imageName: ${e.message}", e)
                    failCount++
                }
            }
            
            AppLogger.i(TAG, "Downloaded $successCount images, $failCount failed")
            return@withContext successCount > 0
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error listing images: ${e.message}", e)
            return@withContext false
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
                .addHeader("User-Agent", "TREC-Custom-Logsheets/1.0")
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
                .addHeader("User-Agent", "TREC-Custom-Logsheets/1.0")
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
            AppLogger.d(TAG, "Full XML: $xml")
            
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
                if (hrefMatch == null) continue
                
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
                var itemName = href.substringAfter(expectedPrefix).trimEnd('/')
                
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
                    .addHeader("User-Agent", "TREC-Custom-Logsheets/1.0")
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
            val v1 = f1.name.removeSuffix(".json")
            val v2 = f2.name.removeSuffix(".json")
            val parts1 = v1.split(".").mapNotNull { it.toIntOrNull() }
            val parts2 = v2.split(".").mapNotNull { it.toIntOrNull() }
            for (i in 0 until maxOf(parts1.size, parts2.size)) {
                val part1 = parts1.getOrNull(i) ?: 0
                val part2 = parts2.getOrNull(i) ?: 0
                val comparison = part1.compareTo(part2)
                if (comparison != 0) return@Comparator comparison
            }
            0
        })
        
        return latestFile
    }
    
    /**
     * Gets the local file path for a team config by folder ID (returns the latest version)
     */
    fun getTeamConfigFile(teamId: String): File? {
        val teamFolder = File(teamsDir, teamId)
        if (!teamFolder.exists() || !teamFolder.isDirectory) {
            return null
        }
        
        // Find the latest version file
        val versionFiles = teamFolder.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: return null
        if (versionFiles.isEmpty()) return null
        
        // Get the latest version by comparing version numbers
        val latestFile = versionFiles.maxWithOrNull(Comparator { f1, f2 ->
            val v1 = f1.name.removeSuffix(".json")
            val v2 = f2.name.removeSuffix(".json")
            val parts1 = v1.split(".").mapNotNull { it.toIntOrNull() }
            val parts2 = v2.split(".").mapNotNull { it.toIntOrNull() }
            for (i in 0 until maxOf(parts1.size, parts2.size)) {
                val part1 = parts1.getOrNull(i) ?: 0
                val part2 = parts2.getOrNull(i) ?: 0
                val comparison = part1.compareTo(part2)
                if (comparison != 0) return@Comparator comparison
            }
            0
        })
        
        return latestFile
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
     * Finds a team config file by matching team and name/subteam fields
     * Returns the latest version file that matches the criteria
     */
    fun findTeamConfigByTeamAndName(team: String, subteam: String?): File? {
        if (!teamsDir.exists() || !teamsDir.isDirectory) {
            return null
        }
        
        val teamFolders = teamsDir.listFiles()?.filter { it.isDirectory } ?: return null
        
        for (teamFolder in teamFolders) {
            // Find the latest version file in this folder
            val versionFiles = teamFolder.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: continue
            if (versionFiles.isEmpty()) continue
            
            val latestFile = versionFiles.maxWithOrNull(Comparator { f1, f2 ->
                val v1 = f1.name.removeSuffix(".json")
                val v2 = f2.name.removeSuffix(".json")
                val parts1 = v1.split(".").mapNotNull { it.toIntOrNull() }
                val parts2 = v2.split(".").mapNotNull { it.toIntOrNull() }
                for (i in 0 until maxOf(parts1.size, parts2.size)) {
                    val part1 = parts1.getOrNull(i) ?: 0
                    val part2 = parts2.getOrNull(i) ?: 0
                    val comparison = part1.compareTo(part2)
                    if (comparison != 0) return@Comparator comparison
                }
                0
            }) ?: continue
            
            // Read the config file and check if it matches
            try {
                val configJson = latestFile.readText()
                val configObj = org.json.JSONObject(configJson)
                val configTeam = configObj.optString("team", "")
                val configName = configObj.optString("name", "")
                
                // Match team field
                if (configTeam != team) continue
                
                // For LSI, match the name field with subteam
                // For AML, name field can be "AML - placeholder" or any other value - we accept any AML config
                if (team == "LSI") {
                    // For LSI, subteam must match the name field in the config
                    if (subteam != null && subteam.isNotEmpty() && configName.equals(subteam, ignoreCase = true)) {
                        return latestFile
                    }
                } else if (team == "AML") {
                    // For AML, we accept any config with team="AML" regardless of name field
                    // The name field is just metadata (e.g., "AML - placeholder")
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
                val v1 = f1.name.removeSuffix(".json")
                val v2 = f2.name.removeSuffix(".json")
                val parts1 = v1.split(".").mapNotNull { it.toIntOrNull() }
                val parts2 = v2.split(".").mapNotNull { it.toIntOrNull() }
                for (i in 0 until maxOf(parts1.size, parts2.size)) {
                    val part1 = parts1.getOrNull(i) ?: 0
                    val part2 = parts2.getOrNull(i) ?: 0
                    val comparison = part1.compareTo(part2)
                    if (comparison != 0) return@Comparator comparison
                }
                0
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
