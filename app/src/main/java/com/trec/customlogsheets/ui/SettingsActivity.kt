package com.trec.customlogsheets.ui

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
import android.widget.TextView
import android.widget.AdapterView
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.trec.customlogsheets.MainActivity
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.SettingsPreferences

class SettingsActivity : AppCompatActivity() {
    private lateinit var settingsPreferences: SettingsPreferences
    private lateinit var folderPathText: TextView
    private lateinit var folderPathLayout: LinearLayout
    private lateinit var iconFolderSelected: ImageView
    private lateinit var selectFolderButton: MaterialButton
    private lateinit var homeButton: MaterialButton
    private lateinit var teamSpinner: Spinner
    private val teams = arrayOf(SettingsPreferences.DEFAULT_TEAM)
    
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
        homeButton = findViewById(R.id.buttonHome)
        
        // Setup team spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, teams)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        teamSpinner = findViewById(R.id.spinnerTeam)
        teamSpinner.adapter = adapter
        
        selectFolderButton.setOnClickListener {
            openFolderPicker()
        }
        
        homeButton.setOnClickListener {
            navigateToHome()
        }
        
        teamSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedTeam = teams[position]
                settingsPreferences.setSamplingTeam(selectedTeam)
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
        val teamIndex = teams.indexOf(currentTeam)
        if (teamIndex >= 0) {
            teamSpinner.setSelection(teamIndex)
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
        }
        startActivityForResult(intent, REQUEST_CODE_OPEN_FOLDER)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_OPEN_FOLDER && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                // Take persistable URI permission
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                
                // Save the URI
                settingsPreferences.setFolderUri(uri.toString())
                
                // Get folder name and display full path
                val documentFile = DocumentFile.fromTreeUri(this, uri)
                val fullPath = getFullPath(uri, documentFile)
                folderPathText.text = fullPath
                
                // Make it visually obvious that folder is selected
                folderPathLayout.setBackgroundColor(0xFFE8F5E9.toInt()) // Light green background
                iconFolderSelected.visibility = android.view.View.VISIBLE
                iconFolderSelected.setImageResource(android.R.drawable.checkbox_on_background)
                
                // Also save a human-readable path if available
                val path = documentFile?.uri?.path ?: ""
                settingsPreferences.setSubmissionPath(path)
                
                val folderName = documentFile?.name ?: "Unknown"
                Toast.makeText(this, "Folder selected: $folderName", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

