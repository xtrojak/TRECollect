package com.trec.customlogsheets

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.trec.customlogsheets.data.AppDatabase
import com.trec.customlogsheets.data.SamplingSite
import com.trec.customlogsheets.ui.MainViewModel
import com.trec.customlogsheets.ui.MainViewModelFactory
import com.trec.customlogsheets.ui.SamplingSiteAdapter
import com.trec.customlogsheets.ui.SiteDetailActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: MainViewModel
    private lateinit var ongoingAdapter: SamplingSiteAdapter
    private lateinit var finishedAdapter: SamplingSiteAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        val database = AppDatabase.getDatabase(applicationContext)
        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(database)
        )[MainViewModel::class.java]
        
        setupRecyclerViews()
        setupCreateButton()
        observeData()
    }
    
    private fun setupRecyclerViews() {
        ongoingAdapter = SamplingSiteAdapter(
            onItemClick = { site -> navigateToDetail(site) },
            onRenameClick = { site -> showRenameDialog(site) },
            showRenameButton = true
        )
        
        finishedAdapter = SamplingSiteAdapter(
            onItemClick = { site -> navigateToDetail(site) },
            onRenameClick = { site -> showRenameDialog(site) },
            showRenameButton = false
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
                viewModel.createSite(siteName)
                editText.text?.clear()
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
    
    private fun showRenameDialog(site: SamplingSite) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_rename_site, null)
        val editText = dialogView.findViewById<EditText>(R.id.editTextNewName)
        editText.setText(site.name)
        editText.selectAll()
        
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Rename") { _, _ ->
                val newName = editText.text.toString()
                if (newName.isNotBlank()) {
                    viewModel.renameSite(site, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun navigateToDetail(site: SamplingSite) {
        val intent = Intent(this, SiteDetailActivity::class.java).apply {
            putExtra("site", site)
        }
        startActivity(intent)
    }
}
