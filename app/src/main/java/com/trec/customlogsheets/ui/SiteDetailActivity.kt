package com.trec.customlogsheets.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.trec.customlogsheets.MainActivity
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.AppDatabase
import com.trec.customlogsheets.data.Form
import com.trec.customlogsheets.data.PredefinedForms
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.trec.customlogsheets.data.SamplingSite
import com.trec.customlogsheets.data.UploadStatus
import com.trec.customlogsheets.ui.MainViewModel
import com.trec.customlogsheets.ui.MainViewModelFactory
import com.trec.customlogsheets.ui.DownloadRegionActivity
import com.trec.customlogsheets.util.AppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SiteDetailActivity : AppCompatActivity() {
    private lateinit var site: SamplingSite
    private lateinit var formSectionAdapter: FormSectionAdapter
    private lateinit var database: AppDatabase
    private lateinit var viewModel: MainViewModel
    private lateinit var siteNameText: TextView
    private lateinit var cardUploadStatus: com.google.android.material.card.MaterialCardView
    private lateinit var imageViewUploadStatus: ImageView
    private lateinit var textViewUploadStatus: TextView
    private lateinit var buttonRetryUpload: MaterialButton
    private var canFinalize: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_site_detail)
        
        database = AppDatabase.getDatabase(applicationContext)
        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(database, applicationContext)
        )[MainViewModel::class.java]
        
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            navigateToHome()
        }
        
        @Suppress("DEPRECATION")
        val siteExtra = intent.getParcelableExtra<SamplingSite>("site")
        if (siteExtra == null) {
            finish()
            return
        }
        site = siteExtra
        
        siteNameText = findViewById<TextView>(R.id.textViewSiteName)
        siteNameText.text = site.name
        supportActionBar?.title = site.name
        
        cardUploadStatus = findViewById(R.id.cardUploadStatus)
        imageViewUploadStatus = findViewById(R.id.imageViewUploadStatus)
        textViewUploadStatus = findViewById(R.id.textViewUploadStatus)
        buttonRetryUpload = findViewById(R.id.buttonRetryUpload)
        
        // Hide upload status section for finished sites (upload is handled from main list)
        if (site.status == com.trec.customlogsheets.data.SiteStatus.FINISHED) {
            cardUploadStatus.visibility = android.view.View.GONE
        } else {
            setupUploadStatus()
        }
        setupFormsList()
        loadFormCompletions()
        
        // Show offline maps prompt if this is a newly created site
        val showOfflineMapsPrompt = intent.getBooleanExtra("showOfflineMapsPrompt", false)
        if (showOfflineMapsPrompt) {
            showOfflineMapsPrompt()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Reload site data to get updated upload status (only if site has valid ID and not finished)
        if (site.id > 0 && site.status != com.trec.customlogsheets.data.SiteStatus.FINISHED) {
            lifecycleScope.launch {
                try {
                    val updatedSite = database.samplingSiteDao().getSiteById(site.id)
                    if (updatedSite != null) {
                        site = updatedSite
                        setupUploadStatus()
                    }
                } catch (e: Exception) {
                    AppLogger.e("SiteDetailActivity", "Error reloading site: ${e.message}", e)
                }
            }
        }
        // Reload form completions when returning from form editing
        loadFormCompletions()
        // Update menu to refresh finalize button state
        invalidateOptionsMenu()
    }
    
    private fun setupUploadStatus() {
        // Only show upload status for finished sites
        if (site.status == com.trec.customlogsheets.data.SiteStatus.FINISHED) {
            cardUploadStatus.visibility = android.view.View.VISIBLE
            
            when (site.uploadStatus) {
                UploadStatus.UPLOADED -> {
                    imageViewUploadStatus.setImageResource(android.R.drawable.ic_menu_upload)
                    imageViewUploadStatus.setColorFilter(
                        ContextCompat.getColor(this, android.R.color.holo_green_dark)
                    )
                    textViewUploadStatus.text = "Uploaded successfully"
                    buttonRetryUpload.visibility = android.view.View.VISIBLE
                    buttonRetryUpload.text = "Re-upload"
                }
                UploadStatus.UPLOAD_FAILED -> {
                    imageViewUploadStatus.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    imageViewUploadStatus.setColorFilter(
                        ContextCompat.getColor(this, android.R.color.holo_red_dark)
                    )
                    textViewUploadStatus.text = "Upload failed"
                    buttonRetryUpload.visibility = android.view.View.VISIBLE
                    buttonRetryUpload.text = "Retry Upload"
                }
                UploadStatus.UPLOADING -> {
                    imageViewUploadStatus.setImageResource(android.R.drawable.ic_menu_upload)
                    imageViewUploadStatus.setColorFilter(
                        ContextCompat.getColor(this, android.R.color.holo_orange_dark)
                    )
                    textViewUploadStatus.text = "Uploading..."
                    buttonRetryUpload.visibility = android.view.View.GONE
                }
                UploadStatus.NOT_UPLOADED -> {
                    imageViewUploadStatus.setImageResource(android.R.drawable.ic_menu_upload)
                    imageViewUploadStatus.setColorFilter(
                        ContextCompat.getColor(this, android.R.color.darker_gray)
                    )
                    textViewUploadStatus.text = "Not uploaded"
                    buttonRetryUpload.visibility = android.view.View.VISIBLE
                    buttonRetryUpload.text = "Upload Now"
                }
            }
            
            buttonRetryUpload.setOnClickListener {
                retryUpload()
            }
        } else {
            cardUploadStatus.visibility = android.view.View.GONE
        }
    }
    
    private fun retryUpload() {
        // Check if already uploaded and show warning
        if (site.uploadStatus == UploadStatus.UPLOADED) {
            AlertDialog.Builder(this)
                .setTitle("Re-upload Site")
                .setMessage("This site has already been uploaded successfully. Re-uploading will overwrite the existing submission. Continue?")
                .setPositiveButton("Re-upload") { _, _ ->
                    performUpload()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            performUpload()
        }
    }
    
    private fun performUpload() {
        lifecycleScope.launch {
            @Suppress("DEPRECATION")
            val progressDialog = android.app.ProgressDialog.show(
                this@SiteDetailActivity,
                "Uploading",
                "Uploading site to ownCloud...",
                true,
                false
            )
            
            try {
                val result = viewModel.uploadSiteToOwnCloud(site)
                progressDialog.dismiss()
                
                when (result) {
                    is MainViewModel.UploadSiteResult.Success -> {
                        Toast.makeText(
                            this@SiteDetailActivity,
                            "Site uploaded successfully (${result.uploadedCount}/${result.totalCount} files)",
                            Toast.LENGTH_SHORT
                        ).show()
                        // Reload site to update status
                        if (site.id > 0) {
                            try {
                                val updatedSite = database.samplingSiteDao().getSiteById(site.id)
                                if (updatedSite != null) {
                                    site = updatedSite
                                    setupUploadStatus()
                                }
                            } catch (e: Exception) {
                                AppLogger.e("SiteDetailActivity", "Error reloading site after upload: ${e.message}", e)
                            }
                        }
                    }
                    is MainViewModel.UploadSiteResult.Error -> {
                        Toast.makeText(
                            this@SiteDetailActivity,
                            "Upload failed: ${result.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        // Reload site to update status
                        if (site.id > 0) {
                            try {
                                val updatedSite = database.samplingSiteDao().getSiteById(site.id)
                                if (updatedSite != null) {
                                    site = updatedSite
                                    setupUploadStatus()
                                }
                            } catch (e: Exception) {
                                AppLogger.e("SiteDetailActivity", "Error reloading site after upload failure: ${e.message}", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(
                    this@SiteDetailActivity,
                    "Error during upload: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    private fun navigateToHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.site_detail_menu, menu)
        return true
    }
    
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val isFinalized = site.status == com.trec.customlogsheets.data.SiteStatus.FINISHED
        
        // Hide/disable menu items for finalized sites
        val finalizeItem = menu.findItem(R.id.action_finalize)
        val renameItem = menu.findItem(R.id.action_rename)
        val deleteItem = menu.findItem(R.id.action_delete)
        
        if (isFinalized) {
            // Hide all editing options for finalized sites
            finalizeItem?.isVisible = false
            renameItem?.isVisible = false
            deleteItem?.isVisible = false
        } else {
            // Show all items for ongoing sites
            finalizeItem?.isVisible = true
            renameItem?.isVisible = true
            deleteItem?.isVisible = true
            // Update finalize button state based on mandatory forms completion
            finalizeItem?.isEnabled = canFinalize
        }
        
        return super.onPrepareOptionsMenu(menu)
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_finalize -> {
                showFinalizeConfirmationDialog()
                true
            }
            R.id.action_rename -> {
                showRenameDialog()
                true
            }
            R.id.action_delete -> {
                showDeleteConfirmationDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private suspend fun checkAllMandatoryFormsSubmitted(): Boolean {
        val mandatoryForms = PredefinedForms.getMandatoryForms(this)
        if (mandatoryForms.isEmpty()) {
            return true // No mandatory forms, can finalize
        }
        
        val formFileHelper = com.trec.customlogsheets.data.FormFileHelper(this)
        val submittedFormIds = formFileHelper.getSubmittedForms(site.name).toSet()
        
        // Check if all mandatory forms are submitted
        return mandatoryForms.all { form ->
            submittedFormIds.contains(form.id)
        }
    }
    
    private fun showFinalizeConfirmationDialog() {
        lifecycleScope.launch {
            val mandatoryForms = PredefinedForms.getMandatoryForms(this@SiteDetailActivity)
            val formFileHelper = com.trec.customlogsheets.data.FormFileHelper(this@SiteDetailActivity)
            val submittedFormIds = formFileHelper.getSubmittedForms(site.name).toSet()
            
            val missingForms = mandatoryForms.filter { !submittedFormIds.contains(it.id) }
            
            if (missingForms.isNotEmpty()) {
                val missingNames = missingForms.joinToString(", ") { it.name }
                Toast.makeText(
                    this@SiteDetailActivity,
                    "Cannot finalize: Missing mandatory forms: $missingNames",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            
            AlertDialog.Builder(this@SiteDetailActivity)
                .setTitle("Submit Site")
                .setMessage("Are you sure you want to submit \"${site.name}\"? This will move the site to finished sites and cannot be undone.")
                .setPositiveButton("Submit") { _, _ ->
                    // Show loading indicator
                    @Suppress("DEPRECATION")
                    val progressDialog = android.app.ProgressDialog.show(
                        this@SiteDetailActivity,
                        "Submitting",
                        "Moving site to finished...",
                        true,
                        false
                    )
                    
                    lifecycleScope.launch {
                        val result = viewModel.finalizeSite(site)
                        progressDialog.dismiss()
                        
                        when (result) {
                            is MainViewModel.FinalizeSiteResult.Success -> {
                                Toast.makeText(this@SiteDetailActivity, "Site finalized", Toast.LENGTH_SHORT).show()
                                // Navigate back to main activity
                                navigateToHome()
                            }
                            is MainViewModel.FinalizeSiteResult.Error -> {
                                Toast.makeText(this@SiteDetailActivity, result.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    private fun showRenameDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_rename_site, null)
        val editText = dialogView.findViewById<EditText>(R.id.editTextNewName)
        editText.setText(site.name)
        editText.selectAll()
        
        AlertDialog.Builder(this)
            .setTitle("Rename Site")
            .setMessage("Enter a new name for this site:")
            .setView(dialogView)
            .setPositiveButton("Rename") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotBlank() && newName != site.name) {
                    // Show loading indicator
                    @Suppress("DEPRECATION")
                    val progressDialog = android.app.ProgressDialog.show(
                        this,
                        "Renaming",
                        "Please wait...",
                        true,
                        false
                    )
                    
                    lifecycleScope.launch {
                        val result = viewModel.renameSite(site, newName)
                        progressDialog.dismiss()
                        
                        when (result) {
                            is MainViewModel.RenameSiteResult.Success -> {
                                // Update local site object and UI
                                site = site.copy(name = newName)
                                siteNameText.text = newName
                                supportActionBar?.title = newName
                                Toast.makeText(this@SiteDetailActivity, "Site renamed", Toast.LENGTH_SHORT).show()
                            }
                            is MainViewModel.RenameSiteResult.Error -> {
                                Toast.makeText(this@SiteDetailActivity, result.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else if (newName.isBlank()) {
                    Toast.makeText(this, "Site name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showDeleteConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Delete Site")
            .setMessage("Are you sure you want to delete \"${site.name}\"? The site folder will be moved to the deleted folder. This action cannot be undone and will also delete all associated form data.")
            .setPositiveButton("Delete") { _, _ ->
                // Show loading indicator
                @Suppress("DEPRECATION")
                val progressDialog = android.app.ProgressDialog.show(
                    this,
                    "Deleting",
                    "Moving site folder to deleted...",
                    true,
                    false
                )
                
                lifecycleScope.launch {
                    val result = viewModel.deleteSite(site)
                    progressDialog.dismiss()
                    
                    when (result) {
                        is MainViewModel.DeleteSiteResult.Success -> {
                            AppLogger.i("SiteDetailActivity", "Site deletion completed: name='${site.name}'")
                            Toast.makeText(this@SiteDetailActivity, "Site deleted", Toast.LENGTH_SHORT).show()
                            // Navigate back to main activity
                            navigateToHome()
                        }
                        is MainViewModel.DeleteSiteResult.Error -> {
                            AppLogger.w("SiteDetailActivity", "Site deletion failed: name='${site.name}', error='${result.message}'")
                            Toast.makeText(this@SiteDetailActivity, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun setupFormsList() {
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewFormSections)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        formSectionAdapter = FormSectionAdapter { form ->
            onFormClick(form)
        }
        recyclerView.adapter = formSectionAdapter
        
        // Initialize with all forms from config
        val sections = PredefinedForms.getSections(this)
        val formsBySection = sections.associateWith { section ->
            PredefinedForms.getFormsBySection(this, section)
        }
        formSectionAdapter.setData(sections, formsBySection, emptySet(), emptySet())
    }
    
    private fun loadFormCompletions() {
        lifecycleScope.launch {
            // Check which forms are submitted and which have drafts using FormFileHelper
            val formFileHelper = com.trec.customlogsheets.data.FormFileHelper(this@SiteDetailActivity)
            val submittedFormIds = formFileHelper.getSubmittedForms(site.name).toSet()
            val allDraftFormIds = formFileHelper.getDraftForms(site.name).toSet()
            // Only show drafts for forms that are not submitted
            val draftFormIds = allDraftFormIds.filter { !submittedFormIds.contains(it) }.toSet()
            
            val sections = PredefinedForms.getSections(this@SiteDetailActivity)
            val formsBySection = sections.associateWith { section ->
                PredefinedForms.getFormsBySection(this@SiteDetailActivity, section)
            }
            formSectionAdapter.setData(sections, formsBySection, submittedFormIds, draftFormIds)
            
            // Update canFinalize flag
            canFinalize = checkAllMandatoryFormsSubmitted()
            invalidateOptionsMenu() // Refresh menu to update finalize button state
        }
    }
    
    private fun onFormClick(form: Form) {
        // Open form editing activity
        val intent = Intent(this, FormEditActivity::class.java).apply {
            putExtra("siteName", site.name)
            putExtra("formId", form.id)
            putExtra("isReadOnly", site.status == com.trec.customlogsheets.data.SiteStatus.FINISHED)
        }
        startActivity(intent)
    }
    
    override fun onSupportNavigateUp(): Boolean {
        navigateToHome()
        return true
    }
    
    private fun showOfflineMapsPrompt() {
        AlertDialog.Builder(this)
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
}

