package com.trec.trecollect.ui

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
import com.trec.trecollect.MainActivity
import com.trec.trecollect.R
import com.trec.trecollect.data.AppDatabase
import com.trec.trecollect.data.Form
import com.trec.trecollect.data.FormConfigLoader
import com.trec.trecollect.data.FormFileHelper
import com.trec.trecollect.data.PredefinedForms
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.button.MaterialButton
import com.trec.trecollect.data.SamplingSite
import com.trec.trecollect.data.UploadStatus
import com.trec.trecollect.ui.MainViewModel
import com.trec.trecollect.ui.MainViewModelFactory
import com.trec.trecollect.util.AppLogger
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SiteDetailActivity : AppCompatActivity() {
    private lateinit var site: SamplingSite
    private var formSectionAdapter: FormSectionAdapter? = null
    private lateinit var database: AppDatabase
    private lateinit var viewModel: MainViewModel
    private lateinit var siteNameText: TextView
    private lateinit var cardUploadStatus: com.google.android.material.card.MaterialCardView
    private lateinit var textViewUploadStatus: TextView
    private lateinit var buttonRetryUpload: MaterialButton
    private var canFinalize: Boolean = false
    private var savedScrollPosition: Int = 0 // Save scroll position when refreshing
    
    // Cache for this site's base forms by section; cleared on reload so size is bounded by app form config.
    private var cachedBaseFormsBySection: Map<String, List<Form>>? = null
    private var cachedSections: List<String>? = null
    
    // Flag to prevent duplicate setupFormsList calls
    private var isSettingUpForms = false
    private var formsListInitialized = false

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
        textViewUploadStatus = findViewById(R.id.textViewUploadStatus)
        buttonRetryUpload = findViewById(R.id.buttonRetryUpload)
        
        // Hide upload status section for finished sites (upload is handled from main list)
        if (site.status == com.trec.trecollect.data.SiteStatus.FINISHED) {
            cardUploadStatus.visibility = android.view.View.GONE
        } else {
            setupUploadStatus()
        }
        
        setupFormsList()
        loadFormCompletions()
        
        // Show offline maps prompt only the first time a site is created (once per site).
        // We persist "already shown" so that if the activity is recreated (e.g. process death,
        // rotation), we don't show the dialog again due to the same intent extra being present.
        val showOfflineMapsPrompt = intent.getBooleanExtra("showOfflineMapsPrompt", false)
        if (showOfflineMapsPrompt && !hasAlreadyShownOfflineMapsPromptForSite(site.name)) {
            markOfflineMapsPromptShownForSite(site.name)
            showOfflineMapsPrompt()
        }
    }
    
    private fun hasAlreadyShownOfflineMapsPromptForSite(siteName: String): Boolean {
        val prefs = getSharedPreferences(PREFS_OFFLINE_MAPS_PROMPT, MODE_PRIVATE)
        return prefs.getStringSet(KEY_OFFLINE_MAPS_PROMPT_SHOWN_SITES, emptySet())?.contains(siteName) == true
    }
    
    private fun markOfflineMapsPromptShownForSite(siteName: String) {
        val prefs = getSharedPreferences(PREFS_OFFLINE_MAPS_PROMPT, MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_OFFLINE_MAPS_PROMPT_SHOWN_SITES, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(KEY_OFFLINE_MAPS_PROMPT_SHOWN_SITES, current + siteName).apply()
    }
    
    override fun onResume() {
        super.onResume()
        // Save scroll position before refreshing
        saveScrollPosition()
        
        // Only reload if forms list was already initialized (skip on first onCreate->onResume cycle)
        // This prevents duplicate work when onCreate() already called setupFormsList()
        if (formsListInitialized && !isSettingUpForms) {
            // Clear cached forms data to force reload (in case forms changed)
            cachedBaseFormsBySection = null
            cachedSections = null
            
            // Reload forms list to pick up any new dynamic instances
            setupFormsList()
        }
        
        // Reload site and refresh upload status so the user sees the latest state when returning
        // (e.g. after background upload or from main list where status is also shown).
        if (site.id > 0 && site.status != com.trec.trecollect.data.SiteStatus.FINISHED) {
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
        
        // Update menu to refresh finalize button state
        invalidateOptionsMenu()
        // Note: Scroll position will be restored in setupFormsList() after data is loaded
    }
    
    private fun setupUploadStatus() {
        // Only show upload status for finished sites
        if (site.status == com.trec.trecollect.data.SiteStatus.FINISHED) {
            cardUploadStatus.visibility = android.view.View.VISIBLE
            
            when (site.uploadStatus) {
                UploadStatus.UPLOADED -> {
                    setUploadStatusCompoundDrawable(android.R.drawable.ic_menu_upload, android.R.color.holo_green_dark)
                    textViewUploadStatus.text = getString(R.string.uploaded_successfully)
                    buttonRetryUpload.visibility = android.view.View.VISIBLE
                    buttonRetryUpload.text = getString(R.string.re_upload)
                }
                UploadStatus.UPLOAD_FAILED -> {
                    setUploadStatusCompoundDrawable(android.R.drawable.ic_menu_close_clear_cancel, android.R.color.holo_red_dark)
                    textViewUploadStatus.text = getString(R.string.upload_failed)
                    buttonRetryUpload.visibility = android.view.View.VISIBLE
                    buttonRetryUpload.text = getString(R.string.retry_upload_button)
                }
                UploadStatus.UPLOADING -> {
                    setUploadStatusCompoundDrawable(android.R.drawable.ic_menu_upload, android.R.color.holo_orange_dark)
                    textViewUploadStatus.text = getString(R.string.uploading)
                    buttonRetryUpload.visibility = android.view.View.GONE
                }
                UploadStatus.NOT_UPLOADED -> {
                    setUploadStatusCompoundDrawable(android.R.drawable.ic_menu_upload, android.R.color.darker_gray)
                    textViewUploadStatus.text = getString(R.string.not_uploaded)
                    buttonRetryUpload.visibility = android.view.View.VISIBLE
                    buttonRetryUpload.text = getString(R.string.upload_now)
                }
            }
            
            buttonRetryUpload.setOnClickListener {
                retryUpload()
            }
        } else {
            cardUploadStatus.visibility = android.view.View.GONE
        }
    }
    
    private fun setUploadStatusCompoundDrawable(drawableResId: Int, tintColorResId: Int) {
        val drawable = ContextCompat.getDrawable(this, drawableResId)?.mutate()
        drawable?.let { DrawableCompat.setTint(it, ContextCompat.getColor(this, tintColorResId)) }
        textViewUploadStatus.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
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
        val isFinalized = site.status == com.trec.trecollect.data.SiteStatus.FINISHED
        
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
            // Submit button is always enabled; if mandatory forms are missing, the dialog will explain
            finalizeItem?.isEnabled = true
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
    
    /**
     * OPTIMIZATION: Check mandatory forms using already-loaded statuses (no file I/O)
     */
    private fun checkAllMandatoryFormsSubmittedFromStatuses(
        allStatuses: Map<String, Pair<Boolean, Boolean>>,
        formsBySection: Map<String, List<Form>>,
        instanceIndexMap: Map<Triple<String, String, Int>, Int>
    ): Boolean {
        val allForms = formsBySection.values.flatten()
        if (allForms.none { it.mandatory }) {
            return true // No mandatory forms, can finalize
        }
        
        // Check each form occurrence by its own mandatory flag (same form id can be mandatory in one spot, optional in another)
        return allForms
            .filter { it.mandatory }
            .all { form ->
                val formsInSection = formsBySection[form.section] ?: emptyList()
                val formPosition = formsInSection.indexOfFirst { it.id == form.id && it.name == form.name }
                    .takeIf { it >= 0 } ?: 0
                // Use pre-computed instance index
                val instanceIndex = instanceIndexMap[Triple(form.id, form.section, formPosition)] ?: 0
                if (form.isDynamic) {
                    // Dynamic forms use keys formId_instanceIndex_subIndex; consider submitted if at least one instance is submitted
                    allStatuses.entries.any { (key, value) ->
                        value.first && key.startsWith("${form.id}_${instanceIndex}_")
                    }
                } else {
                    val formKey = "${form.id}_${instanceIndex}"
                    val status = allStatuses[formKey]
                    status?.first == true
                }
            }
    }
    
    private fun showFinalizeConfirmationDialog() {
        lifecycleScope.launch {
            val formFileHelper = FormFileHelper(this@SiteDetailActivity)
            
            // OPTIMIZATION: Use cached forms data if available, otherwise load
            val formsBySection = cachedBaseFormsBySection ?: run {
                val sections = withContext(Dispatchers.IO) {
                    PredefinedForms.getSectionsForSite(this@SiteDetailActivity, site.name)
                }
                sections.associateWith { section ->
                    PredefinedForms.getFormsBySectionForSite(this@SiteDetailActivity, site.name, section)
                }
            }
            
            // OPTIMIZATION: Pre-compute instance indices for all forms to avoid recalculation
            val instanceIndexMap = mutableMapOf<Triple<String, String, Int>, Int>() // (formId, section, orderInSection) -> instanceIndex
            formsBySection.forEach { (section, forms) ->
                forms.forEachIndexed { orderInSection, form ->
                    var instanceIndex = 0
                    for (i in 0 until orderInSection) {
                        if (forms[i].id == form.id) {
                            instanceIndex++
                        }
                    }
                    instanceIndexMap[Triple(form.id, section, orderInSection)] = instanceIndex
                }
            }
            
            // Check which mandatory form instances are missing (use each form's mandatory flag, not form id)
            val missingForms = formsBySection.values.flatten()
                .filter { it.mandatory }
                .filter { form ->
                    val formsInSection = formsBySection[form.section] ?: emptyList()
                    val formPosition = formsInSection.indexOfFirst { it.id == form.id && it.name == form.name }
                        .takeIf { it >= 0 } ?: 0
                    val instanceIndex = instanceIndexMap[Triple(form.id, form.section, formPosition)] ?: 0
                    val isSubmitted = if (form.isDynamic) {
                        val instances = formFileHelper.getDynamicFormInstances(site.name, form.id, instanceIndex)
                        instances.any { subIndex ->
                            formFileHelper.isFormSubmitted(site.name, form.id, instanceIndex, subIndex)
                        }
                    } else {
                        formFileHelper.isFormSubmitted(site.name, form.id, instanceIndex)
                    }
                    !isSubmitted
                }
            
            if (missingForms.isNotEmpty()) {
                val missingNames = missingForms.map { it.name }
                // Log complete list for debugging
                AppLogger.d("SiteDetailActivity", "Cannot finalize site \"${site.name}\": missing mandatory forms (${missingNames.size}): ${missingNames.joinToString(", ")}")
                android.util.Log.d("SiteDetailActivity", "Cannot finalize: missing mandatory forms: ${missingNames.joinToString(", ")}")
                
                val message = if (missingNames.size <= 3) {
                    "The following mandatory forms are not filled:\n\n• ${missingNames.joinToString("\n• ")}"
                } else {
                    "Many mandatory forms are not filled yet (${missingNames.size} forms). Complete all required forms before submitting the site."
                }
                AlertDialog.Builder(this@SiteDetailActivity)
                    .setTitle("Cannot submit site")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
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
    
    private fun saveScrollPosition() {
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewFormSections)
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        if (layoutManager != null) {
            val firstVisible = layoutManager.findFirstVisibleItemPosition()
            if (firstVisible >= 0) {
                savedScrollPosition = firstVisible
                AppLogger.d("SiteDetailActivity", "Saved scroll position: $savedScrollPosition")
            }
        }
    }
    
    private fun restoreScrollPosition() {
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewFormSections)
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        val adapter = formSectionAdapter
        if (layoutManager != null && adapter != null && savedScrollPosition >= 0) {
            AppLogger.d("SiteDetailActivity", "Restoring scroll position: $savedScrollPosition (adapter has ${adapter.itemCount} items)")
            // Post to ensure the layout is complete before scrolling
            recyclerView.post {
                // Double-check the position is still valid after layout
                val currentAdapter = formSectionAdapter
                if (currentAdapter != null) {
                    val targetPosition = if (savedScrollPosition < currentAdapter.itemCount) {
                        savedScrollPosition
                    } else {
                        currentAdapter.itemCount - 1
                    }.coerceAtLeast(0)
                    
                    if (targetPosition >= 0) {
                        AppLogger.d("SiteDetailActivity", "Scrolling to position: $targetPosition")
                        layoutManager.scrollToPositionWithOffset(targetPosition, 0)
                    }
                }
            }
        }
    }
    
    private fun setupFormsList() {
        // Prevent duplicate concurrent calls
        if (isSettingUpForms) {
            AppLogger.d("SiteDetailActivity", "setupFormsList already in progress, skipping duplicate call")
            return
        }
        
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewFormSections)
        
        // Only create adapter and layout manager once
        if (formSectionAdapter == null) {
            recyclerView.layoutManager = LinearLayoutManager(this)
            formSectionAdapter = FormSectionAdapter(
                onFormClick = { form -> onFormClick(form) },
                onAddDynamicForm = { form -> 
                    AppLogger.d("SiteDetailActivity", "Callback received in FormSectionAdapter lambda for form: ${form.id}")
                    onAddDynamicForm(form) 
                },
                onDeleteDynamicForm = { form, subIndex -> onDeleteDynamicForm(form, subIndex) }
            )
        recyclerView.adapter = formSectionAdapter
        }
        
        // Mark as in progress
        isSettingUpForms = true
        
        // Initialize with forms for this specific site (uses pinned team config version)
        lifecycleScope.launch {
            try {
            
            // OPTIMIZATION: Load configs once and reuse for both forms and sections
            val (originalConfigs, allForms) = withContext(Dispatchers.IO) {
                val configs = FormConfigLoader.loadForSite(this@SiteDetailActivity, site.name)
                
                // Convert to Forms once and reuse
                val forms = configs
                    .filter { it.id != "horizontal_line" } // Exclude dividers from Form list
                    .map { config ->
                        Form(
                            id = config.id,
                            name = config.name,
                            section = config.section,
                            description = config.description,
                            mandatory = config.mandatory,
                            isDynamic = config.isDynamic,
                            dynamicButtonName = config.dynamicButtonName
                        )
                    }
                configs to forms
            }
            
            // Build sections and forms by section from the already-loaded data
            val sections = allForms.map { it.section }.distinct()
            val baseFormsBySection = sections.associateWith { section ->
                allForms.filter { it.section == section }
            }
            
            // Cache for reuse in other methods
            cachedBaseFormsBySection = baseFormsBySection
            cachedSections = sections
            
            // Expand dynamic forms to include their instances
            // OPTIMIZATION: Batch all file I/O operations into a single withContext block
            val formFileHelper = FormFileHelper(this@SiteDetailActivity)
            
            // Pre-calculate instance indices for all forms (not just dynamic) to avoid repeated calculations
            // Map: (formId, section, orderInSection) -> instanceIndex
            val instanceIndexMap = mutableMapOf<Triple<String, String, Int>, Int>()
            baseFormsBySection.forEach { (section, forms) ->
                forms.forEachIndexed { orderInSection, form ->
                    var instanceIndex = 0
                    for (i in 0 until orderInSection) {
                        if (forms[i].id == form.id) {
                            instanceIndex++
                        }
                    }
                    instanceIndexMap[Triple(form.id, section, orderInSection)] = instanceIndex
                }
            }
            
            // Pre-calculate instance indices for dynamic forms only (for dynamic instance lookup)
            val dynamicFormInstanceIndices = mutableMapOf<Pair<String, String>, Int>() // (formId, section) -> instanceIndex
            baseFormsBySection.forEach { (section, forms) ->
                forms.forEachIndexed { orderInSection, form ->
                    if (form.isDynamic) {
                        dynamicFormInstanceIndices[Pair(form.id, section)] = instanceIndexMap[Triple(form.id, section, orderInSection)] ?: 0
                    }
                }
            }
            
            // OPTIMIZATION: Get statuses and file list ONCE, then use cached results
            val (allStatuses, cachedFiles) = withContext(Dispatchers.IO) {
                val result = formFileHelper.getAllFormStatusesWithCache(site.name)
                result.statusMap to result.fileList
            }
            
            // OPTIMIZATION: Use cached file list to get dynamic instances (no listFiles() calls)
            val allDynamicInstances = dynamicFormInstanceIndices.mapNotNull { (key, instanceIndex) ->
                val (formId, _) = key
                val instances = formFileHelper.getDynamicFormInstancesFromCache(cachedFiles, formId, instanceIndex)
                key to instances
            }.toMap()
            
            // Build expanded forms from files only (source of truth)
            // Unsaved instances will only exist in the adapter's current list until saved or page is refreshed
            // Also insert add buttons after each dynamic form group and dividers where specified
            val configsBySection = originalConfigs.groupBy { it.section }
            
            // OPTIMIZATION: Build form lookup map for O(1) access instead of linear search
            val formsBySectionAndOccurrence = baseFormsBySection.mapValues { (_, forms) ->
                val map = mutableMapOf<Pair<String, Int>, Form>() // (formId, occurrence) -> Form
                val occurrenceCount = mutableMapOf<String, Int>() // formId -> current occurrence
                forms.forEach { form ->
                    val occurrence = occurrenceCount.getOrDefault(form.id, 0)
                    map[Pair(form.id, occurrence)] = form
                    occurrenceCount[form.id] = occurrence + 1
                }
                map
            }
            
            val expandedFormsBySection = baseFormsBySection.mapValues { (section, _) ->
                val result = mutableListOf<FormListItem>()
                val sectionConfigs = configsBySection[section] ?: emptyList()
                val formsLookup = formsBySectionAndOccurrence[section] ?: emptyMap()
                
                // Create a map to track which forms we've processed
                // Since forms can appear multiple times (same ID), we need to track by occurrence
                val formOccurrenceMap = mutableMapOf<String, Int>() // formId -> occurrence count
                
                // Iterate through original configs to preserve order and insert dividers
                sectionConfigs.forEach { config ->
                    if (config.id == "horizontal_line") {
                        // Insert divider
                        result.add(FormListItem.DividerItem)
                    } else {
                        // Find the matching form by ID and occurrence using O(1) lookup
                        val occurrence = formOccurrenceMap.getOrDefault(config.id, 0)
                        formOccurrenceMap[config.id] = occurrence + 1
                        
                        // Use map lookup instead of linear search
                        val foundForm = formsLookup[Pair(config.id, occurrence)]
                        
                        if (foundForm != null) {
                            if (foundForm.isDynamic) {
                                val instances = allDynamicInstances[Pair(foundForm.id, section)] ?: emptyList()
                                
                                // For dynamic forms, show instances (at least one default instance #1)
                                val formInstances = if (instances.isEmpty()) {
                                    // Show default instance #1 (subIndex 0)
                                    listOf(foundForm.copy(name = "${foundForm.name} #1", isDynamic = true))
                                } else {
                                    // Show all existing instances from files
                                    instances.map { subIndex ->
                                        foundForm.copy(name = "${foundForm.name} #${subIndex + 1}", isDynamic = true)
                                    }
                                }
                                
                                // Add all instances
                                result.addAll(formInstances.map { FormListItem.FormItem(it) })
                                
                                // Add button after this dynamic form group
                                result.add(FormListItem.AddButtonItem(foundForm))
                            } else {
                                result.add(FormListItem.FormItem(foundForm))
                            }
                        }
                    }
                }
                
                result
            }
            
            // canAdd for dynamic forms is always true (instances list only contains sub-indices with saved files)
            val canAddCallback: (Form) -> Boolean = { form -> form.isDynamic }
            
            // Build sets of form keys for submitted and draft forms using expanded forms
            // OPTIMIZATION: Pre-compute form position to instanceIndex mapping to avoid recalculation
            val formPositionToInstanceIndex = baseFormsBySection.mapValues { (section, forms) ->
                val map = mutableMapOf<Int, Int>() // orderInSection -> instanceIndex
                forms.forEachIndexed { orderInSection, form ->
                    val instanceIndex = instanceIndexMap[Triple(form.id, section, orderInSection)] ?: 0
                    map[orderInSection] = instanceIndex
                }
                map
            }
            
            val submittedFormKeys = mutableSetOf<String>()
            val draftFormKeys = mutableSetOf<String>()
            
            expandedFormsBySection.forEach { (section, formListItems) ->
                formListItems.forEach innerForEach@{ item ->
                    // Only process FormItem, skip AddButtonItem and DividerItem
                    if (item !is FormListItem.FormItem) return@innerForEach
                    
                    val form = item.form
                    // Check if this is a dynamic form instance (name contains " #")
                    val isDynamicInstance = form.isDynamic && form.name.contains(" #")
                    val subIndex = if (isDynamicInstance) {
                        Regex("#(\\d+)$").find(form.name)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
                    } else {
                        null
                    }
                    
                    // Get base form to find orderInSection
                    val baseFormName = if (isDynamicInstance) {
                        form.name.substringBefore(" #")
                    } else {
                        form.name
                    }
                    val baseFormsInSection = baseFormsBySection[section] ?: emptyList()
                    val baseFormPosition = baseFormsInSection.indexOfFirst { it.id == form.id && it.name == baseFormName }
                        .takeIf { it >= 0 } ?: 0
                    
                    // Use pre-computed instance index instead of recalculating
                    val instanceIndex = formPositionToInstanceIndex[section]?.get(baseFormPosition) ?: 0
                    
                    // Use composite key to look up status (include sub-index for dynamic forms)
                    val formKey = if (subIndex != null) {
                        "${form.id}_${instanceIndex}_${subIndex}"
                    } else {
                        "${form.id}_${instanceIndex}"
                    }
                    val status = allStatuses[formKey]
                    
                    if (status != null) {
                        val (isSubmitted, hasDraft) = status
                        if (isSubmitted) {
                            submittedFormKeys.add(formKey)
                        } else if (hasDraft) {
                            draftFormKeys.add(formKey)
                        }
                    }
                }
            }
            
            formSectionAdapter?.setData(sections, expandedFormsBySection, submittedFormKeys, draftFormKeys, canAddCallback, baseFormsBySection)
            
            // Update canFinalize flag using already-loaded statuses (much faster than loading files individually)
            canFinalize = checkAllMandatoryFormsSubmittedFromStatuses(allStatuses, baseFormsBySection, instanceIndexMap)
            invalidateOptionsMenu() // Refresh menu to update finalize button state
            
            // Restore scroll position after data is loaded (if it was saved)
            // Use withContext(Main) to ensure we're on the main thread
            withContext(Dispatchers.Main) {
                if (savedScrollPosition > 0) {
                    // Wait for layout to complete before restoring scroll
                    recyclerView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            recyclerView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            restoreScrollPosition()
                        }
                    })
                }
            }
            } finally {
                // Mark as completed
                isSettingUpForms = false
                formsListInitialized = true
            }
        }
    }
    
    private fun loadFormCompletions() {
        // This function is now a no-op - setupFormsList() handles everything
        // We keep it for backward compatibility but it doesn't need to do anything
        // since setupFormsList() already loads form completions
    }
    
    private fun onAddDynamicForm(form: Form) {
        // Get base form (remove # suffix if present)
        val baseFormName = form.name.substringBefore(" #")
        val baseForm = form.copy(name = baseFormName)
        
        // OPTIMIZATION: Use cached forms data if available
        val baseFormsInSection = cachedBaseFormsBySection?.get(baseForm.section) ?: run {
            PredefinedForms.getFormsBySectionForSite(this, site.name, baseForm.section)
        }
        val baseFormPosition = baseFormsInSection.indexOfFirst { it.id == baseForm.id && it.name == baseFormName }
            .takeIf { it >= 0 } ?: 0
        
        // Count how many forms with same ID appear before this position (this is instanceIndex)
        var instanceIndex = 0
        for (i in 0 until baseFormPosition) {
            if (baseFormsInSection[i].id == baseForm.id) {
                instanceIndex++
            }
        }
        
        val formFileHelper = FormFileHelper(this)
        
        // OPTIMIZATION: Batch file operations
        lifecycleScope.launch(Dispatchers.IO) {
            val canAdd = formFileHelper.canAddDynamicFormInstance(site.name, baseForm.id, instanceIndex)
            if (!canAdd) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SiteDetailActivity, getString(R.string.please_save_instances_before_add), Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            
            // Get the next sub-index (from existing saved instances)
            val instances = formFileHelper.getDynamicFormInstances(site.name, baseForm.id, instanceIndex)
            val nextSubIndex = if (instances.isEmpty()) 0 else instances.maxOrNull()!! + 1
            
            withContext(Dispatchers.Main) {
                // Find the section adapter and form adapter to add the new instance directly
                val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewFormSections)
                
                // First, try to find by adapter position (more reliable than childCount)
                val sectionPosition = formSectionAdapter?.sectionsList?.indexOf(baseForm.section) ?: -1
                
                if (sectionPosition >= 0) {
                    // Try to find the viewholder for this section
                    // First try: find by adapter position
                    var viewHolder: FormSectionAdapter.SectionViewHolder? = recyclerView.findViewHolderForAdapterPosition(sectionPosition) as? FormSectionAdapter.SectionViewHolder
                    
                    // Fallback: iterate through visible children
                    if (viewHolder == null) {
                        for (i in 0 until recyclerView.childCount) {
                            val child = recyclerView.getChildAt(i)
                            val vh = recyclerView.getChildViewHolder(child)
                            if (vh is FormSectionAdapter.SectionViewHolder && vh.bindingAdapterPosition == sectionPosition) {
                                viewHolder = vh
                                break
                            }
                        }
                    }
                    
                    // Use a local val copy to allow smart cast
                    val foundViewHolder = viewHolder
                    if (foundViewHolder != null) {
                        foundViewHolder.addDynamicFormInstance(baseForm, nextSubIndex)
                    } else {
                        // Last resort: refresh the entire list
                        setupFormsList()
                    }
                }
            }
        }
        
    }
    
    private fun onDeleteDynamicForm(form: Form, subIndex: Int) {
        // Get base form (remove # suffix)
        val baseFormName = form.name.substringBefore(" #")
        val baseForm = form.copy(name = baseFormName)
        
        // OPTIMIZATION: Use cached forms data if available
        val formsInSection = cachedBaseFormsBySection?.get(baseForm.section) ?: run {
            PredefinedForms.getFormsBySectionForSite(this, site.name, baseForm.section)
        }
        val orderInSection = formsInSection.indexOfFirst { it.id == baseForm.id && it.name == baseForm.name }
            .takeIf { it >= 0 } ?: 0
        
        // Count how many forms with same ID appear before this position
        var instanceIndex = 0
        for (i in 0 until orderInSection) {
            if (formsInSection[i].id == baseForm.id) {
                instanceIndex++
            }
        }
        
        lifecycleScope.launch {
            val formFileHelper = FormFileHelper(this@SiteDetailActivity)
            
            // Check if this is the last instance
            // We need to check both file-based instances AND UI-based instances
            val allFileInstances = withContext(Dispatchers.IO) {
                formFileHelper.getDynamicFormInstances(site.name, baseForm.id, instanceIndex)
            }
            
            // Also check UI list for any instances that come after this one
            val currentFormsBySection = formSectionAdapter?.currentFormsBySection
            val currentFormsInSection = currentFormsBySection?.get(baseForm.section) ?: emptyList()
            val currentBaseFormName = baseForm.name
            val currentInstanceNumber = subIndex + 1
            val currentInstanceName = "$currentBaseFormName #$currentInstanceNumber"
            
            // Find this instance in the UI list (only check FormItem, skip AddButtonItem)
            val currentInstanceIndex = currentFormsInSection.indexOfFirst { item ->
                item is FormListItem.FormItem && 
                item.form.isDynamic && 
                item.form.id == baseForm.id && 
                item.form.name == currentInstanceName
            }
            
            // Check if there are any instances after this one in the UI list
            val hasLaterInstancesInUI = if (currentInstanceIndex >= 0) {
                currentFormsInSection.subList(currentInstanceIndex + 1, currentFormsInSection.size).any { item ->
                    item is FormListItem.FormItem &&
                    item.form.isDynamic && 
                    item.form.id == baseForm.id && 
                    item.form.name.contains(" #")
                }
            } else {
                false
            }
            
            // This is the last instance if:
            // 1. It's the only file-based instance, AND
            // 2. There are no later instances in the UI list
            val isLastInstance = allFileInstances.size == 1 && 
                                 allFileInstances.contains(subIndex) && 
                                 !hasLaterInstancesInUI
            
            if (isLastInstance) {
                // For the last instance, just delete the draft (not the whole instance)
                // Check if there's a draft to delete
                val hasDraft = withContext(Dispatchers.IO) {
                    formFileHelper.hasDraft(site.name, baseForm.id, instanceIndex, subIndex)
                }
                val isSubmitted = withContext(Dispatchers.IO) {
                    formFileHelper.isFormSubmitted(site.name, baseForm.id, instanceIndex, subIndex)
                }
                
                AppLogger.d("SiteDetailActivity", "onDeleteDynamicForm - isLastInstance=true, hasDraft=$hasDraft, isSubmitted=$isSubmitted")
                
                // Switch to main thread for UI operations
                withContext(Dispatchers.Main) {
                    if (hasDraft) {
                        AppLogger.d("SiteDetailActivity", "Showing confirmation dialog for clearing draft")
                        // Show confirmation dialog before clearing draft
                        AlertDialog.Builder(this@SiteDetailActivity)
                            .setTitle("Clear Draft")
                            .setMessage("Are you sure you want to clear the draft for this instance? The form will be reset to empty state.")
                            .setPositiveButton("Clear") { _, _ ->
                                AppLogger.d("SiteDetailActivity", "User confirmed clearing draft")
                                lifecycleScope.launch {
                                    val success = withContext(Dispatchers.IO) {
                                        formFileHelper.deleteForm(site.name, baseForm.id, instanceIndex, subIndex)
                                    }
                                    if (success) {
                                        AppLogger.d("SiteDetailActivity", "Draft deleted successfully, updating UI in place")
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@SiteDetailActivity, "Draft cleared", Toast.LENGTH_SHORT).show()
                                            
                                            // Update status in place - card should remain but become empty
                                            val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewFormSections)
                                            val sectionPosition = formSectionAdapter?.sectionsList?.indexOf(baseForm.section) ?: -1
                                            
                                            AppLogger.d("SiteDetailActivity", "Section position: $sectionPosition")
                                            
                                            if (sectionPosition >= 0) {
                                                var viewHolder: FormSectionAdapter.SectionViewHolder? = recyclerView.findViewHolderForAdapterPosition(sectionPosition) as? FormSectionAdapter.SectionViewHolder
                                                
                                                if (viewHolder == null) {
                                                    for (i in 0 until recyclerView.childCount) {
                                                        val child = recyclerView.getChildAt(i)
                                                        val vh = recyclerView.getChildViewHolder(child)
                                                        if (vh is FormSectionAdapter.SectionViewHolder && vh.bindingAdapterPosition == sectionPosition) {
                                                            viewHolder = vh
                                                            break
                                                        }
                                                    }
                                                }
                                                
                                                // Use a local val copy to allow smart cast
                                                val foundViewHolder = viewHolder
                                                if (foundViewHolder != null) {
                                                    AppLogger.d("SiteDetailActivity", "Found ViewHolder, updating status in place")
                                                    // Last instance: no instances after this one; update status in place
                                                    AppLogger.d("SiteDetailActivity", "Scheduling updateFormStatus with 100ms delay")
                                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                        if (isDestroyed || isFinishing) return@postDelayed
                                                        AppLogger.d("SiteDetailActivity", "Handler.postDelayed executed, calling updateFormStatus")
                                                        // Update status in place - card remains, just becomes empty
                                                        foundViewHolder.updateFormStatus(form, subIndex, instanceIndex, isCompleted = false, isDraft = false)
                                                        // Update the add button state (canAdd for dynamic forms is always true)
                                                        lifecycleScope.launch {
                                                            withContext(Dispatchers.Main) {
                                                                if (isDestroyed || isFinishing) return@withContext
                                                                foundViewHolder.updateAddButtonState(baseForm) { f ->
                                                                    f.id == baseForm.id && f.isDynamic
                                                                }
                                                            }
                                                        }
                                                    }, 100) // Small delay to ensure submitList callback completes
                                                } else {
                                                    AppLogger.d("SiteDetailActivity", "ViewHolder not found, falling back to full refresh")
                                                    // Fallback: full refresh
                                                    saveScrollPosition()
                                                    setupFormsList()
                                                    loadFormCompletions()
                                                }
                                            } else {
                                                AppLogger.d("SiteDetailActivity", "Section position invalid, falling back to full refresh")
                                                // Fallback: full refresh
                                                saveScrollPosition()
                                                setupFormsList()
                                                loadFormCompletions()
                                            }
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@SiteDetailActivity, "Failed to clear draft", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                            .setNegativeButton("Cancel") { _, _ ->
                                AppLogger.d("SiteDetailActivity", "User cancelled clearing draft")
                            }
                            .show()
                    } else if (isSubmitted) {
                        // Allow deleting submitted last instance (same as regular forms)
                        AlertDialog.Builder(this@SiteDetailActivity)
                            .setTitle("Delete Form Instance")
                            .setMessage("Are you sure you want to delete this submitted instance? This action cannot be undone.")
                            .setPositiveButton("Delete") { _, _ ->
                                lifecycleScope.launch {
                                    val success = withContext(Dispatchers.IO) {
                                        formFileHelper.deleteForm(site.name, baseForm.id, instanceIndex, subIndex)
                                    }
                                    if (success) {
                                        Toast.makeText(this@SiteDetailActivity, "Form instance deleted", Toast.LENGTH_SHORT).show()
                                        saveScrollPosition()
                                        setupFormsList()
                                        loadFormCompletions()
                                    } else {
                                        Toast.makeText(this@SiteDetailActivity, "Failed to delete form instance", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        // Already empty, nothing to do
                        Toast.makeText(this@SiteDetailActivity, "Form is already empty", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                // Not the last instance, delete the whole instance
                AlertDialog.Builder(this@SiteDetailActivity)
                    .setTitle("Delete Form Instance")
                    .setMessage("Are you sure you want to delete this instance of \"${baseForm.name}\"? This action cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            val success = withContext(Dispatchers.IO) {
                                formFileHelper.deleteDynamicFormInstance(site.name, baseForm.id, instanceIndex, subIndex)
                            }
                            if (success) {
                                Toast.makeText(this@SiteDetailActivity, "Form instance deleted", Toast.LENGTH_SHORT).show()
                                
                                // Find the section adapter and form adapter to delete the instance directly
                                val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewFormSections)
                                val sectionPosition = formSectionAdapter?.sectionsList?.indexOf(baseForm.section) ?: -1
                                
                                if (sectionPosition >= 0) {
                                    // Try to find the viewholder for this section
                                    var viewHolder: FormSectionAdapter.SectionViewHolder? = recyclerView.findViewHolderForAdapterPosition(sectionPosition) as? FormSectionAdapter.SectionViewHolder
                                    
                                    // Fallback: iterate through visible children
                                    if (viewHolder == null) {
                                        for (i in 0 until recyclerView.childCount) {
                                            val child = recyclerView.getChildAt(i)
                                            val vh = recyclerView.getChildViewHolder(child)
                                            if (vh is FormSectionAdapter.SectionViewHolder && vh.bindingAdapterPosition == sectionPosition) {
                                                viewHolder = vh
                                                break
                                            }
                                        }
                                    }
                                    
                                    // Use a local val copy to allow smart cast
                                    val foundViewHolder = viewHolder
                                    if (foundViewHolder != null) {
                                        // Delete the instance directly from the adapter (live update)
                                        foundViewHolder.deleteDynamicFormInstance(form, subIndex)
                                    } else {
                                        // Fallback: refresh the entire list
                                        saveScrollPosition()
                                        setupFormsList()
                                        loadFormCompletions()
                                    }
                                } else {
                                    // Fallback: refresh the entire list
                                    saveScrollPosition()
                                    setupFormsList()
                                    loadFormCompletions()
                                }
                            } else {
                                Toast.makeText(this@SiteDetailActivity, "Failed to delete form instance", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
    
    private fun onFormClick(form: Form) {
        // OPTIMIZATION: Use cached forms data if available
        val baseFormsInSection = cachedBaseFormsBySection?.get(form.section) ?: run {
            PredefinedForms.getFormsBySectionForSite(this, site.name, form.section)
        }
        
        // Check if this is a dynamic form instance (name contains " #")
        val isDynamicInstance = form.isDynamic && form.name.contains(" #")
        val baseFormName = if (isDynamicInstance) {
            form.name.substringBefore(" #")
        } else {
            form.name
        }
        
        // Find the actual position of the base form in the list
        val positionInSection = baseFormsInSection.indexOfFirst { 
            it.id == form.id && it.name == baseFormName
        }.takeIf { it >= 0 } ?: 0
        
        // Count how many forms with the same ID appear before this position
        var instanceIndex = 0
        for (i in 0 until positionInSection) {
            if (baseFormsInSection[i].id == form.id) {
                instanceIndex++
            }
        }
        
        // Extract sub-index from name if it's a dynamic instance
        val subIndex = if (isDynamicInstance) {
            Regex("#(\\d+)$").find(form.name)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
        } else {
            null
        }
        
        android.util.Log.w("SiteDetailActivity", "Form clicked: id=${form.id}, name=${form.name}, position=$positionInSection, instanceIndex=$instanceIndex, subIndex=$subIndex")
        
        // Open form editing activity
        val intent = Intent(this, FormEditActivity::class.java).apply {
            putExtra("siteName", site.name)
            putExtra("formId", form.id)
            putExtra("orderInSection", instanceIndex)
            if (subIndex != null) {
                putExtra("subIndex", subIndex)
            }
            putExtra("isReadOnly", site.status == com.trec.trecollect.data.SiteStatus.FINISHED)
        }
        android.util.Log.w("SiteDetailActivity", "Starting FormEditActivity with orderInSection=$instanceIndex")
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
    
    companion object {
        private const val PREFS_OFFLINE_MAPS_PROMPT = "site_detail_offline_maps_prompt"
        private const val KEY_OFFLINE_MAPS_PROMPT_SHOWN_SITES = "offline_maps_prompt_shown_sites"
    }
}

