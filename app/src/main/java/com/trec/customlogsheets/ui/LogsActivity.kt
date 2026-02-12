package com.trec.customlogsheets.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import androidx.lifecycle.lifecycleScope
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.OwnCloudManager
import com.trec.customlogsheets.data.SettingsPreferences
import com.trec.customlogsheets.util.AppLogger
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class LogsActivity : AppCompatActivity() {
    private lateinit var editTextLogFilter: TextInputEditText
    private lateinit var textLogs: TextView
    private lateinit var textLogCount: TextView
    private lateinit var scrollViewLogs: ScrollView
    private lateinit var buttonClearLogs: MaterialButton
    private lateinit var buttonCopyLogs: MaterialButton
    private lateinit var buttonReport: MaterialButton
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)
        
        setupToolbar()
        setupViews()
        updateLogs()
    }
    
    private fun setupToolbar() {
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }
    
    private fun setupViews() {
        editTextLogFilter = findViewById(R.id.editTextLogFilter)
        textLogs = findViewById(R.id.textLogs)
        textLogCount = findViewById(R.id.textLogCount)
        scrollViewLogs = findViewById(R.id.scrollViewLogs)
        buttonClearLogs = findViewById(R.id.buttonClearLogs)
        buttonCopyLogs = findViewById(R.id.buttonCopyLogs)
        buttonReport = findViewById(R.id.buttonReport)
        
        // Filter text change listener
        editTextLogFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateLogs()
            }
        })
        
        // Clear logs button
        buttonClearLogs.setOnClickListener {
            AppLogger.clearLogs()
            updateLogs()
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }
        
        // Copy logs button
        buttonCopyLogs.setOnClickListener {
            val filter = editTextLogFilter.text?.toString()?.trim()
            val logs = if (filter.isNullOrEmpty()) {
                AppLogger.getAllLogs()
            } else {
                // Filter by tag or search in message (same logic as updateLogs)
                AppLogger.getAllLogs()
                    .split("\n")
                    .filter { line ->
                        line.contains(filter, ignoreCase = true)
                    }
                    .joinToString("\n")
            }
            
            if (logs.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("App Logs", logs)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No logs to copy", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Report button - upload logs to ownCloud
        buttonReport.setOnClickListener {
            uploadLogsReport()
        }
    }
    
    private fun updateLogs() {
        val filter = editTextLogFilter.text?.toString()?.trim()
        val logs = if (filter.isNullOrEmpty()) {
            AppLogger.getRecentLogs(500)
        } else {
            // Filter by tag or search in message
            AppLogger.getAllLogs()
                .split("\n")
                .filter { line ->
                    line.contains(filter, ignoreCase = true)
                }
                .joinToString("\n")
        }
        
        val totalLogCount = AppLogger.getLogCount()
        val filteredCount = if (filter.isNullOrEmpty()) {
            totalLogCount
        } else {
            logs.split("\n").count { it.isNotEmpty() }
        }
        
        textLogCount.text = if (filter.isNullOrEmpty()) {
            getString(R.string.logs_count, totalLogCount)
        } else {
            getString(R.string.logs_count_filtered, filteredCount, totalLogCount, filter)
        }
        
        if (logs.isEmpty()) {
            textLogs.text = if (!filter.isNullOrEmpty()) getString(R.string.no_logs_matching, filter) else getString(R.string.no_logs_available)
        } else {
            textLogs.text = logs
            // Auto-scroll to bottom to show most recent logs
            scrollViewLogs.post {
                scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }
    
    private fun uploadLogsReport() {
        val filter = editTextLogFilter.text?.toString()?.trim()
        val logs = if (filter.isNullOrEmpty()) {
            AppLogger.getAllLogs()
        } else {
            // Filter by tag or search in message (same logic as updateLogs)
            AppLogger.getAllLogs()
                .split("\n")
                .filter { line ->
                    line.contains(filter, ignoreCase = true)
                }
                .joinToString("\n")
        }
        
        if (logs.isEmpty()) {
            Toast.makeText(this, "No logs to upload", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                // Get UUID from settings
                val settingsPreferences = SettingsPreferences(this@LogsActivity)
                val appUuid = settingsPreferences.getAppUuid()
                
                // Generate filename with datetime stamp
                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                val timestamp = dateFormat.format(Date())
                val fileName = "logs_$timestamp.txt"
                
                // Show progress
                Toast.makeText(this@LogsActivity, "Uploading log report...", Toast.LENGTH_SHORT).show()
                
                // Upload to ownCloud
                val ownCloudManager = OwnCloudManager(this@LogsActivity)
                val success = ownCloudManager.uploadTextFile(
                    uuidFolder = appUuid,
                    subfolder = "logs",
                    fileName = fileName,
                    content = logs
                )
                
                if (success) {
                    AppLogger.i("LogsActivity", "Log report uploaded successfully: $fileName")
                    Toast.makeText(this@LogsActivity, "Log report uploaded successfully", Toast.LENGTH_SHORT).show()
                } else {
                    AppLogger.e("LogsActivity", "Failed to upload log report: $fileName")
                    Toast.makeText(this@LogsActivity, "Failed to upload log report. Check logs for details.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                AppLogger.e("LogsActivity", "Error uploading log report", e)
                Toast.makeText(this@LogsActivity, "Error uploading log report: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh logs when returning to this activity
        updateLogs()
    }
}

