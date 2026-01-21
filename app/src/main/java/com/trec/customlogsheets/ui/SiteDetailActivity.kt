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
        // Reload forms list to pick up any new dynamic instances
        setupFormsList()
        loadFormCompletions()
        
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
    
    private fun setupFormsList() {
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewFormSections)
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
        
        // Initialize with forms for this specific site (uses pinned team config version)
        lifecycleScope.launch {
            val sections = withContext(Dispatchers.IO) {
                PredefinedForms.getSectionsForSite(this@SiteDetailActivity, site.name)
            }
            val baseFormsBySection = sections.associateWith { section ->
                PredefinedForms.getFormsBySectionForSite(this@SiteDetailActivity, site.name, section)
            }
            
            // Expand dynamic forms to include their instances
            val formFileHelper = com.trec.customlogsheets.data.FormFileHelper(this@SiteDetailActivity)
            val expandedFormsBySection = baseFormsBySection.mapValues { (_, forms) ->
                forms.flatMap { form ->
                    if (form.isDynamic) {
                        // Get all instances of this dynamic form
                        val formsInSection = baseFormsBySection[form.section] ?: emptyList()
                        val orderInSection = formsInSection.indexOfFirst { it.id == form.id && it.name == form.name }
                            .takeIf { it >= 0 } ?: 0
                        
                        // Count how many forms with same ID appear before this position
                        var instanceIndex = 0
                        for (i in 0 until orderInSection) {
                            if (formsInSection[i].id == form.id) {
                                instanceIndex++
                            }
                        }
                        
                        val instances = withContext(Dispatchers.IO) {
                            formFileHelper.getDynamicFormInstances(site.name, form.id, instanceIndex)
                        }
                        
                        // For dynamic forms, show instances (at least one default instance #1)
                        if (instances.isEmpty()) {
                            // Show default instance #1 (subIndex 0)
                            listOf(form.copy(name = "${form.name} #1", isDynamic = true))
                        } else {
                            // Show all existing instances
                            instances.map { subIndex ->
                                form.copy(name = "${form.name} #${subIndex + 1}", isDynamic = true)
                            }
                        }
                    } else {
                        listOf(form)
                    }
                }
            }
            
            // Set up canAddDynamicForm callback
            val canAddCallback: (Form) -> Boolean = { form ->
                if (!form.isDynamic) {
                    false
                } else {
                    // Get base form (remove # suffix if present)
                    val baseFormName = form.name.substringBefore(" #")
                    val baseFormsInSection = PredefinedForms.getFormsBySectionForSite(this@SiteDetailActivity, site.name, form.section)
                    val orderInSection = baseFormsInSection.indexOfFirst { it.id == form.id && (it.name == baseFormName || it.name == form.name) }
                        .takeIf { it >= 0 } ?: 0
                    var instanceIndex = 0
                    for (i in 0 until orderInSection) {
                        if (baseFormsInSection[i].id == form.id) {
                            instanceIndex++
                        }
                    }
                    formFileHelper.canAddDynamicFormInstance(site.name, form.id, instanceIndex)
                }
            }
            
            // Load form completions using the expanded forms
            val allStatuses = withContext(Dispatchers.IO) {
                formFileHelper.getAllFormStatuses(site.name)
            }
            
            // Build sets of form keys for submitted and draft forms using expanded forms
            val submittedFormKeys = mutableSetOf<String>()
            val draftFormKeys = mutableSetOf<String>()
            
            expandedFormsBySection.forEach { (section, forms) ->
                forms.forEach { form ->
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
            
            formSectionAdapter.setData(sections, expandedFormsBySection, submittedFormKeys, draftFormKeys, canAddCallback, baseFormsBySection)
            
            // Update canFinalize flag
            canFinalize = checkAllMandatoryFormsSubmitted()
            invalidateOptionsMenu() // Refresh menu to update finalize button state
        }
    }
    
    private fun loadFormCompletions() {
        // Reload the forms list to get updated statuses
        setupFormsList()
    }
    
    private fun onAddDynamicForm(form: Form) {
        AppLogger.d("SiteDetailActivity", "onAddDynamicForm called for form: ${form.id}, name=${form.name}")
        
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
        
        AppLogger.d("SiteDetailActivity", "Calculated: baseFormPosition=$baseFormPosition, instanceIndex=$instanceIndex")
        
        val formFileHelper = com.trec.customlogsheets.data.FormFileHelper(this)
        if (!formFileHelper.canAddDynamicFormInstance(site.name, baseForm.id, instanceIndex)) {
            AppLogger.d("SiteDetailActivity", "Cannot add instance - not all instances are saved")
            Toast.makeText(this, "Please save all existing instances before adding a new one", Toast.LENGTH_LONG).show()
            return
        }
        
        // Get the next sub-index (from existing saved instances)
        val instances = formFileHelper.getDynamicFormInstances(site.name, baseForm.id, instanceIndex)
        val nextSubIndex = if (instances.isEmpty()) 0 else instances.maxOrNull()!! + 1
        
        AppLogger.d("SiteDetailActivity", "Next subIndex: $nextSubIndex, existing instances: $instances")
        
        // Find the section adapter and form adapter to add the new instance directly
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewFormSections)
        
        // First, try to find by adapter position (more reliable than childCount)
        val sectionPosition = formSectionAdapter.sectionsList.indexOf(baseForm.section)
        AppLogger.d("SiteDetailActivity", "Looking for section: ${baseForm.section}, position=$sectionPosition")
        
        if (sectionPosition >= 0) {
            // Try to find the viewholder for this section
            var viewHolder: FormSectionAdapter.SectionViewHolder? = null
            
            // First try: find by adapter position
            viewHolder = recyclerView.findViewHolderForAdapterPosition(sectionPosition) as? FormSectionAdapter.SectionViewHolder
            
            // Fallback: iterate through visible children
            if (viewHolder == null) {
                AppLogger.d("SiteDetailActivity", "ViewHolder not found via adapter position, trying visible children")
                for (i in 0 until recyclerView.childCount) {
                    val child = recyclerView.getChildAt(i)
                    val vh = recyclerView.getChildViewHolder(child)
                    if (vh is FormSectionAdapter.SectionViewHolder && vh.adapterPosition == sectionPosition) {
                        viewHolder = vh
                        AppLogger.d("SiteDetailActivity", "Found viewholder in visible children at index $i")
                        break
                    }
                }
            }
            
            if (viewHolder != null) {
                AppLogger.d("SiteDetailActivity", "Found viewholder, adding instance")
                viewHolder.addDynamicFormInstance(baseForm, nextSubIndex)
            } else {
                AppLogger.w("SiteDetailActivity", "ViewHolder not found for section position $sectionPosition. RecyclerView childCount=${recyclerView.childCount}, sectionsList size=${formSectionAdapter.sectionsList.size}")
                // Last resort: refresh the entire list
                AppLogger.d("SiteDetailActivity", "Refreshing forms list as fallback")
                setupFormsList()
            }
        } else {
            AppLogger.w("SiteDetailActivity", "Section not found in sections list: ${baseForm.section}")
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
        
        // Show confirmation dialog
        AlertDialog.Builder(this)
            .setTitle("Delete Form Instance")
            .setMessage("Are you sure you want to delete this instance of \"${baseForm.name}\"? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val formFileHelper = com.trec.customlogsheets.data.FormFileHelper(this@SiteDetailActivity)
                    val success = withContext(Dispatchers.IO) {
                        formFileHelper.deleteDynamicFormInstance(site.name, baseForm.id, instanceIndex, subIndex)
                    }
                    if (success) {
                        Toast.makeText(this@SiteDetailActivity, "Form instance deleted", Toast.LENGTH_SHORT).show()
                        // Reload forms list and completions
                        setupFormsList()
                        loadFormCompletions()
                    } else {
                        Toast.makeText(this@SiteDetailActivity, "Failed to delete form instance", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

