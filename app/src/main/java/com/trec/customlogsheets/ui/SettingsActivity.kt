package com.trec.customlogsheets.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import android.widget.TextView
import android.widget.AdapterView
import com.google.android.material.button.MaterialButton
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.SettingsPreferences

class SettingsActivity : AppCompatActivity() {
    private lateinit var settingsPreferences: SettingsPreferences
    private lateinit var folderPathText: TextView
    private lateinit var selectFolderButton: MaterialButton
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
            finish()
        }
    }
    
    private fun setupViews() {
        folderPathText = findViewById(R.id.textFolderPath)
        selectFolderButton = findViewById(R.id.buttonSelectFolder)
        
        // Setup team spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, teams)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        teamSpinner = findViewById(R.id.spinnerTeam)
        teamSpinner.adapter = adapter
        
        selectFolderButton.setOnClickListener {
            openFolderPicker()
        }
        
        teamSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedTeam = teams[position]
                settingsPreferences.setSamplingTeam(selectedTeam)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun loadCurrentSettings() {
        val folderUri = settingsPreferences.getFolderUri()
        if (folderUri.isNotEmpty()) {
            try {
                val uri = Uri.parse(folderUri)
                val documentFile = DocumentFile.fromTreeUri(this, uri)
                val path = documentFile?.name ?: "Unknown"
                folderPathText.text = "Selected: $path"
            } catch (e: Exception) {
                folderPathText.text = "Error loading path"
            }
        } else {
            folderPathText.text = "No folder selected"
        }
        
        val currentTeam = settingsPreferences.getSamplingTeam()
        val teamIndex = teams.indexOf(currentTeam)
        if (teamIndex >= 0) {
            teamSpinner.setSelection(teamIndex)
        }
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
                
                // Get folder name and display it
                val documentFile = DocumentFile.fromTreeUri(this, uri)
                val folderName = documentFile?.name ?: "Unknown"
                folderPathText.text = "Selected: $folderName"
                
                // Also save a human-readable path if available
                val path = documentFile?.uri?.path ?: ""
                settingsPreferences.setSubmissionPath(path)
                
                Toast.makeText(this, "Folder selected: $folderName", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

