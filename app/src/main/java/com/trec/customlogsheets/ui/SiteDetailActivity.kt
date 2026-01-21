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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SiteDetailActivity : AppCompatActivity() {
    private lateinit var site: SamplingSite
    private var formSectionAdapter: FormSectionAdapter? = null
    private lateinit var database: AppDatabase
    private lateinit var viewModel: MainViewModel
    private lateinit var siteNameText: TextView
    private lateinit var cardUploadStatus: com.google.android.material.card.MaterialCardView
    private lateinit var imageViewUploadStatus: ImageView
    private lateinit var textViewUploadStatus: TextView
    private lateinit var buttonRetryUpload: MaterialButton
    private var canFinalize: Boolean = false
    private var savedScrollPosition: Int = 0 // Save scroll position when refreshing

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
        // Save scroll position before refreshing
        saveScrollPosition()
        
        // Reload forms list to pick up any new dynamic instances
        // Only call setupFormsList once - it will handle everything
        setupFormsList()
        
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
        // Update menu to refresh finalize button state
        invalidateOptionsMenu()
        // Note: Scroll position will be restored in setupFormsList() after data is loaded
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
        val mandatoryForms = withContext(Dispatchers.IO) {
            PredefinedForms.getMandatoryFormsForSite(this@SiteDetailActivity, site.name)
        }
        if (mandatoryForms.isEmpty()) {
            return true // No mandatory forms, can finalize
        }
        
        val formFileHelper = com.trec.customlogsheets.data.FormFileHelper(this)
        
        // Get all forms grouped by section to check all instances
        val sections = withContext(Dispatchers.IO) {
            PredefinedForms.getSectionsForSite(this@SiteDetailActivity, site.name)
        }
        val formsBySection = sections.associateWith { section ->
            PredefinedForms.getFormsBySectionForSite(this@SiteDetailActivity, site.name, section)
        }
        
        // Check if all mandatory form instances are submitted
        val mandatoryFormIds = mandatoryForms.map { it.id }.toSet()
        
        return formsBySection.values.flatten()
            .filter { mandatoryFormIds.contains(it.id) }
            .all { form ->
                // Calculate orderInSection for this instance
                val formsInSection = formsBySection[form.section] ?: emptyList()
                val formPosition = formsInSection.indexOfFirst { it.id == form.id && it.name == form.name }
                var instanceIndex = 0
                if (formPosition >= 0) {
                    for (i in 0 until formPosition) {
                        if (formsInSection[i].id == form.id) {
                            instanceIndex++
                        }
                    }
                }
                // Check if this specific instance is submitted
                formFileHelper.isFormSubmitted(site.name, form.id, instanceIndex)
        }
    }
    
    private fun showFinalizeConfirmationDialog() {
        lifecycleScope.launch {
            val mandatoryForms = withContext(Dispatchers.IO) {
                PredefinedForms.getMandatoryFormsForSite(this@SiteDetailActivity, site.name)
            }
            val formFileHelper = com.trec.customlogsheets.data.FormFileHelper(this@SiteDetailActivity)
            
            // Get all forms grouped by section to check all instances
            val sections = withContext(Dispatchers.IO) {
                PredefinedForms.getSectionsForSite(this@SiteDetailActivity, site.name)
            }
            val formsBySection = sections.associateWith { section ->
                PredefinedForms.getFormsBySectionForSite(this@SiteDetailActivity, site.name, section)
            }
            
            // Check which mandatory form instances are missing
            val mandatoryFormIds = mandatoryForms.map { it.id }.toSet()
            val missingForms = formsBySection.values.flatten()
                .filter { mandatoryFormIds.contains(it.id) }
                .filter { form ->
                    // Calculate orderInSection for this instance
                    val formsInSection = formsBySection[form.section] ?: emptyList()
                    val formPosition = formsInSection.indexOfFirst { it.id == form.id && it.name == form.name }
                    var instanceIndex = 0
                    if (formPosition >= 0) {
                        for (i in 0 until formPosition) {
                            if (formsInSection[i].id == form.id) {
                                instanceIndex++
                            }
                        }
                    }
                    // Check if this specific instance is NOT submitted
                    !formFileHelper.isFormSubmitted(site.name, form.id, instanceIndex)
                }
            
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
        
        // Initialize with forms for this specific site (uses pinned team config version)
        lifecycleScope.launch {
            val totalStartTime = System.currentTimeMillis()
            
            val sections = withContext(Dispatchers.IO) {
                PredefinedForms.getSectionsForSite(this@SiteDetailActivity, site.name)
            }
            val baseFormsBySection = sections.associateWith { section ->
                PredefinedForms.getFormsBySectionForSite(this@SiteDetailActivity, site.name, section)
            }
            
            // Expand dynamic forms to include their instances
            // OPTIMIZATION: Batch all file I/O operations into a single withContext block
            val formFileHelper = com.trec.customlogsheets.data.FormFileHelper(this@SiteDetailActivity)
            
            // Pre-calculate instance indices for all dynamic forms to avoid repeated calculations
            val dynamicFormInstanceIndices = mutableMapOf<Pair<String, String>, Int>() // (formId, section) -> instanceIndex
            baseFormsBySection.forEach { (section, forms) ->
                forms.forEachIndexed { orderInSection, form ->
                    if (form.isDynamic) {
                        var instanceIndex = 0
                        for (i in 0 until orderInSection) {
                            if (forms[i].id == form.id) {
                                instanceIndex++
                            }
                        }
                        dynamicFormInstanceIndices[Pair(form.id, section)] = instanceIndex
                    }
                }
            }
            
            // OPTIMIZATION: Get statuses and file list ONCE, then use cached results
            val getAllStatusesStartTime = System.currentTimeMillis()
            val (allStatuses, cachedFiles) = withContext(Dispatchers.IO) {
                val statusesStartTime = System.currentTimeMillis()
                val result = formFileHelper.getAllFormStatusesWithCache(site.name)
                val statusesEndTime = System.currentTimeMillis()
                AppLogger.d("SiteDetailActivity", "getAllFormStatusesWithCache took ${statusesEndTime - statusesStartTime}ms, found ${result.fileList.size} files")
                result.statusMap to result.fileList
            }
            val getAllStatusesEndTime = System.currentTimeMillis()
            AppLogger.d("SiteDetailActivity", "getAllFormStatusesWithCache total took ${getAllStatusesEndTime - getAllStatusesStartTime}ms")
            
            // OPTIMIZATION: Use cached file list to get dynamic instances (no listFiles() calls)
            val getInstancesStartTime = System.currentTimeMillis()
            val allDynamicInstances = dynamicFormInstanceIndices.mapNotNull { (key, instanceIndex) ->
                val (formId, _) = key
                val instanceStartTime = System.currentTimeMillis()
                val instances = formFileHelper.getDynamicFormInstancesFromCache(cachedFiles, formId, instanceIndex)
                val instanceEndTime = System.currentTimeMillis()
                AppLogger.d("SiteDetailActivity", "getDynamicFormInstancesFromCache for $formId took ${instanceEndTime - instanceStartTime}ms")
                key to instances
            }.toMap()
            val getInstancesEndTime = System.currentTimeMillis()
            AppLogger.d("SiteDetailActivity", "Total getDynamicFormInstancesFromCache calls took ${getInstancesEndTime - getInstancesStartTime}ms for ${dynamicFormInstanceIndices.size} forms")
            
            // Build expanded forms from files only (source of truth)
            // Unsaved instances will only exist in the adapter's current list until saved or page is refreshed
            // Also insert add buttons after each dynamic form group
            val expandedFormsBySection = baseFormsBySection.mapValues { (section, forms) ->
                val result = mutableListOf<com.trec.customlogsheets.ui.FormListItem>()
                
                forms.forEach { form ->
                    if (form.isDynamic) {
                        val instances = allDynamicInstances[Pair(form.id, section)] ?: emptyList()
                        
                        // For dynamic forms, show instances (at least one default instance #1)
                        val formInstances = if (instances.isEmpty()) {
                            // Show default instance #1 (subIndex 0)
                            listOf(form.copy(name = "${form.name} #1", isDynamic = true))
                        } else {
                            // Show all existing instances from files
                            instances.map { subIndex ->
                                form.copy(name = "${form.name} #${subIndex + 1}", isDynamic = true)
                            }
                        }
                        
                        // Add all instances
                        result.addAll(formInstances.map { com.trec.customlogsheets.ui.FormListItem.FormItem(it) })
                        
                        // Add button after this dynamic form group
                        result.add(com.trec.customlogsheets.ui.FormListItem.AddButtonItem(form))
                    } else {
                        result.add(com.trec.customlogsheets.ui.FormListItem.FormItem(form))
                    }
                }
                
                result
            }
            
            // OPTIMIZATION: Use cached status map and instances to calculate canAdd (no XML loading)
            val canAddStartTime = System.currentTimeMillis()
            val canAddCache = mutableMapOf<Pair<String, Int>, Boolean>() // (formId, instanceIndex) -> canAdd
            dynamicFormInstanceIndices.forEach { (key, instanceIndex) ->
                val (formId, _) = key
                val instances = allDynamicInstances[key] ?: emptyList()
                canAddCache[Pair(formId, instanceIndex)] = formFileHelper.canAddDynamicFormInstanceFromStatus(
                    allStatuses, formId, instanceIndex, instances
                )
            }
            val canAddEndTime = System.currentTimeMillis()
            AppLogger.d("SiteDetailActivity", "Total canAddDynamicFormInstanceFromStatus calls took ${canAddEndTime - canAddStartTime}ms for ${dynamicFormInstanceIndices.size} forms")
            
            // Set up canAddDynamicForm callback with cached results
            val canAddCallback: (Form) -> Boolean = { form ->
                if (!form.isDynamic) {
                    false
                } else {
                    // Get base form (remove # suffix if present)
                    val baseFormName = form.name.substringBefore(" #")
                    val baseFormsInSection = baseFormsBySection[form.section] ?: emptyList()
                    val orderInSection = baseFormsInSection.indexOfFirst { it.id == form.id && (it.name == baseFormName || it.name == form.name) }
                        .takeIf { it >= 0 } ?: 0
                    var instanceIndex = 0
                    for (i in 0 until orderInSection) {
                        if (baseFormsInSection[i].id == form.id) {
                            instanceIndex++
                        }
                    }
                    // Use cached result instead of calling canAddDynamicFormInstance
                    canAddCache[Pair(form.id, instanceIndex)] ?: false
                }
            }
            
            // Build sets of form keys for submitted and draft forms using expanded forms
            val submittedFormKeys = mutableSetOf<String>()
            val draftFormKeys = mutableSetOf<String>()
            
            expandedFormsBySection.forEach { (section, formListItems) ->
                formListItems.forEach { item ->
                    // Only process FormItem, skip AddButtonItem
                    if (item !is com.trec.customlogsheets.ui.FormListItem.FormItem) return@forEach
                    
                    val form = item.form
                    // Check if this is a dynamic form instance (name contains " #")
                    val isDynamicInstance = form.isDynamic && form.name.contains(" #")
                    val subIndex = if (isDynamicInstance) {
                        Regex("#(\\d+)$").find(form.name)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
                    } else {
                        null
                    }
                    
                    // Get base form to calculate orderInSection
                    val baseFormName = if (isDynamicInstance) {
                        form.name.substringBefore(" #")
                    } else {
                        form.name
                    }
                    val baseFormsInSection = baseFormsBySection[section] ?: emptyList()
                    val baseFormPosition = baseFormsInSection.indexOfFirst { it.id == form.id && it.name == baseFormName }
                        .takeIf { it >= 0 } ?: 0
                    
                    // Calculate orderInSection for the base form
                    var instanceIndex = 0
                    for (i in 0 until baseFormPosition) {
                        if (baseFormsInSection[i].id == form.id) {
                            instanceIndex++
                        }
                    }
                    
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
            
            // Update canFinalize flag
            canFinalize = checkAllMandatoryFormsSubmitted()
            invalidateOptionsMenu() // Refresh menu to update finalize button state
            
            val totalEndTime = System.currentTimeMillis()
            AppLogger.d("SiteDetailActivity", "Total setupFormsList took ${totalEndTime - totalStartTime}ms")
            
            // Restore scroll position after data is loaded (if it was saved)
            // Use withContext(Main) to ensure we're on the main thread
            withContext(Dispatchers.Main) {
                if (savedScrollPosition > 0) {
                    // Wait for layout to complete before restoring scroll
                    val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewFormSections)
                    recyclerView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            recyclerView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            restoreScrollPosition()
                        }
                    })
                }
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
        
        // Use the same calculation as setupFormsList() - get base forms for the section
        val baseFormsInSection = PredefinedForms.getFormsBySectionForSite(this, site.name, baseForm.section)
        val baseFormPosition = baseFormsInSection.indexOfFirst { it.id == baseForm.id && it.name == baseFormName }
            .takeIf { it >= 0 } ?: 0
        
        // Count how many forms with same ID appear before this position (this is instanceIndex)
        var instanceIndex = 0
        for (i in 0 until baseFormPosition) {
            if (baseFormsInSection[i].id == baseForm.id) {
                instanceIndex++
            }
        }
        
        val formFileHelper = com.trec.customlogsheets.data.FormFileHelper(this)
        
        // OPTIMIZATION: Batch file operations
        lifecycleScope.launch(Dispatchers.IO) {
            val canAdd = formFileHelper.canAddDynamicFormInstance(site.name, baseForm.id, instanceIndex)
            if (!canAdd) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SiteDetailActivity, "Please save all existing instances before adding a new one", Toast.LENGTH_LONG).show()
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
        
        val formsInSection = PredefinedForms.getFormsBySectionForSite(this, site.name, baseForm.section)
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
            val formFileHelper = com.trec.customlogsheets.data.FormFileHelper(this@SiteDetailActivity)
            
            // Check if this is the last instance
            // We need to check both file-based instances AND UI-based instances
            val allFileInstances = withContext(Dispatchers.IO) {
                formFileHelper.getDynamicFormInstances(site.name, baseForm.id, instanceIndex)
            }
            
            // Also check UI list for any instances that come after this one
            val currentFormsBySection = formSectionAdapter?.currentFormsBySection
            val formsInSection = currentFormsBySection?.get(baseForm.section) ?: emptyList()
            val baseFormName = baseForm.name
            val currentInstanceNumber = subIndex + 1
            val currentInstanceName = "$baseFormName #$currentInstanceNumber"
            
            // Find this instance in the UI list (only check FormItem, skip AddButtonItem)
            val currentInstanceIndex = formsInSection.indexOfFirst { item ->
                item is com.trec.customlogsheets.ui.FormListItem.FormItem && 
                item.form.isDynamic && 
                item.form.id == baseForm.id && 
                item.form.name == currentInstanceName
            }
            
            // Check if there are any instances after this one in the UI list
            val hasLaterInstancesInUI = if (currentInstanceIndex >= 0) {
                formsInSection.subList(currentInstanceIndex + 1, formsInSection.size).any { item ->
                    item is com.trec.customlogsheets.ui.FormListItem.FormItem &&
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
                                        formFileHelper.deleteForm(site.name, baseForm.id, instanceIndex, subIndex, isDraft = true)
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
                                                    // Remove any unsaved instances that come after this one
                                                    val instancesRemoved = foundViewHolder.removeUnsavedInstancesAfter(baseForm, subIndex)
                                                    
                                                    // Use Handler.postDelayed to ensure the list update from removeUnsavedInstancesAfter completes
                                                    // before we update the status and rebind (submitList is asynchronous)
                                                    AppLogger.d("SiteDetailActivity", "Scheduling updateFormStatus with 100ms delay")
                                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                        AppLogger.d("SiteDetailActivity", "Handler.postDelayed executed, calling updateFormStatus")
                                                        // Update status in place - card remains, just becomes empty
                                                        foundViewHolder.updateFormStatus(form, subIndex, instanceIndex, isCompleted = false, isDraft = false)
                                                        // Also update the add button state - recalculate canAdd after draft deletion
                                                        lifecycleScope.launch {
                                                            val instances = withContext(Dispatchers.IO) {
                                                                formFileHelper.getDynamicFormInstances(site.name, baseForm.id, instanceIndex)
                                                            }
                                                            val statusMap = withContext(Dispatchers.IO) {
                                                                formFileHelper.getAllFormStatusesWithCache(site.name).statusMap
                                                            }
                                                            val canAdd = formFileHelper.canAddDynamicFormInstanceFromStatus(
                                                                statusMap, baseForm.id, instanceIndex, instances
                                                            )
                                                            withContext(Dispatchers.Main) {
                                                                foundViewHolder.updateAddButtonState(baseForm) { f ->
                                                                    if (f.id == baseForm.id && f.isDynamic) canAdd else false
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
                                        formFileHelper.deleteForm(site.name, baseForm.id, instanceIndex, subIndex, isDraft = false)
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
        // Get base forms list (without # suffix)
        val baseFormsInSection = PredefinedForms.getFormsBySectionForSite(this, site.name, form.section)
        
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
        
        android.util.Log.d("SiteDetailActivity", "Form clicked: id=${form.id}, name=${form.name}, position=$positionInSection, instanceIndex=$instanceIndex, subIndex=$subIndex")
        
        // Open form editing activity
        val intent = Intent(this, FormEditActivity::class.java).apply {
            putExtra("siteName", site.name)
            putExtra("formId", form.id)
            putExtra("orderInSection", instanceIndex)
            if (subIndex != null) {
                putExtra("subIndex", subIndex)
            }
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

