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
import com.trec.customlogsheets.data.SamplingSite
import com.trec.customlogsheets.ui.MainViewModel
import com.trec.customlogsheets.ui.MainViewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SiteDetailActivity : AppCompatActivity() {
    private lateinit var site: SamplingSite
    private lateinit var formSectionAdapter: FormSectionAdapter
    private lateinit var database: AppDatabase
    private lateinit var viewModel: MainViewModel
    private lateinit var siteNameText: TextView

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
        
        val siteExtra = intent.getParcelableExtra<SamplingSite>("site")
        if (siteExtra == null) {
            finish()
            return
        }
        site = siteExtra
        
        siteNameText = findViewById<TextView>(R.id.textViewSiteName)
        siteNameText.text = site.name
        supportActionBar?.title = site.name
        
        setupFormsList()
        loadFormCompletions()
    }
    
    override fun onResume() {
        super.onResume()
        // Reload form completions when returning from form editing
        loadFormCompletions()
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
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
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
                            Toast.makeText(this@SiteDetailActivity, "Site deleted", Toast.LENGTH_SHORT).show()
                            // Navigate back to main activity
                            navigateToHome()
                        }
                        is MainViewModel.DeleteSiteResult.Error -> {
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
        formSectionAdapter.setData(sections, formsBySection, emptySet())
    }
    
    private fun loadFormCompletions() {
        lifecycleScope.launch {
            // Check which forms are submitted using FormFileHelper
            val formFileHelper = com.trec.customlogsheets.data.FormFileHelper(this@SiteDetailActivity)
            val submittedFormIds = formFileHelper.getSubmittedForms(site.name).toSet()
            
            val sections = PredefinedForms.getSections(this@SiteDetailActivity)
            val formsBySection = sections.associateWith { section ->
                PredefinedForms.getFormsBySection(this@SiteDetailActivity, section)
            }
            formSectionAdapter.setData(sections, formsBySection, submittedFormIds)
        }
    }
    
    private fun onFormClick(form: Form) {
        // Open form editing activity
        val intent = Intent(this, FormEditActivity::class.java).apply {
            putExtra("siteName", site.name)
            putExtra("formId", form.id)
        }
        startActivity(intent)
    }
    
    override fun onSupportNavigateUp(): Boolean {
        navigateToHome()
        return true
    }
}

