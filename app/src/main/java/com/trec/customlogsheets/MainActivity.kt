package com.trec.customlogsheets

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
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.AppDatabase
import com.trec.customlogsheets.data.OfflineMapsManager
import com.trec.customlogsheets.data.SamplingSite
import com.trec.customlogsheets.ui.MainViewModel
import com.trec.customlogsheets.ui.MainViewModelFactory
import com.trec.customlogsheets.ui.SamplingSiteAdapter
import com.trec.customlogsheets.ui.SettingsActivity
import com.trec.customlogsheets.ui.SiteDetailActivity
import com.trec.customlogsheets.ui.DownloadRegionActivity
import com.trec.customlogsheets.data.SettingsPreferences
import com.trec.customlogsheets.data.OwnCloudManager
import com.trec.customlogsheets.util.AppLogger
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: MainViewModel
    private lateinit var ongoingAdapter: SamplingSiteAdapter
    private lateinit var finishedAdapter: SamplingSiteAdapter
    
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
        observeData()
        
        // Cleanup expired offline maps on startup
        lifecycleScope.launch {
            val mapsManager = OfflineMapsManager(this@MainActivity)
            mapsManager.cleanupExpiredRegions()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Reload sites from folders when returning to this activity
        viewModel.loadSitesFromFolders()
        
        // Retry ownCloud folder creation if it wasn't verified yet
        retryOwnCloudFolderCreation()
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
                            // Show offline maps prompt
                            showOfflineMapsPrompt()
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
            }
        }
    }
    
    private fun observeData() {
        lifecycleScope.launch {
            viewModel.ongoingSites.collect { sites ->
                ongoingAdapter.submitList(sites)
            }
        }
        
        lifecycleScope.launch {
            viewModel.finishedSites.collect { sites ->
                finishedAdapter.submitList(sites)
            }
        }
    }
    
    private fun navigateToDetail(site: SamplingSite) {
        val intent = Intent(this, SiteDetailActivity::class.java).apply {
            putExtra("site", site)
        }
        startActivity(intent)
    }
    
    private fun uploadSite(site: SamplingSite) {
        // Check if already uploaded and show warning
        if (site.uploadStatus == com.trec.customlogsheets.data.UploadStatus.UPLOADED) {
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
    
    private fun showOfflineMapsPrompt() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Offline Maps")
            .setMessage("In order to use GPS widgets in offline mode, please download offline maps for this site.")
            .setPositiveButton("Download Maps") { _, _ ->
                // Navigate to download region activity
                val intent = Intent(this, DownloadRegionActivity::class.java)
                startActivity(intent)
            }
            .setNegativeButton("Dismiss") { _, _ ->
                // Do nothing, user dismissed the prompt
            }
            .show()
    }
    
    private fun initializeAppUuidAndOwnCloud() {
        lifecycleScope.launch {
            try {
                val settingsPreferences = SettingsPreferences(this@MainActivity)
                val appUuid = settingsPreferences.getAppUuid()
                AppLogger.i("MainActivity", "App UUID: $appUuid")
                android.util.Log.d("MainActivity", "App UUID: $appUuid")
                
                // Check if folder was already verified
                if (settingsPreferences.isOwnCloudFolderVerified()) {
                    AppLogger.d("MainActivity", "OwnCloud folder already verified: $appUuid")
                    android.util.Log.d("MainActivity", "OwnCloud folder already verified: $appUuid")
                    return@launch
                }
                
                // Ensure ownCloud folder exists
                AppLogger.i("MainActivity", "Ensuring ownCloud folder exists for UUID: $appUuid")
                val ownCloudManager = OwnCloudManager(this@MainActivity)
                val folderCreated = ownCloudManager.ensureFolderExists(appUuid)
                
                if (folderCreated) {
                    settingsPreferences.setOwnCloudFolderVerified(true)
                    AppLogger.i("MainActivity", "OwnCloud folder ensured successfully: $appUuid")
                    android.util.Log.d("MainActivity", "OwnCloud folder ensured: $appUuid")
                } else {
                    AppLogger.w("MainActivity", "Failed to ensure ownCloud folder: $appUuid (will retry later)")
                    android.util.Log.w("MainActivity", "Failed to ensure ownCloud folder: $appUuid (will retry later)")
                }
            } catch (e: Exception) {
                AppLogger.e("MainActivity", "Error initializing UUID/ownCloud: ${e.message}", e)
                android.util.Log.e("MainActivity", "Error initializing UUID/ownCloud: ${e.message}", e)
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
                    android.util.Log.d("MainActivity", "OwnCloud folder verified on retry: $appUuid")
                } else {
                    AppLogger.w("MainActivity", "OwnCloud folder creation retry failed: $appUuid")
                }
            } catch (e: Exception) {
                AppLogger.e("MainActivity", "Error retrying ownCloud folder creation: ${e.message}", e)
                android.util.Log.e("MainActivity", "Error retrying ownCloud folder creation: ${e.message}", e)
            }
        }
    }
}

