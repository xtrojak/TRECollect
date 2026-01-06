package com.trec.customlogsheets.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.AppDatabase
import com.trec.customlogsheets.data.Form
import com.trec.customlogsheets.data.PredefinedForms
import com.trec.customlogsheets.data.SamplingSite
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SiteDetailActivity : AppCompatActivity() {
    private lateinit var site: SamplingSite
    private lateinit var formSectionAdapter: FormSectionAdapter
    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_site_detail)
        
        database = AppDatabase.getDatabase(applicationContext)
        
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        val siteExtra = intent.getParcelableExtra<SamplingSite>("site")
        if (siteExtra == null) {
            finish()
            return
        }
        site = siteExtra
        
        val siteNameText = findViewById<TextView>(R.id.textViewSiteName)
        siteNameText.text = site.name
        supportActionBar?.title = site.name
        
        setupFormsList()
        loadFormCompletions()
    }
    
    private fun setupFormsList() {
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewFormSections)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        formSectionAdapter = FormSectionAdapter { form ->
            onFormClick(form)
        }
        recyclerView.adapter = formSectionAdapter
        
        // Initialize with all forms
        val sections = PredefinedForms.sections
        val formsBySection = sections.associateWith { section ->
            PredefinedForms.getFormsBySection(section)
        }
        formSectionAdapter.setData(sections, formsBySection, emptySet())
    }
    
    private fun loadFormCompletions() {
        lifecycleScope.launch {
            val completions = database.formCompletionDao().getCompletionsForSite(site.id).first()
            val completedFormIds = completions.map { it.formId }.toSet()
            
            val sections = PredefinedForms.sections
            val formsBySection = sections.associateWith { section ->
                PredefinedForms.getFormsBySection(section)
            }
            formSectionAdapter.setData(sections, formsBySection, completedFormIds)
        }
    }
    
    private fun onFormClick(form: Form) {
        // For now, clicking does nothing as requested
        // This is where form filling functionality will be added later
        Toast.makeText(this, "Form: ${form.name} (clicking does nothing for now)", Toast.LENGTH_SHORT).show()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

