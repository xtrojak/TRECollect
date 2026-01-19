package com.trec.customlogsheets.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
                        subteamSpinner.setSelection(0)
                        // Clear form config cache since subteam was auto-selected
                        FormConfigLoader.clearCache()
                        PredefinedForms.clearCache()
                    }
                    
                    // If folder is already selected, try to create folder structure now that team/subteam are set
                    val folderUri = settingsPreferences.getFolderUri()
                    if (folderUri.isNotEmpty()) {
                        try {
                            val uri = Uri.parse(folderUri)
                            createFolderStructure(uri)
                        } catch (e: Exception) {
                            android.util.Log.w("SettingsActivity", "Error creating folder structure after team change: ${e.message}")
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
                    
                    // If folder is already selected, try to create folder structure now that subteam is set
                    val folderUri = settingsPreferences.getFolderUri()
                    if (folderUri.isNotEmpty()) {
                        try {
                            val uri = Uri.parse(folderUri)
                            createFolderStructure(uri)
                        } catch (e: Exception) {
                            android.util.Log.w("SettingsActivity", "Error creating folder structure after subteam change: ${e.message}")
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
                val uri = Uri.parse(folderUri)
                val documentFile = DocumentFile.fromTreeUri(this, uri)
                val fullPath = getFullPath(uri, documentFile)
                folderPathText.text = fullPath
                // Make it visually obvious that folder is selected
                folderPathLayout.setBackgroundColor(0xFFE8F5E9.toInt()) // Light green background
                iconFolderSelected.visibility = android.view.View.VISIBLE
                iconFolderSelected.setImageResource(android.R.drawable.checkbox_on_background)
            } catch (e: Exception) {
                folderPathText.text = "Error loading path: ${e.message}"
                resetFolderVisualState()
            }
        } else {
            folderPathText.text = "No folder selected"
            resetFolderVisualState()
        }
        
        val currentTeam = settingsPreferences.getSamplingTeam()
        if (currentTeam.isNotEmpty()) {
            val teamIndex = teams.indexOf(currentTeam)
            if (teamIndex >= 0) {
                teamSpinner.setSelection(teamIndex)
                
                // Update subteams for the current team and show spinner
                // Set selection after subteams are loaded
                updateSubteamsForTeam(currentTeam) {
                    // Load current subteam after subteams are populated
                    val currentSubteam = settingsPreferences.getSamplingSubteam()
                    if (currentSubteam.isNotEmpty() && currentSubteams.contains(currentSubteam)) {
                        val subteamIndex = currentSubteams.indexOf(currentSubteam)
                        if (subteamIndex >= 0) {
                            subteamSpinner.setSelection(subteamIndex)
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
            textLogsheetsStatus.text = "Status: Up to date"
            textLogsheetsStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else if (hasDownloaded && !isDownloaded) {
            textLogsheetsStatus.text = "Status: Partially downloaded"
            textLogsheetsStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
        } else {
            textLogsheetsStatus.text = "Status: Not downloaded"
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
        try {
            val folderHelper = FolderStructureHelper(this)
            val team = settingsPreferences.getSamplingTeam()
            val subteam = settingsPreferences.getSamplingSubteam()
            
            // Check if team and subteam are set before creating folder structure
            if (team.isEmpty() || subteam.isEmpty()) {
                // Just save the base URI without creating full structure
                // The structure will be created later when team/subteam are configured
                settingsPreferences.setFolderUri(baseUri.toString())
                folderPathText.text = getFullPath(baseUri, DocumentFile.fromTreeUri(this, baseUri))
                folderPathLayout.setBackgroundColor(0xFFE8F5E9.toInt())
                iconFolderSelected.visibility = android.view.View.VISIBLE
                iconFolderSelected.setImageResource(android.R.drawable.checkbox_on_background)
                Toast.makeText(this, "Folder selected. Please configure team and subteam to create folder structure.", Toast.LENGTH_LONG).show()
                return
            }
            
            val trecFolder = folderHelper.ensureFolderStructure(baseUri, settingsPreferences)
            
            if (trecFolder == null) {
                Toast.makeText(this, "Error: Could not create folder structure", Toast.LENGTH_LONG).show()
                return
            }
            
            // Verify we have the TREC_logsheets folder
            if (trecFolder.name != FolderStructureHelper.PARENT_FOLDER_NAME) {
                android.util.Log.w("SettingsActivity", "Warning: Folder name is '${trecFolder.name}', expected '${FolderStructureHelper.PARENT_FOLDER_NAME}'")
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
            val structureInfo = "\n\nStructure created:\n• TREC_logsheets/\n  - $team/\n    - $subteam/\n      - ongoing/\n      - finished/\n      - deleted/"
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
            
            Toast.makeText(this, "Folder structure created successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error creating folder structure: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun updateLogsheets() {
        buttonUpdateLogsheets.isEnabled = false
        buttonUpdateLogsheets.text = "Downloading..."
        textLogsheetsStatus.text = "Status: Downloading..."
        textLogsheetsStatus.setTextColor(getColor(android.R.color.holo_blue_dark))
        
        lifecycleScope.launch {
            try {
                val downloader = LogsheetDownloader(this@SettingsActivity)
                val success = downloader.downloadAll()
                
                if (success) {
                    settingsPreferences.setLogsheetsDownloaded(true)
                    // Clear form config cache to reload from downloaded files
                    FormConfigLoader.clearCache()
                    PredefinedForms.clearCache()
                    textLogsheetsStatus.text = "Status: Up to date"
                    textLogsheetsStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                    Toast.makeText(this@SettingsActivity, "Logsheets updated successfully", Toast.LENGTH_SHORT).show()
                } else {
                    textLogsheetsStatus.text = "Status: Update failed"
                    textLogsheetsStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    Toast.makeText(this@SettingsActivity, "Some logsheets failed to update. Check logs for details.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Error updating logsheets: ${e.message}", e)
                textLogsheetsStatus.text = "Status: Error - ${e.message?.take(50)}"
                textLogsheetsStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                Toast.makeText(this@SettingsActivity, "Error updating logsheets: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                buttonUpdateLogsheets.isEnabled = true
                buttonUpdateLogsheets.text = "Update Logsheets"
            }
        }
    }
}

