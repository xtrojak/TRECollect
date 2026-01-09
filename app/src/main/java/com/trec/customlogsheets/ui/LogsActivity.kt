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
import com.trec.customlogsheets.R
import com.trec.customlogsheets.util.AppLogger

class LogsActivity : AppCompatActivity() {
    private lateinit var editTextLogFilter: TextInputEditText
    private lateinit var textLogs: TextView
    private lateinit var textLogCount: TextView
    private lateinit var scrollViewLogs: ScrollView
    private lateinit var buttonClearLogs: MaterialButton
    private lateinit var buttonCopyLogs: MaterialButton
    private lateinit var buttonScrollToBottom: MaterialButton
    
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
        buttonScrollToBottom = findViewById(R.id.buttonScrollToBottom)
        
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
        
        // Scroll to bottom button
        buttonScrollToBottom.setOnClickListener {
            scrollViewLogs.post {
                scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN)
            }
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
            "$totalLogCount logs"
        } else {
            "$filteredCount of $totalLogCount logs (filtered: \"$filter\")"
        }
        
        if (logs.isEmpty()) {
            textLogs.text = "No logs available${if (!filter.isNullOrEmpty()) " matching \"$filter\"" else ""}"
        } else {
            textLogs.text = logs
            // Auto-scroll to bottom to show most recent logs
            scrollViewLogs.post {
                scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh logs when returning to this activity
        updateLogs()
    }
}

