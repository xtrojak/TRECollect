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
import com.trec.customlogsheets.data.SamplingSite
import com.trec.customlogsheets.ui.MainViewModel
import com.trec.customlogsheets.ui.MainViewModelFactory
import com.trec.customlogsheets.ui.SamplingSiteAdapter
import com.trec.customlogsheets.ui.SettingsActivity
import com.trec.customlogsheets.ui.SiteDetailActivity
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
            onItemClick = { site -> navigateToDetail(site) }
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
                            editText.text?.clear()
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "Site created successfully",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        is MainViewModel.CreateSiteResult.Error -> {
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
}
