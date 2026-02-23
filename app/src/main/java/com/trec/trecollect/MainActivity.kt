package com.trec.trecollect

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.trec.trecollect.data.AppDatabase
import com.trec.trecollect.data.OfflineMapsManager
import com.trec.trecollect.data.SamplingSite
import com.trec.trecollect.ui.MainViewModel
import com.trec.trecollect.ui.MainViewModelFactory
import com.trec.trecollect.ui.SamplingSiteAdapter
import com.trec.trecollect.ui.SettingsActivity
import com.trec.trecollect.ui.SiteDetailActivity
import com.trec.trecollect.data.SettingsPreferences
import com.trec.trecollect.data.OwnCloudManager
import com.trec.trecollect.data.FormConfigLoader
import com.trec.trecollect.data.PredefinedForms
import com.trec.trecollect.data.UploadStatus
import com.trec.trecollect.util.AppLogger
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: MainViewModel
    private lateinit var ongoingAdapter: SamplingSiteAdapter
    private lateinit var finishedAdapter: SamplingSiteAdapter
    
    // Track last known team/subteam to detect changes
    private var lastKnownTeam: String = ""
    private var lastKnownSubteam: String = ""
    private var lastResumeTime = 0L
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        
        val database = AppDatabase.getDatabase(applicationContext)
        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(database, applicationContext)
        )[MainViewModel::class.java]
        
        setupRecyclerViews()
        setupCreateButton()
        setupUploadAllButton()
        observeData()
        
        // Initialize last known team/subteam
        val settingsPreferences = SettingsPreferences(this)
        lastKnownTeam = settingsPreferences.getSamplingTeam()
        lastKnownSubteam = settingsPreferences.getSamplingSubteam()
        
        // Cleanup expired offline maps on startup
        lifecycleScope.launch {
            val mapsManager = OfflineMapsManager(this@MainActivity)
            mapsManager.cleanupExpiredRegions()
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // Check if team and folder are configured
        checkInitialSetup()
        
        val settingsPreferences = SettingsPreferences(this)
        val currentTeam = settingsPreferences.getSamplingTeam()
        val currentSubteam = settingsPreferences.getSamplingSubteam()
        
        // Check if team or subteam has changed
        val teamChanged = currentTeam != lastKnownTeam || currentSubteam != lastKnownSubteam
        
        val currentTime = System.currentTimeMillis()
        // Reload if:
        // 1. Team/subteam changed (force reload regardless of time)
        // 2. We've been away for more than 2 seconds (to avoid unnecessary reloads)
        if (teamChanged || lastResumeTime == 0L || (currentTime - lastResumeTime) > 2000) {
            if (teamChanged) {
                AppLogger.d("MainActivity", "Team/subteam changed: team='$lastKnownTeam'->'$currentTeam', subteam='$lastKnownSubteam'->'$currentSubteam'")
                // Clear form config cache when team changes
                FormConfigLoader.clearCache()
                PredefinedForms.clearCache()
                // Force reload by bypassing debounce
                viewModel.loadSitesFromFolders(force = true)
            } else {
                // Normal reload (with debounce)
            viewModel.loadSitesFromFolders()
            }
            // Update last known values AFTER calling loadSitesFromFolders
            lastKnownTeam = currentTeam
            lastKnownSubteam = currentSubteam
        }
        lastResumeTime = currentTime
        
        // Retry ownCloud folder creation if it wasn't verified yet
        retryOwnCloudFolderCreation()
    }
    
    private fun checkInitialSetup() {
        // Skip setup check in test environment to avoid blocking tests
        if (isRunningInTest()) {
            return
        }
        
        val settingsPreferences = SettingsPreferences(this)
        val folderUri = settingsPreferences.getFolderUri()
        val team = settingsPreferences.getSamplingTeam()
        val subteamSet = settingsPreferences.isSamplingSubteamSet()
        
        val missingItems = mutableListOf<String>()
        if (folderUri.isEmpty()) {
            missingItems.add("Output folder")
        }
        if (team.isEmpty()) {
            missingItems.add("Sampling team")
        } else if (!subteamSet) {
            val teamDisplayName = "$team subteam"
            missingItems.add(teamDisplayName)
        }
        
        if (missingItems.isNotEmpty()) {
            val message = "Please configure the following in Settings before using the app:\n\n" +
                    missingItems.joinToString("\n• ", "• ")
            
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Initial Setup Required")
                .setMessage(message)
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                }
                .setCancelable(false)
                .show()
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun setupRecyclerViews() {
        ongoingAdapter = SamplingSiteAdapter(
            onItemClick = { site -> navigateToDetail(site) }
        )
        
        finishedAdapter = SamplingSiteAdapter(
            onItemClick = { site -> navigateToDetail(site) },
            onUploadClick = { site -> uploadSite(site) }
        )
        
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewOngoing).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = ongoingAdapter
        }
        
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewFinished).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = finishedAdapter
        }
    }
    
    private fun setupCreateButton() {
        findViewById<MaterialButton>(R.id.buttonCreateSite).setOnClickListener {
            val settingsPreferences = SettingsPreferences(this)
            val team = settingsPreferences.getSamplingTeam()
            val subteamSet = settingsPreferences.isSamplingSubteamSet()
            val folderUri = settingsPreferences.getFolderUri()
            
            // Check if setup is complete
            if (team.isEmpty() || !subteamSet || folderUri.isEmpty()) {
                android.widget.Toast.makeText(
                    this,
                    "Please configure team and output folder in Settings first",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                return@setOnClickListener
            }
            
            val editText = findViewById<TextInputEditText>(R.id.editTextSiteName)
            val siteName = editText.text?.toString() ?: ""
            
            if (siteName.isNotBlank()) {
                lifecycleScope.launch {
                    val result = viewModel.createSite(siteName)
                    when (result) {
                        is MainViewModel.CreateSiteResult.Success -> {
                            AppLogger.i("MainActivity", "Site creation completed successfully: name='$siteName'")
                editText.text?.clear()
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "Site created successfully",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            // Navigate to the newly created site (with flag to show offline maps prompt)
                            navigateToDetail(result.site, showOfflineMapsPrompt = true)
                        }
                        is MainViewModel.CreateSiteResult.Error -> {
                            AppLogger.w("MainActivity", "Site creation failed: name='$siteName', error='${result.message}'")
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                result.message,
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } else {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Please provide a site name",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun observeData() {
        // Observe both flows in parallel using a single coroutine scope
        lifecycleScope.launch {
            kotlinx.coroutines.coroutineScope {
                // Launch both collectors in parallel
                launch {
                    viewModel.ongoingSites.collect { sites ->
                        ongoingAdapter.submitList(sites)
                    }
                }
                launch {
                    viewModel.finishedSites.collect { sites ->
                        finishedAdapter.submitList(sites)
                    }
                }
            }
        }
    }
    
    private fun navigateToDetail(site: SamplingSite, showOfflineMapsPrompt: Boolean = false) {
        val intent = Intent(this, SiteDetailActivity::class.java).apply {
            putExtra("site", site)
            putExtra("showOfflineMapsPrompt", showOfflineMapsPrompt)
        }
        startActivity(intent)
    }
    
    private fun uploadSite(site: SamplingSite) {
        // Check if already uploaded and show warning
        if (site.uploadStatus == UploadStatus.UPLOADED) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Re-upload Site")
                .setMessage("${site.name} has already been uploaded successfully. Re-uploading will overwrite the existing submission. Continue?")
                .setPositiveButton("Re-upload") { _, _ ->
                    performUpload(site)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            performUpload(site)
        }
    }
    
    private fun setupUploadAllButton() {
        findViewById<MaterialButton>(R.id.buttonUploadAll).setOnClickListener {
            uploadAllSites()
        }
    }
    
    private fun uploadAllSites() {
        lifecycleScope.launch {
            // Get all finished sites that haven't been uploaded yet
            val finishedSites = viewModel.finishedSites.value
            val sitesToUpload = finishedSites.filter { 
                it.uploadStatus != UploadStatus.UPLOADED 
            }
            
            if (sitesToUpload.isEmpty()) {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "All sites have already been uploaded",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            
            // Show warning dialog
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Upload All Sites")
                .setMessage("This will upload ${sitesToUpload.size} site(s) that haven't been uploaded yet. This may take a while. Continue?")
                .setPositiveButton("Upload All") { _, _ ->
                    performUploadAll(sitesToUpload)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    private fun performUploadAll(sites: List<SamplingSite>) {
        lifecycleScope.launch {
            AppLogger.i("MainActivity", "Starting batch upload for ${sites.size} site(s)")
            
            var successCount = 0
            var failCount = 0
            
            for ((index, site) in sites.withIndex()) {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Uploading ${site.name} (${index + 1}/${sites.size})...",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                
                val result = viewModel.uploadSiteToOwnCloud(site)
                when (result) {
                    is MainViewModel.UploadSiteResult.Success -> {
                        successCount++
                        AppLogger.i("MainActivity", "Uploaded ${site.name} successfully (${index + 1}/${sites.size})")
                    }
                    is MainViewModel.UploadSiteResult.Error -> {
                        failCount++
                        AppLogger.e("MainActivity", "Upload failed for ${site.name}: ${result.message}")
                    }
                }
                
                // Reload sites to update checkboxes after each upload
                viewModel.loadSitesFromFolders()
            }
            
            // Show final summary
            val message = when {
                failCount == 0 -> "All ${successCount} site(s) uploaded successfully"
                successCount == 0 -> "All uploads failed"
                else -> "$successCount site(s) uploaded successfully, $failCount failed"
            }
            
            android.widget.Toast.makeText(
                this@MainActivity,
                message,
                if (failCount > 0) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
            ).show()
            
            AppLogger.i("MainActivity", "Batch upload completed: $successCount success, $failCount failed")
        }
    }
    
    private fun performUpload(site: SamplingSite) {
        lifecycleScope.launch {
            AppLogger.i("MainActivity", "Starting manual upload for site: name='${site.name}'")
            
            // Show upload start message
            android.widget.Toast.makeText(
                this@MainActivity,
                "Uploading ${site.name}...",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            
            val result = viewModel.uploadSiteToOwnCloud(site)
            when (result) {
                is MainViewModel.UploadSiteResult.Success -> {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "${site.name} uploaded successfully",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    // Reload sites to update the checkbox
                    viewModel.loadSitesFromFolders()
                }
                is MainViewModel.UploadSiteResult.Error -> {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Upload failed for ${site.name}: ${result.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    // Reload sites to update the checkbox
                    viewModel.loadSitesFromFolders()
                }
            }
        }
    }
    
    /**
     * Checks if the app is running in a test environment.
     * This prevents creating ownCloud folders during testing.
     * 
     * Detection methods:
     * 1. Check if running under instrumentation (most reliable for instrumented tests)
     * 2. Check for test runner process name
     */
    private fun isRunningInTest(): Boolean {
        return try {
            // Method 1: Check if running under instrumentation (instrumented tests)
            // This is the most reliable method for Android instrumented tests
            Class.forName("androidx.test.platform.app.InstrumentationRegistry")
            true
        } catch (e: ClassNotFoundException) {
            try {
                // Method 2: Check for AndroidJUnitRunner (alternative instrumentation)
                Class.forName("androidx.test.runner.AndroidJUnitRunner")
                true
            } catch (e2: ClassNotFoundException) {
                // Method 3: Check process name (heuristic - less reliable but catches some cases)
                val processName = android.os.Process.myPid().let { pid ->
                    try {
                        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        activityManager.runningAppProcesses?.find { it.pid == pid }?.processName
                    } catch (e: Exception) {
                        null
                    }
                }
                // Check if process name suggests testing
                processName?.contains("test", ignoreCase = true) == true ||
                processName?.contains("androidTest", ignoreCase = true) == true
            }
        }
    }
    
    private fun retryOwnCloudFolderCreation() {
        lifecycleScope.launch {
            try {
                val settingsPreferences = SettingsPreferences(this@MainActivity)
                
                // Only retry if folder hasn't been verified yet
                if (settingsPreferences.isOwnCloudFolderVerified()) {
                    return@launch
                }
                
                val appUuid = settingsPreferences.getAppUuid()
                AppLogger.d("MainActivity", "Retrying ownCloud folder creation for UUID: $appUuid")
                val ownCloudManager = OwnCloudManager(this@MainActivity)
                val folderCreated = ownCloudManager.ensureFolderExists(appUuid)
                
                if (folderCreated) {
                    settingsPreferences.setOwnCloudFolderVerified(true)
                    AppLogger.i("MainActivity", "OwnCloud folder verified on retry: $appUuid")
                } else {
                    AppLogger.w("MainActivity", "OwnCloud folder creation retry failed: $appUuid")
                }
            } catch (e: Exception) {
                AppLogger.e("MainActivity", "Error retrying ownCloud folder creation: ${e.message}", e)
            }
        }
    }
}

