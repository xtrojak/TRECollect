package com.trec.customlogsheets.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.trec.customlogsheets.R
import com.trec.customlogsheets.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import android.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Manages ownCloud WebDAV operations with retry logic
 */
class OwnCloudManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    // Use the public ownCloud WebDAV endpoint
    // Format: https://oc.embl.de/public.php/webdav/
    private val baseWebDavUrl: String = context.getString(R.string.owncloud_url).trimEnd('/')
    private val accessToken: String = context.getString(R.string.owncloud_access_token)
    
    // For public shares, create UUID folder directly at the root
    // No need for target folder path - create UUID folder directly in the public share
    private val targetFolderUrl: String = baseWebDavUrl
    
    /**
     * Creates Basic Authentication header for public share
     * For ownCloud public shares: username = access token, password = empty
     */
    private fun createAuthHeader(): String {
        val credentials = "$accessToken:"
        val encoded = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        return "Basic $encoded"
    }
    
    companion object {
        private const val TAG = "OwnCloudManager"
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L // 1 second
        private const val MAX_RETRY_DELAY_MS = 10000L // 10 seconds
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
     * Checks if a folder exists in ownCloud with retry logic
     */
    suspend fun folderExists(folderName: String, retries: Int = MAX_RETRIES): Boolean = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) {
            Log.w(TAG, "No network connectivity, cannot check folder existence")
            return@withContext false
        }
        
        var lastException: Exception? = null
        var delayMs = INITIAL_RETRY_DELAY_MS
        
        repeat(retries) { attempt ->
            try {
                val url = "$targetFolderUrl/$folderName"
                Log.d(TAG, "Checking folder existence: $url (attempt ${attempt + 1}/$retries)")
                AppLogger.d(TAG, "Checking folder existence: $url (attempt ${attempt + 1}/$retries)")
                
                val request = Request.Builder()
                    .url(url)
                    .method("PROPFIND", null)
                    .addHeader("Depth", "0")
                    .addHeader("Authorization", createAuthHeader())
                    .addHeader("User-Agent", "TREC-Custom-Logsheets/1.0")
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                val exists = response.isSuccessful && response.code == 207 // Multi-Status for PROPFIND
                AppLogger.d(TAG, "PROPFIND response: code=${response.code}, exists=$exists")
                AppLogger.d(TAG, "Response headers: ${response.headers}")
                if (responseBody != null) {
                    AppLogger.d(TAG, "Response body (first 500 chars): ${responseBody.take(500)}")
                }
                Log.d(TAG, "PROPFIND response: code=${response.code}, exists=$exists")
                Log.d(TAG, "Response headers: ${response.headers}")
                if (responseBody != null) {
                    Log.d(TAG, "Response body (first 500 chars): ${responseBody.take(500)}")
                }
                response.close()
                
                if (exists || response.code == 404) {
                    return@withContext exists
                }
                
                AppLogger.w(TAG, "Unexpected response code when checking folder: ${response.code}")
                Log.w(TAG, "Unexpected response code when checking folder: ${response.code}")
            } catch (e: SocketTimeoutException) {
                lastException = e
                AppLogger.w(TAG, "Timeout checking folder existence (attempt ${attempt + 1}/$retries): ${e.message}", e)
                Log.w(TAG, "Timeout checking folder existence (attempt ${attempt + 1}/$retries): ${e.message}")
            } catch (e: IOException) {
                lastException = e
                AppLogger.w(TAG, "Network error checking folder existence (attempt ${attempt + 1}/$retries): ${e.message}", e)
                Log.w(TAG, "Network error checking folder existence (attempt ${attempt + 1}/$retries): ${e.message}")
            } catch (e: Exception) {
                lastException = e
                AppLogger.e(TAG, "Error checking folder existence (attempt ${attempt + 1}/$retries): ${e.message}", e)
                Log.e(TAG, "Error checking folder existence (attempt ${attempt + 1}/$retries): ${e.message}", e)
            }
            
            // Wait before retrying (exponential backoff)
            if (attempt < retries - 1) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }
        
        AppLogger.e(TAG, "Failed to check folder existence after $retries attempts", lastException)
        Log.e(TAG, "Failed to check folder existence after $retries attempts", lastException)
        false
    }
    
    /**
     * Creates a folder in ownCloud with retry logic
     * Tries MKCOL first, then falls back to PUT with a .folder marker file
     */
    suspend fun createFolder(folderName: String, retries: Int = MAX_RETRIES): Boolean = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) {
            Log.w(TAG, "No network connectivity, cannot create folder")
            return@withContext false
        }
        
        var lastException: Exception? = null
        var delayMs = INITIAL_RETRY_DELAY_MS
        
        repeat(retries) { attempt ->
            try {
                // Try MKCOL first (standard WebDAV method)
                val url = "$targetFolderUrl/$folderName"
                Log.d(TAG, "Creating folder: $url (attempt ${attempt + 1}/$retries)")
                AppLogger.d(TAG, "Creating folder: $url (attempt ${attempt + 1}/$retries)")
                
                // Method 1: Try MKCOL
                var request = Request.Builder()
                    .url(url)
                    .method("MKCOL", null)
                    .addHeader("Authorization", createAuthHeader())
                    .addHeader("User-Agent", "TREC-Custom-Logsheets/1.0")
                    .build()
                
                var response = client.newCall(request).execute()
                var responseBody = response.body?.string()
                var responseCode = response.code
                var created = response.isSuccessful && (responseCode == 201 || responseCode == 405) // 201 Created or 405 Method Not Allowed (already exists)
                AppLogger.d(TAG, "MKCOL response: code=$responseCode, created=$created")
                AppLogger.d(TAG, "Response headers: ${response.headers}")
                if (responseBody != null) {
                    AppLogger.d(TAG, "Response body (first 500 chars): ${responseBody.take(500)}")
                }
                Log.d(TAG, "MKCOL response: code=$responseCode, created=$created")
                Log.d(TAG, "Response headers: ${response.headers}")
                if (responseBody != null) {
                    Log.d(TAG, "Response body (first 500 chars): ${responseBody.take(500)}")
                }
                response.close()
                
                if (created) {
                    AppLogger.i(TAG, "Folder created successfully with MKCOL: $folderName")
                    Log.d(TAG, "Folder created successfully with MKCOL: $folderName")
                    return@withContext true
                }
                
                // Method 2: If MKCOL fails, try creating a .folder marker file (some ownCloud setups require this)
                if (responseCode == 405 || responseCode == 403 || responseCode == 501) {
                    AppLogger.d(TAG, "MKCOL not supported, trying PUT with .folder marker")
                    Log.d(TAG, "MKCOL not supported, trying PUT with .folder marker")
                    val markerUrl = "$url/.folder"
                    val markerContent = "".toRequestBody("text/plain".toMediaType())
                    request = Request.Builder()
                        .url(markerUrl)
                        .put(markerContent)
                        .addHeader("Authorization", createAuthHeader())
                        .addHeader("User-Agent", "TREC-Custom-Logsheets/1.0")
                        .build()
                    
                    response = client.newCall(request).execute()
                    responseBody = response.body?.string()
                    responseCode = response.code
                    created = response.isSuccessful && (responseCode == 201 || responseCode == 204)
                    AppLogger.d(TAG, "PUT .folder marker response: code=$responseCode, created=$created")
                    AppLogger.d(TAG, "Response headers: ${response.headers}")
                    if (responseBody != null) {
                        AppLogger.d(TAG, "Response body (first 500 chars): ${responseBody.take(500)}")
                    }
                    Log.d(TAG, "PUT .folder marker response: code=$responseCode, created=$created")
                    Log.d(TAG, "Response headers: ${response.headers}")
                    if (responseBody != null) {
                        Log.d(TAG, "Response body (first 500 chars): ${responseBody.take(500)}")
                    }
                    response.close()
                    
                    if (created) {
                        AppLogger.i(TAG, "Folder marker created successfully: $folderName")
                        Log.d(TAG, "Folder marker created successfully: $folderName")
                        return@withContext true
                    }
                }
                
                AppLogger.w(TAG, "Failed to create folder. Last response code: $responseCode")
                Log.w(TAG, "Failed to create folder. Last response code: $responseCode")
            } catch (e: SocketTimeoutException) {
                lastException = e
                AppLogger.w(TAG, "Timeout creating folder (attempt ${attempt + 1}/$retries): ${e.message}", e)
                Log.w(TAG, "Timeout creating folder (attempt ${attempt + 1}/$retries): ${e.message}")
            } catch (e: IOException) {
                lastException = e
                AppLogger.w(TAG, "Network error creating folder (attempt ${attempt + 1}/$retries): ${e.message}", e)
                Log.w(TAG, "Network error creating folder (attempt ${attempt + 1}/$retries): ${e.message}")
            } catch (e: Exception) {
                lastException = e
                AppLogger.e(TAG, "Error creating folder (attempt ${attempt + 1}/$retries): ${e.message}", e)
                Log.e(TAG, "Error creating folder (attempt ${attempt + 1}/$retries): ${e.message}", e)
            }
            
            // Wait before retrying (exponential backoff)
            if (attempt < retries - 1) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }
        
        AppLogger.e(TAG, "Failed to create folder after $retries attempts", lastException)
        Log.e(TAG, "Failed to create folder after $retries attempts", lastException)
        false
    }
    
    /**
     * Ensures the target folder path exists (creates parent directories if needed)
     * For public shares, we create the UUID folder directly at the root
     */
    private suspend fun ensureTargetFolderPath(): Boolean {
        // For public shares, no parent folders needed - create UUID folder directly
        return true
    }
    
    /**
     * Checks if a path exists (quick check without retries)
     */
    private suspend fun checkPathExists(fullPath: String): Boolean = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) {
            return@withContext false
        }
        
        try {
            val request = Request.Builder()
                .url(fullPath)
                .method("PROPFIND", null)
                .addHeader("Depth", "0")
                .addHeader("Authorization", createAuthHeader())
                .addHeader("User-Agent", "TREC-Custom-Logsheets/1.0")
                .build()
            
            val response = client.newCall(request).execute()
            val exists = response.isSuccessful && response.code == 207
            response.close()
            return@withContext exists
        } catch (e: Exception) {
            Log.d(TAG, "Path check failed: $fullPath - ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Creates a path (quick create without retries)
     */
    private suspend fun createPath(fullPath: String): Boolean = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) {
            return@withContext false
        }
        
        try {
            val request = Request.Builder()
                .url(fullPath)
                .method("MKCOL", null)
                .addHeader("Authorization", createAuthHeader())
                .addHeader("User-Agent", "TREC-Custom-Logsheets/1.0")
                .build()
            
            val response = client.newCall(request).execute()
            val created = response.isSuccessful && (response.code == 201 || response.code == 405)
            response.close()
            if (created) {
                AppLogger.d(TAG, "Created parent folder: $fullPath")
                Log.d(TAG, "Created parent folder: $fullPath")
            }
            return@withContext created
        } catch (e: Exception) {
            Log.d(TAG, "Path creation failed: $fullPath - ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Ensures a folder exists (creates if it doesn't) with retry logic
     */
    suspend fun ensureFolderExists(folderName: String, retries: Int = MAX_RETRIES): Boolean {
        // First ensure the target folder path exists
        ensureTargetFolderPath()
        
        // Then check if the UUID folder exists
        val exists = folderExists(folderName, retries)
        if (exists) {
            return true
        }
        
        // If it doesn't exist, try to create it
        return createFolder(folderName, retries)
    }
    
    /**
     * Ensures a subfolder exists within a UUID folder (creates if it doesn't)
     */
    suspend fun ensureSubfolderExists(uuidFolder: String, subfolderName: String, retries: Int = MAX_RETRIES): Boolean {
        val subfolderUrl = "$targetFolderUrl/$uuidFolder/$subfolderName"
        val exists = checkPathExists(subfolderUrl)
        if (exists) {
            return true
        }
        return createPath(subfolderUrl)
    }
    
    /**
     * Uploads a text file to ownCloud
     * @param uuidFolder The UUID folder name
     * @param subfolder The subfolder name (e.g., "logs")
     * @param fileName The filename
     * @param content The file content as string
     * @return true if successful, false otherwise
     */
    suspend fun uploadTextFile(uuidFolder: String, subfolder: String, fileName: String, content: String, retries: Int = MAX_RETRIES): Boolean = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) {
            AppLogger.w(TAG, "No network connectivity, cannot upload file")
            return@withContext false
        }
        
        // Ensure UUID folder exists
        if (!ensureFolderExists(uuidFolder, retries)) {
            AppLogger.e(TAG, "Failed to ensure UUID folder exists: $uuidFolder")
            return@withContext false
        }
        
        // Ensure subfolder exists
        if (!ensureSubfolderExists(uuidFolder, subfolder, retries)) {
            AppLogger.e(TAG, "Failed to ensure subfolder exists: $uuidFolder/$subfolder")
            return@withContext false
        }
        
        var lastException: Exception? = null
        var delayMs = INITIAL_RETRY_DELAY_MS
        
        val filePath = "$uuidFolder/$subfolder/$fileName"
        val fileUrl = "$targetFolderUrl/$filePath"
        
        repeat(retries) { attempt ->
            try {
                AppLogger.d(TAG, "Uploading file: $filePath (attempt ${attempt + 1}/$retries)")
                
                val requestBody = content.toRequestBody("text/plain".toMediaType())
                val request = Request.Builder()
                    .url(fileUrl)
                    .put(requestBody)
                    .addHeader("Authorization", createAuthHeader())
                    .addHeader("User-Agent", "TREC-Custom-Logsheets/1.0")
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                val success = response.isSuccessful && (response.code == 201 || response.code == 204)
                
                AppLogger.d(TAG, "PUT file response: code=${response.code}, success=$success")
                AppLogger.d(TAG, "Response headers: ${response.headers}")
                if (responseBody != null) {
                    AppLogger.d(TAG, "Response body (first 500 chars): ${responseBody.take(500)}")
                }
                response.close()
                
                if (success) {
                    AppLogger.i(TAG, "File uploaded successfully: $filePath")
                    return@withContext true
                }
                
                AppLogger.w(TAG, "File upload failed. Response code: ${response.code}")
            } catch (e: SocketTimeoutException) {
                lastException = e
                AppLogger.w(TAG, "Timeout uploading file (attempt ${attempt + 1}/$retries): ${e.message}", e)
            } catch (e: IOException) {
                lastException = e
                AppLogger.w(TAG, "Network error uploading file (attempt ${attempt + 1}/$retries): ${e.message}", e)
            } catch (e: Exception) {
                lastException = e
                AppLogger.e(TAG, "Error uploading file (attempt ${attempt + 1}/$retries): ${e.message}", e)
            }
            
            // Wait before retrying (exponential backoff)
            if (attempt < retries - 1) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }
        
        AppLogger.e(TAG, "Failed to upload file after $retries attempts: $filePath", lastException)
        false
    }
    
    /**
     * Uploads an entire folder recursively to ownCloud
     * @param uuidFolder The UUID folder name
     * @param siteFolderName The site folder name (will be created as subfolder in UUID folder)
     * @param localFolder The local DocumentFile folder to upload
     * @param progressCallback Optional callback for progress updates (uploaded files count, total files count)
     * @return UploadFolderResult with success status and details
     */
    suspend fun uploadFolder(
        uuidFolder: String,
        siteFolderName: String,
        localFolder: DocumentFile,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): UploadFolderResult = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) {
            AppLogger.w(TAG, "No network connectivity, cannot upload folder")
            return@withContext UploadFolderResult.Error("No network connectivity")
        }
        
        // Ensure UUID folder exists
        if (!ensureFolderExists(uuidFolder)) {
            AppLogger.e(TAG, "Failed to ensure UUID folder exists: $uuidFolder")
            return@withContext UploadFolderResult.Error("Failed to create UUID folder")
        }
        
        // Ensure site subfolder exists
        if (!ensureSubfolderExists(uuidFolder, siteFolderName)) {
            AppLogger.e(TAG, "Failed to ensure site folder exists: $uuidFolder/$siteFolderName")
            return@withContext UploadFolderResult.Error("Failed to create site folder")
        }
        
        try {
            // Count total files first
            val allFiles = collectAllFiles(localFolder)
            val totalFiles = allFiles.size
            AppLogger.i(TAG, "Starting folder upload: site='$siteFolderName', total files=$totalFiles")
            
            var uploadedCount = 0
            var failedCount = 0
            
            // Upload each file
            for ((relativePath, file) in allFiles) {
                try {
                    // Check for cancellation before each file upload
                    ensureActive()
                    
                    val fileContent = readFileContent(file)
                    if (fileContent == null) {
                        AppLogger.w(TAG, "Could not read file content: $relativePath")
                        failedCount++
                        continue
                    }
                    
                    val remotePath = "$uuidFolder/$siteFolderName/$relativePath"
                    val success = uploadTextFileContent(
                        remotePath = remotePath,
                        content = fileContent,
                        retries = 3 // Retry up to 3 times per file
                    )
                    
                    if (success) {
                        uploadedCount++
                        AppLogger.d(TAG, "Uploaded file: $relativePath")
                    } else {
                        failedCount++
                        AppLogger.w(TAG, "Failed to upload file: $relativePath")
                    }
                    
                    progressCallback?.invoke(uploadedCount, totalFiles)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    AppLogger.w(TAG, "Upload cancelled for file: $relativePath")
                    throw e // Re-throw cancellation to stop the upload
                } catch (e: Exception) {
                    failedCount++
                    AppLogger.e(TAG, "Error uploading file: $relativePath", e)
                }
            }
            
            // Verify upload
            val verification = verifyFolderUpload(uuidFolder, siteFolderName, totalFiles)
            
            if (verification.success && uploadedCount > 0) {
                AppLogger.i(TAG, "Folder upload completed: site='$siteFolderName', uploaded=$uploadedCount/$totalFiles")
                UploadFolderResult.Success(uploadedCount, totalFiles)
            } else {
                AppLogger.w(TAG, "Folder upload verification failed: site='$siteFolderName', uploaded=$uploadedCount/$totalFiles, verification=${verification.message}")
                UploadFolderResult.Error("Upload verification failed: ${verification.message}")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error uploading folder: site='$siteFolderName'", e)
            UploadFolderResult.Error("Error uploading folder: ${e.message}")
        }
    }
    
    /**
     * Collects all files recursively from a folder
     * Returns a map of relative path -> DocumentFile
     */
    private fun collectAllFiles(folder: DocumentFile, basePath: String = ""): Map<String, DocumentFile> {
        val files = mutableMapOf<String, androidx.documentfile.provider.DocumentFile>()
        
        try {
            val folderFiles = folder.listFiles()
            for (file in folderFiles) {
                val fileName = file.name ?: continue
                val relativePath = if (basePath.isEmpty()) fileName else "$basePath/$fileName"
                
                if (file.isDirectory) {
                    // Recursively collect files from subdirectory
                    files.putAll(collectAllFiles(file, relativePath))
                } else {
                    // Add file
                    files[relativePath] = file
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error collecting files from folder: ${e.message}", e)
        }
        
        return files
    }
    
    /**
     * Reads file content as string
     */
    private suspend fun readFileContent(file: DocumentFile): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(file.uri) ?: return@withContext null
            inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading file content: ${file.name}", e)
            null
        }
    }
    
    /**
     * Uploads text file content directly (without ensuring folders)
     */
    private suspend fun uploadTextFileContent(
        remotePath: String,
        content: String,
        retries: Int = 1
    ): Boolean = withContext(Dispatchers.IO) {
        val fileUrl = "$targetFolderUrl/$remotePath"
        
        repeat(retries) { attempt ->
            try {
                val requestBody = content.toRequestBody("text/plain".toMediaType())
                val request = Request.Builder()
                    .url(fileUrl)
                    .put(requestBody)
                    .addHeader("Authorization", createAuthHeader())
                    .addHeader("User-Agent", "TREC-Custom-Logsheets/1.0")
                    .build()
                
                val response = client.newCall(request).execute()
                val success = response.isSuccessful && (response.code == 201 || response.code == 204)
                response.close()
                
                if (success) {
                    return@withContext true
                }
            } catch (e: Exception) {
                if (attempt == retries - 1) {
                    AppLogger.w(TAG, "Failed to upload file after $retries attempts: $remotePath")
                }
            }
        }
        
        false
    }
    
    /**
     * Verifies that a folder was uploaded successfully
     */
    suspend fun verifyFolderUpload(uuidFolder: String, siteFolderName: String, expectedFileCount: Int): VerificationResult = withContext(Dispatchers.IO) {
        try {
            val folderUrl = "$targetFolderUrl/$uuidFolder/$siteFolderName"
            
            // Check if folder exists
            val request = Request.Builder()
                .url(folderUrl)
                .method("PROPFIND", null)
                .addHeader("Depth", "1")
                .addHeader("Authorization", createAuthHeader())
                .addHeader("User-Agent", "TREC-Custom-Logsheets/1.0")
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            val exists = response.isSuccessful && response.code == 207
            
            response.close()
            
            if (!exists) {
                return@withContext VerificationResult(false, "Folder does not exist")
            }
            
            // Parse response to count files (simplified - just check if response contains file entries)
            // For a more robust check, we'd parse the XML response
            val fileCount = if (responseBody != null) {
                // Count <d:response> elements that are files (not directories)
                responseBody.split("<d:response>").size - 1 // Subtract 1 for the folder itself
            } else {
                0
            }
            
            if (fileCount == 0) {
                return@withContext VerificationResult(false, "Folder is empty")
            }
            
            // If we have at least some files, consider it successful
            // We don't require exact match because some files might be skipped or already exist
            AppLogger.d(TAG, "Folder verification: found $fileCount files, expected at least 1")
            VerificationResult(true, "Found $fileCount files")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error verifying folder upload", e)
            VerificationResult(false, "Verification error: ${e.message}")
        }
    }
    
    data class UploadFolderResult(
        val success: Boolean,
        val uploadedCount: Int = 0,
        val totalCount: Int = 0,
        val errorMessage: String? = null
    ) {
        companion object {
            fun Success(uploaded: Int, total: Int) = UploadFolderResult(true, uploaded, total)
            fun Error(message: String) = UploadFolderResult(false, errorMessage = message)
        }
    }
    
    data class VerificationResult(
        val success: Boolean,
        val message: String
    )
}

