package com.trec.customlogsheets.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import android.widget.TextView
import android.widget.AdapterView
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.trec.customlogsheets.MainActivity
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.FolderStructureHelper
import com.trec.customlogsheets.data.SettingsPreferences
import com.trec.customlogsheets.data.FormConfigLoader
import com.trec.customlogsheets.data.PredefinedForms
import com.trec.customlogsheets.data.LogsheetDownloader
import com.trec.customlogsheets.util.AppLogger
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var settingsPreferences: SettingsPreferences
    private lateinit var folderPathText: TextView
    private lateinit var folderPathLayout: LinearLayout
    private lateinit var iconFolderSelected: ImageView
    private lateinit var selectFolderButton: MaterialButton
    private lateinit var teamSpinner: Spinner
    private lateinit var subteamSpinner: Spinner
    private lateinit var subteamLabel: TextView
    private lateinit var buttonOfflineMaps: MaterialButton
    private lateinit var buttonViewLogs: MaterialButton
    private lateinit var buttonCopyUuid: MaterialButton
    private lateinit var buttonUpdateLogsheets: MaterialButton
    private lateinit var textLogsheetsStatus: TextView
    private lateinit var progressBarDownload: android.widget.ProgressBar
    private lateinit var textDownloadProgress: TextView
    private val teams = arrayOf("LSI", "AML")
    private var currentSubteams: List<String> = emptyList()
    private lateinit var subteamAdapter: ArrayAdapter<String>
    
    companion object {
        private const val REQUEST_CODE_OPEN_FOLDER = 1001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        settingsPreferences = SettingsPreferences(this)
        
        setupToolbar()
        setupViews()
        loadCurrentSettings()
    }
    
    private fun setupToolbar() {
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"
        toolbar.setNavigationOnClickListener {
            navigateToHome()
        }
    }
    
    private fun setupViews() {
        folderPathText = findViewById(R.id.textFolderPath)
        folderPathLayout = findViewById(R.id.layoutFolderPath)
        iconFolderSelected = findViewById(R.id.iconFolderSelected)
        selectFolderButton = findViewById(R.id.buttonSelectFolder)
        buttonOfflineMaps = findViewById(R.id.buttonOfflineMaps)
        
        // Setup team spinner
        val teamAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, teams)
        teamAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        teamSpinner = findViewById(R.id.spinnerTeam)
        teamSpinner.adapter = teamAdapter
        
        // Setup subteam spinner (will be populated dynamically)
        subteamAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf<String>())
        subteamAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        subteamSpinner = findViewById(R.id.spinnerSubteam)
        subteamSpinner.adapter = subteamAdapter
        subteamLabel = findViewById(R.id.textSubteamLabel)
        
        selectFolderButton.setOnClickListener {
            openFolderPicker()
        }
        
        buttonOfflineMaps.setOnClickListener {
            val intent = Intent(this, OfflineMapsActivity::class.java)
            startActivity(intent)
        }
        
        buttonViewLogs = findViewById(R.id.buttonViewLogs)
        buttonViewLogs.setOnClickListener {
            val intent = Intent(this, LogsActivity::class.java)
            startActivity(intent)
        }
        
        buttonCopyUuid = findViewById(R.id.buttonCopyUuid)
        buttonCopyUuid.setOnClickListener {
            val appUuid = settingsPreferences.getAppUuid()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("App UUID", appUuid)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "UUID copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        
        buttonUpdateLogsheets = findViewById(R.id.buttonUpdateLogsheets)
        textLogsheetsStatus = findViewById(R.id.textLogsheetsStatus)
        progressBarDownload = findViewById(R.id.progressBarDownload)
        textDownloadProgress = findViewById(R.id.textDownloadProgress)
        buttonUpdateLogsheets.setOnClickListener {
            updateLogsheets()
        }
        
        teamSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedTeam = teams[position]
                val previousTeam = settingsPreferences.getSamplingTeam()
                val wasTeamChanged = previousTeam != selectedTeam
                settingsPreferences.setSamplingTeam(selectedTeam)
                
                // Clear subteam when switching teams (old subteam might not be valid for new team)
                if (wasTeamChanged) {
                    settingsPreferences.setSamplingSubteam("")
                }
                
                // Clear form config cache when team changes
                FormConfigLoader.clearCache()
                PredefinedForms.clearCache()
                
                // Discover and populate subteams for the selected team
                // If team was changed and no subteam is set, automatically select the first one
                updateSubteamsForTeam(selectedTeam) {
                    if (wasTeamChanged && settingsPreferences.getSamplingSubteam().isEmpty() && currentSubteams.isNotEmpty()) {
                        // Automatically select the first subteam
                        val firstSubteam = currentSubteams[0]
                        settingsPreferences.setSamplingSubteam(firstSubteam)
                        // Set selection without triggering onItemSelected (by temporarily removing listener)
                        val subteamListener = subteamSpinner.onItemSelectedListener
                        subteamSpinner.onItemSelectedListener = null
                        subteamSpinner.setSelection(0)
                        subteamSpinner.onItemSelectedListener = subteamListener
                        // Clear form config cache since subteam was auto-selected
                        FormConfigLoader.clearCache()
                        PredefinedForms.clearCache()
                    }
                    
                    // If folder is already selected, verify folder structure exists (don't recreate)
                    val folderUri = settingsPreferences.getFolderUri()
                    if (folderUri.isNotEmpty()) {
                        try {
                            val uri = folderUri.toUri()
                            verifyFolderStructure(uri)
                        } catch (e: Exception) {
                            android.util.Log.w("SettingsActivity", "Error verifying folder structure after team change: ${e.message}")
                        }
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        subteamSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position < currentSubteams.size) {
                    val selectedSubteam = currentSubteams[position]
                settingsPreferences.setSamplingSubteam(selectedSubteam)
                
                // Clear form config cache when subteam changes
                FormConfigLoader.clearCache()
                PredefinedForms.clearCache()
                    
                    // If folder is already selected, verify folder structure exists (don't recreate)
                    val folderUri = settingsPreferences.getFolderUri()
                    if (folderUri.isNotEmpty()) {
                        try {
                            val uri = folderUri.toUri()
                            verifyFolderStructure(uri)
                        } catch (e: Exception) {
                            android.util.Log.w("SettingsActivity", "Error verifying folder structure after subteam change: ${e.message}")
                        }
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun navigateToHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }
    
    /**
     * Updates the subteam spinner with available subteams for the given team
     * Shows/hides the spinner based on whether subteams are available
     * @param onComplete Optional callback to execute after subteams are loaded (e.g., to set selection)
     */
    private fun updateSubteamsForTeam(team: String, onComplete: (() -> Unit)? = null) {
        lifecycleScope.launch {
            try {
                val downloader = LogsheetDownloader(this@SettingsActivity)
                val subteams = downloader.getAvailableSubteams(team)
                currentSubteams = subteams
                
                // Update adapter with new subteams
                subteamAdapter.clear()
                subteamAdapter.addAll(subteams)
                subteamAdapter.notifyDataSetChanged()
                
                // Show subteam spinner if there are subteams available
                if (subteams.isNotEmpty()) {
                    subteamSpinner.visibility = android.view.View.VISIBLE
                    subteamLabel.visibility = android.view.View.VISIBLE
                } else {
                    subteamSpinner.visibility = android.view.View.GONE
                    subteamLabel.visibility = android.view.View.GONE
                }
                
                // Execute callback if provided
                onComplete?.invoke()
            } catch (e: Exception) {
                AppLogger.e("SettingsActivity", "Error loading subteams: ${e.message}", e)
                // Hide spinner on error
                subteamSpinner.visibility = android.view.View.GONE
                subteamLabel.visibility = android.view.View.GONE
            }
        }
    }
    
    private fun loadCurrentSettings() {
        val folderUri = settingsPreferences.getFolderUri()
        if (folderUri.isNotEmpty()) {
            try {
                val uri = folderUri.toUri()
                val documentFile = DocumentFile.fromTreeUri(this, uri)
                val fullPath = getFullPath(uri, documentFile)
                folderPathText.text = fullPath
                // Make it visually obvious that folder is selected
                folderPathLayout.setBackgroundColor(0xFFE8F5E9.toInt()) // Light green background
                iconFolderSelected.visibility = android.view.View.VISIBLE
                iconFolderSelected.setImageResource(android.R.drawable.checkbox_on_background)
            } catch (e: Exception) {
                folderPathText.text = getString(R.string.error_loading_path, e.message ?: "")
                resetFolderVisualState()
            }
        } else {
            folderPathText.text = getString(R.string.no_folder_selected)
            resetFolderVisualState()
        }
        
        val currentTeam = settingsPreferences.getSamplingTeam()
        if (currentTeam.isNotEmpty()) {
            val teamIndex = teams.indexOf(currentTeam)
            if (teamIndex >= 0) {
                // Set selection without triggering onItemSelected (by temporarily removing listener)
                val teamListener = teamSpinner.onItemSelectedListener
                teamSpinner.onItemSelectedListener = null
                teamSpinner.setSelection(teamIndex)
                teamSpinner.onItemSelectedListener = teamListener
                
                // Update subteams for the current team and show spinner
                // Set selection after subteams are loaded
                updateSubteamsForTeam(currentTeam) {
                    // Load current subteam after subteams are populated
                    val currentSubteam = settingsPreferences.getSamplingSubteam()
                    if (currentSubteam.isNotEmpty() && currentSubteams.contains(currentSubteam)) {
                        val subteamIndex = currentSubteams.indexOf(currentSubteam)
                        if (subteamIndex >= 0) {
                            // Set selection without triggering onItemSelected (by temporarily removing listener)
                            val subteamListener = subteamSpinner.onItemSelectedListener
                            subteamSpinner.onItemSelectedListener = null
                            subteamSpinner.setSelection(subteamIndex)
                            subteamSpinner.onItemSelectedListener = subteamListener
                        }
                    }
                }
            }
        } else {
            // No team selected yet - hide subteam
            subteamSpinner.visibility = android.view.View.GONE
            subteamLabel.visibility = android.view.View.GONE
        }
        
        // Load and display app UUID
        val appUuid = settingsPreferences.getAppUuid()
        val uuidText = findViewById<TextView>(R.id.textAppUuid)
        uuidText.text = appUuid
        
        // Load and display logsheets status
        updateLogsheetsStatus()
    }
    
    private fun updateLogsheetsStatus() {
        val downloader = LogsheetDownloader(this)
        val hasDownloaded = downloader.hasDownloadedLogsheets()
        val isDownloaded = settingsPreferences.areLogsheetsDownloaded()
        
        if (hasDownloaded && isDownloaded) {
            textLogsheetsStatus.text = getString(R.string.status_last_download_completed)
            textLogsheetsStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else if (hasDownloaded && !isDownloaded) {
            textLogsheetsStatus.text = getString(R.string.status_partially_downloaded)
            textLogsheetsStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
        } else {
            textLogsheetsStatus.text = getString(R.string.status_not_downloaded)
            textLogsheetsStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }
    
    private fun resetFolderVisualState() {
        folderPathLayout.setBackgroundColor(0xFFF5F5F5.toInt()) // Gray background
        iconFolderSelected.visibility = android.view.View.GONE
    }
    
    private fun getFullPath(uri: Uri, documentFile: DocumentFile?): String {
        val pathBuilder = StringBuilder()
        
        // Try to get the display name
        val displayName = documentFile?.name ?: "Unknown"
        pathBuilder.append(displayName)
        
        // Try to get additional path information from URI
        try {
            // For document tree URIs, try to extract path segments
            val uriString = uri.toString()
            if (uriString.contains("tree")) {
                // Extract the document ID if available
                val documentId = DocumentsContract.getTreeDocumentId(uri)
                if (documentId.isNotEmpty() && documentId != displayName) {
                    pathBuilder.append(" (ID: $documentId)")
                }
            }
            
            // Add the full URI for reference
            pathBuilder.append("\nURI: ${uri.toString().take(80)}...")
        } catch (e: Exception) {
            // If we can't get more info, just use the display name
        }
        
        return pathBuilder.toString()
    }
    
    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            
            // Try to set initial directory to a previously selected folder (Android 11+)
            // This helps the picker open to a familiar location
            val existingFolderUri = settingsPreferences.getFolderUri()
            if (existingFolderUri.isNotEmpty()) {
                try {
                    val uri = Uri.parse(existingFolderUri)
                    // Verify the URI is still accessible
                    val docFile = DocumentFile.fromTreeUri(this@SettingsActivity, uri)
                    if (docFile != null && docFile.exists()) {
                        // Use the existing folder as initial location
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("SettingsActivity", "Could not use existing folder URI: ${e.message}")
                }
            }
        }
        
        try {
            // Show helpful message to guide users
            Toast.makeText(this, "Navigate to a writable folder (e.g., Downloads or Documents)", Toast.LENGTH_LONG).show()
            
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CODE_OPEN_FOLDER)
        } catch (e: Exception) {
            android.util.Log.e("SettingsActivity", "Error opening folder picker: ${e.message}", e)
            Toast.makeText(this, "Error opening folder picker. Please try again.", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_OPEN_FOLDER && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                // Verify the selected folder is writable
                val docFile = DocumentFile.fromTreeUri(this, uri)
                if (docFile == null || !docFile.exists()) {
                    Toast.makeText(this, "Error: Selected folder is not accessible", Toast.LENGTH_LONG).show()
                    return
                }
                
                if (!docFile.canWrite()) {
                    Toast.makeText(this, "Error: Selected folder is not writable. Please choose a different folder (e.g., Downloads or Documents).", Toast.LENGTH_LONG).show()
                    return
                }
                
                // Take persistable URI permission
                try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                } catch (e: SecurityException) {
                    Toast.makeText(this, "Error: Could not get write permission for selected folder. Please try again.", Toast.LENGTH_LONG).show()
                    android.util.Log.e("SettingsActivity", "Security exception taking persistable permission: ${e.message}", e)
                    return
                }
                
                // Create the folder structure
                createFolderStructure(uri)
            }
        } else if (requestCode == REQUEST_CODE_OPEN_FOLDER && resultCode != RESULT_OK) {
            // User cancelled or there was an error
            android.util.Log.d("SettingsActivity", "Folder picker cancelled or failed with result code: $resultCode")
        }
    }
    
    private fun createFolderStructure(baseUri: Uri) {
        android.util.Log.i("SettingsActivity", "createFolderStructure called with URI: $baseUri")
        try {
            val folderHelper = FolderStructureHelper(this)
            val team = settingsPreferences.getSamplingTeam()
            val subteam = settingsPreferences.getSamplingSubteam()
            
            android.util.Log.d("SettingsActivity", "Current team: '$team', subteam: '$subteam'")
            
            // Check if the URI already points to TREC_logsheets folder
            val docFile = DocumentFile.fromTreeUri(this, baseUri)
            if (docFile == null) {
                android.util.Log.e("SettingsActivity", "Could not create DocumentFile from URI: $baseUri")
                Toast.makeText(this, "Error: Could not access selected folder", Toast.LENGTH_LONG).show()
                return
            }
            
            val folderName = docFile.name
            val isTrecFolder = folderName == FolderStructureHelper.PARENT_FOLDER_NAME
            
            android.util.Log.d("SettingsActivity", "createFolderStructure: baseUri=$baseUri, folder name='$folderName', isTrecFolder=$isTrecFolder")
            
            // ALWAYS create TREC_logsheets folder, even if team/subteam are not set yet
            // The deeper structure (team/subteam) will be created later when they are configured
            val trecFolder = if (isTrecFolder) {
                // URI already points to TREC_logsheets, use it directly - don't recreate
                android.util.Log.i("SettingsActivity", "URI already points to TREC_logsheets folder, using existing folder")
                docFile
            } else {
                // URI points to parent folder, create TREC_logsheets inside it (only if it doesn't exist)
                android.util.Log.i("SettingsActivity", "URI points to parent folder '$folderName', creating TREC_logsheets inside it")
                val createdTrecFolder = folderHelper.ensureFolderStructure(baseUri, settingsPreferences)
                if (createdTrecFolder == null) {
                    android.util.Log.e("SettingsActivity", "Failed to create TREC_logsheets folder in '$folderName'")
                    Toast.makeText(this, "Error: Could not create TREC_logsheets folder. Please try selecting the folder again.", Toast.LENGTH_LONG).show()
                    return
                }
                android.util.Log.i("SettingsActivity", "TREC_logsheets folder created successfully: ${createdTrecFolder.uri}")
                // Verify the created folder is actually TREC_logsheets and is accessible
                if (createdTrecFolder.name != FolderStructureHelper.PARENT_FOLDER_NAME) {
                    android.util.Log.e("SettingsActivity", "Created folder has wrong name: '${createdTrecFolder.name}', expected '${FolderStructureHelper.PARENT_FOLDER_NAME}'")
                    Toast.makeText(this, "Error: Created folder has unexpected name: '${createdTrecFolder.name}'. Please try again.", Toast.LENGTH_LONG).show()
                    return
                }
                if (!createdTrecFolder.exists() || !createdTrecFolder.canWrite()) {
                    android.util.Log.e("SettingsActivity", "Created TREC_logsheets folder exists but is not accessible or writable")
                    Toast.makeText(this, "Error: TREC_logsheets folder is not accessible. Please try selecting the folder again.", Toast.LENGTH_LONG).show()
                    return
                }
                createdTrecFolder
            }
            
            // Verify we have the TREC_logsheets folder
            if (trecFolder.name != FolderStructureHelper.PARENT_FOLDER_NAME) {
                android.util.Log.w("SettingsActivity", "Warning: Folder name is '${trecFolder.name}', expected '${FolderStructureHelper.PARENT_FOLDER_NAME}'")
                Toast.makeText(this, "Warning: Folder structure may be incorrect", Toast.LENGTH_LONG).show()
            }
            
            android.util.Log.i("SettingsActivity", "TREC_logsheets folder ready: ${trecFolder.uri}")
            
            // Only create deeper structure (team/subteam) if team and subteam are set
            if (team.isNotEmpty() && subteam.isNotEmpty()) {
                android.util.Log.d("SettingsActivity", "Team and subteam are set, creating deeper folder structure...")
                // Ensure subfolders exist (team/subteam/ongoing/finished/deleted)
                // This will create team/subteam folders inside TREC_logsheets
                if (!folderHelper.ensureSubfoldersExist(settingsPreferences)) {
                    android.util.Log.w("SettingsActivity", "Could not ensure all subfolders exist")
                    Toast.makeText(this, "Warning: Could not create all subfolders", Toast.LENGTH_SHORT).show()
                } else {
                    android.util.Log.i("SettingsActivity", "Deeper folder structure created successfully")
                }
            } else {
                android.util.Log.i("SettingsActivity", "Team or subteam not set yet - TREC_logsheets folder created, deeper structure will be created later")
            }
            
            // Get the URI for the TREC_logsheets folder directly from the DocumentFile
            val trecFolderUri = trecFolder.uri
            
            // Also take persistable permission for the TREC_logsheets folder
            try {
                contentResolver.takePersistableUriPermission(
                    trecFolderUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                android.util.Log.w("SettingsActivity", "Could not take persistable permission for TREC_logsheets: ${e.message}")
            }
            
            // Save the tree URI - this should point to TREC_logsheets folder
            settingsPreferences.setFolderUri(trecFolderUri.toString())
            
            // Verify we're saving the correct URI
            android.util.Log.d("SettingsActivity", "Saved TREC_logsheets URI: $trecFolderUri")
            android.util.Log.d("SettingsActivity", "TREC_logsheets folder name: ${trecFolder.name}")
            
            // Display the path with structure info (all teams use subteam structure now)
            val fullPath = getFullPath(trecFolderUri, trecFolder)
            val structureInfo = "\n\nStructure:\n• TREC_logsheets/\n  - $team/\n    - $subteam/\n      - ongoing/\n      - finished/\n      - deleted/"
            folderPathText.text = fullPath + structureInfo
            
            // Make it visually obvious that folder is selected
            folderPathLayout.setBackgroundColor(0xFFE8F5E9.toInt()) // Light green background
            iconFolderSelected.visibility = android.view.View.VISIBLE
            iconFolderSelected.setImageResource(android.R.drawable.checkbox_on_background)
            
            // Also save a human-readable path if available
            val path = trecFolderUri.path ?: ""
            settingsPreferences.setSubmissionPath(path)
            
            // Verify the saved URI
            android.util.Log.d("SettingsActivity", "Verifying saved URI: ${settingsPreferences.getFolderUri()}")
            
            Toast.makeText(this, "Folder structure ready", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error creating folder structure: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Verifies that the folder structure exists without recreating it.
     * This is called when team/subteam changes to ensure structure is still valid.
     * Only ensures subfolders exist, doesn't recreate TREC_logsheets or team/subteam folders.
     */
    private fun verifyFolderStructure(uri: Uri) {
        try {
            val folderHelper = FolderStructureHelper(this)
            val docFile = DocumentFile.fromTreeUri(this, uri)
            
            // Check if URI points to TREC_logsheets folder
            if (docFile?.name == FolderStructureHelper.PARENT_FOLDER_NAME) {
                // URI points to TREC_logsheets, just verify subfolders exist (team/subteam/ongoing/finished/deleted)
                // This won't recreate anything, just ensures the subfolders exist
                if (!folderHelper.ensureSubfoldersExist(settingsPreferences)) {
                    android.util.Log.w("SettingsActivity", "Some subfolders could not be verified/created")
                } else {
                    android.util.Log.d("SettingsActivity", "Folder structure verified successfully")
                }
            } else {
                // URI points to parent folder - structure should already exist
                // Don't do anything, just log
                android.util.Log.d("SettingsActivity", "URI points to parent folder, structure should already exist")
            }
        } catch (e: Exception) {
            android.util.Log.w("SettingsActivity", "Error verifying folder structure: ${e.message}")
        }
    }
    
    private fun updateLogsheets() {
        buttonUpdateLogsheets.isEnabled = false
        buttonUpdateLogsheets.text = getString(R.string.downloading)
        textLogsheetsStatus.text = getString(R.string.status_downloading)
        textLogsheetsStatus.setTextColor(getColor(android.R.color.holo_blue_dark))
        progressBarDownload.visibility = android.view.View.VISIBLE
        progressBarDownload.progress = 0
        textDownloadProgress.visibility = android.view.View.VISIBLE
        textDownloadProgress.text = getString(R.string.initializing)
        
        val progressCallback = object : LogsheetDownloader.DownloadProgressCallback {
            override fun onPhaseStarted(phase: String) {
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    textDownloadProgress.text = getString(R.string.phase_label, phase)
                    progressBarDownload.progress = 0
                }
            }
            
            override fun onFileProgress(current: Int, total: Int, fileName: String) {
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    val progress = if (total > 0) {
                        (current * 100) / total
                    } else {
                        100 // If no files to download, show complete
                    }
                    progressBarDownload.progress = progress
                    if (total > 0) {
                        textDownloadProgress.text = getString(R.string.downloading_file, fileName, current, total)
                    } else {
                        textDownloadProgress.text = getString(R.string.no_new_files_to_download)
                    }
                }
            }
            
            override fun onPhaseCompleted(phase: String, downloaded: Int, failed: Int) {
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    textDownloadProgress.text = getString(R.string.phase_downloaded_failed, phase, downloaded, failed)
                }
            }
        }
        
        lifecycleScope.launch {
            try {
                val downloader = LogsheetDownloader(this@SettingsActivity)
                val success = downloader.downloadAll(progressCallback)
                if (isDestroyed || isFinishing) return@launch
                if (success) {
                    settingsPreferences.setLogsheetsDownloaded(true)
                    // Clear form config cache to reload from downloaded files
                    FormConfigLoader.clearCache()
                    PredefinedForms.clearCache()
                    textLogsheetsStatus.text = getString(R.string.status_last_download_completed)
                    textLogsheetsStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                    Toast.makeText(this@SettingsActivity, getString(R.string.logsheets_updated_success), Toast.LENGTH_SHORT).show()
                } else {
                    textLogsheetsStatus.text = getString(R.string.status_update_failed)
                    textLogsheetsStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    Toast.makeText(this@SettingsActivity, getString(R.string.logsheets_some_failed), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Error updating logsheets: ${e.message}", e)
                if (!isDestroyed && !isFinishing) {
                    textLogsheetsStatus.text = getString(R.string.status_error, e.message?.take(50) ?: "")
                    textLogsheetsStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    Toast.makeText(this@SettingsActivity, getString(R.string.error_updating_logsheets, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            } finally {
                if (!isDestroyed && !isFinishing) {
                    buttonUpdateLogsheets.isEnabled = true
                    buttonUpdateLogsheets.text = getString(R.string.update_logsheets)
                    progressBarDownload.visibility = android.view.View.GONE
                    textDownloadProgress.visibility = android.view.View.GONE
                }
            }
        }
    }
}

