@file:Suppress("DEPRECATION")

package com.trec.customlogsheets.ui

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.OfflineMapRegion
import com.trec.customlogsheets.data.OfflineMapsManager
import com.trec.customlogsheets.data.SettingsPreferences
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class OfflineMapsActivity : AppCompatActivity() {
    private lateinit var mapsManager: OfflineMapsManager
    private lateinit var settingsPreferences: SettingsPreferences
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RegionAdapter
    private lateinit var buttonDownloadRegion: MaterialButton
    private lateinit var buttonSelectAll: MaterialButton
    private lateinit var textStorageUsed: TextView
    private lateinit var editTextExpiryDays: TextInputEditText
    private lateinit var buttonSaveExpiry: MaterialButton
    
    private var isSelectionMode = false
    private val selectedRegions = mutableSetOf<String>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_maps)
        
        mapsManager = OfflineMapsManager(this)
        settingsPreferences = SettingsPreferences(this)
        
        setupToolbar()
        setupViews()
        loadRegions()
        loadStorageInfo()
        loadExpirySettings()
        
        // Cleanup expired regions on startup
        lifecycleScope.launch {
            val deletedCount = mapsManager.cleanupExpiredRegions()
            if (deletedCount > 0) {
                Toast.makeText(this@OfflineMapsActivity, "Deleted $deletedCount expired region(s)", Toast.LENGTH_SHORT).show()
                loadRegions()
                loadStorageInfo()
            }
        }
    }
    
    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.offline_maps_menu, menu)
        return true
    }
    
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val selectItem = menu.findItem(R.id.action_select)
        val cancelItem = menu.findItem(R.id.action_cancel_selection)
        val deleteSelectedItem = menu.findItem(R.id.action_delete_selected)
        
        if (isSelectionMode) {
            selectItem?.isVisible = false
            cancelItem?.isVisible = true
            deleteSelectedItem?.isVisible = true
        } else {
            selectItem?.isVisible = true
            cancelItem?.isVisible = false
            deleteSelectedItem?.isVisible = false
        }
        
        return super.onPrepareOptionsMenu(menu)
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select -> {
                enableSelectionMode()
                true
            }
            R.id.action_cancel_selection -> {
                disableSelectionMode()
                true
            }
            R.id.action_delete_selected -> {
                deleteSelectedRegions()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun setupViews() {
        recyclerView = findViewById(R.id.recyclerViewRegions)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = RegionAdapter(
            mapsManager = mapsManager,
            onRegionClick = { region ->
                if (isSelectionMode) {
                    toggleRegionSelection(region)
                } else {
                    showRegionDetails(region)
                }
            },
            onRegionLongClick = { region ->
                if (!isSelectionMode) {
                    enableSelectionMode()
                    toggleRegionSelection(region)
                }
                true
            },
            isSelectionMode = { isSelectionMode },
            isSelected = { region -> selectedRegions.contains(region.id) }
        )
        recyclerView.adapter = adapter
        
        buttonDownloadRegion = findViewById(R.id.buttonDownloadRegion)
        buttonDownloadRegion.setOnClickListener {
            showDownloadRegionDialog()
        }
        
        buttonSelectAll = findViewById(R.id.buttonSelectAll)
        buttonSelectAll.setOnClickListener {
            selectAllRegions()
        }
        
        textStorageUsed = findViewById(R.id.textStorageUsed)
        
        editTextExpiryDays = findViewById(R.id.editTextExpiryDays)
        buttonSaveExpiry = findViewById(R.id.buttonSaveExpiry)
        buttonSaveExpiry.setOnClickListener {
            saveExpirySettings()
        }
    }
    
    private fun loadRegions() {
        val regions = mapsManager.getAllRegions()
        adapter.submitList(regions)
        
        if (regions.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_offline_maps_yet), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun loadStorageInfo() {
        lifecycleScope.launch {
            val storageBytes = mapsManager.getStorageUsed()
            val storageMB = storageBytes / (1024.0 * 1024.0)
            textStorageUsed.text = getString(R.string.storage_used_mb, String.format(Locale.getDefault(), "%.2f", storageMB))
        }
    }
    
    private fun loadExpirySettings() {
        val expiryDays = settingsPreferences.getMapExpiryDays()
        editTextExpiryDays.setText(expiryDays.toString())
    }
    
    private fun saveExpirySettings() {
        val daysText = editTextExpiryDays.text?.toString() ?: "0"
        val days = try {
            daysText.toLong().coerceAtLeast(0)
        } catch (e: NumberFormatException) {
            Toast.makeText(this, getString(R.string.invalid_number), Toast.LENGTH_SHORT).show()
            return
        }
        
        settingsPreferences.setMapExpiryDays(days)
        Toast.makeText(this, getString(R.string.expiry_settings_saved), Toast.LENGTH_SHORT).show()
        
        // Update existing regions with new expiry
        lifecycleScope.launch {
            val regions = mapsManager.getAllRegions()
            for (region in regions) {
                val expiresAt = if (days > 0) {
                    region.downloadedAt + (days * 24 * 60 * 60 * 1000)
                } else {
                    null
                }
                val updatedRegion = region.copy(expiresAt = expiresAt)
                // Re-save with new expiry
                mapsManager.saveRegion(updatedRegion)
            }
            loadRegions()
        }
    }
    
    private fun enableSelectionMode() {
        isSelectionMode = true
        selectedRegions.clear()
        invalidateOptionsMenu()
        updateSelectAllButton()
        adapter.notifyDataSetChanged()
    }
    
    private fun disableSelectionMode() {
        isSelectionMode = false
        selectedRegions.clear()
        invalidateOptionsMenu()
        updateSelectAllButton()
        adapter.notifyDataSetChanged()
    }
    
    private fun updateSelectAllButton() {
        buttonSelectAll.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        if (isSelectionMode) {
            val allSelected = adapter.currentList.isNotEmpty() && 
                adapter.currentList.all { selectedRegions.contains(it.id) }
            buttonSelectAll.text = if (allSelected) "Deselect All" else "Select All"
        }
    }
    
    private fun selectAllRegions() {
        val allSelected = adapter.currentList.isNotEmpty() && 
            adapter.currentList.all { selectedRegions.contains(it.id) }
        
        if (allSelected) {
            // Deselect all
            selectedRegions.clear()
        } else {
            // Select all
            selectedRegions.clear()
            adapter.currentList.forEach { region ->
                selectedRegions.add(region.id)
            }
        }
        updateSelectAllButton()
        adapter.notifyDataSetChanged()
    }
    
    private fun toggleRegionSelection(region: OfflineMapRegion) {
        val wasSelected = selectedRegions.contains(region.id)
        if (wasSelected) {
            selectedRegions.remove(region.id)
        } else {
            selectedRegions.add(region.id)
        }
        
        // Find position by ID (more reliable than object equality)
        val position = adapter.currentList.indexOfFirst { it.id == region.id }
        if (position >= 0) {
            adapter.notifyItemChanged(position)
        } else {
            // If position not found, refresh all items
            adapter.notifyDataSetChanged()
        }
        
        updateSelectAllButton()
        
        android.util.Log.d("OfflineMapsActivity", "Toggled region '${region.name}' (${region.id}): ${if (wasSelected) "deselected" else "selected"}. Total selected: ${selectedRegions.size}")
    }
    
    private fun showRegionDetails(region: OfflineMapRegion) {
        // Open map preview activity
        val intent = Intent(this, MapPreviewActivity::class.java).apply {
            putExtra("region", region.toJson())
        }
        startActivity(intent)
    }
    
    private fun showDeleteConfirmation(regions: List<OfflineMapRegion>) {
        val count = regions.size
        AlertDialog.Builder(this)
            .setTitle("Delete Region${if (count > 1) "s" else ""}")
            .setMessage("Are you sure you want to delete ${if (count == 1) "this region" else "$count regions"}? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                deleteRegions(regions)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun deleteSelectedRegions() {
        val regionsToDelete = adapter.currentList.filter { selectedRegions.contains(it.id) }
        if (regionsToDelete.isEmpty()) {
            Toast.makeText(this, "No regions selected", Toast.LENGTH_SHORT).show()
            return
        }
        showDeleteConfirmation(regionsToDelete)
    }
    
    
    @Suppress("DEPRECATION")
    private fun deleteRegions(regions: List<OfflineMapRegion>) {
        val progressDialog = ProgressDialog.show(this, "Deleting", "Please wait...", true, false)
        
        lifecycleScope.launch {
            val deletedCount = mapsManager.deleteRegions(regions)
            progressDialog.dismiss()
            
            if (deletedCount > 0) {
                Toast.makeText(this@OfflineMapsActivity, "Deleted $deletedCount region(s)", Toast.LENGTH_SHORT).show()
                disableSelectionMode()
                loadRegions()
                loadStorageInfo()
            } else {
                Toast.makeText(this@OfflineMapsActivity, "Error deleting regions", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showDownloadRegionDialog() {
        val intent = Intent(this, DownloadRegionActivity::class.java)
        startActivity(intent)
    }
    
    override fun onResume() {
        super.onResume()
        loadRegions()
        loadStorageInfo()
    }
}

// Adapter for regions list
class RegionAdapter(
    private val mapsManager: OfflineMapsManager,
    private val onRegionClick: (OfflineMapRegion) -> Unit,
    private val onRegionLongClick: (OfflineMapRegion) -> Boolean,
    private val isSelectionMode: () -> Boolean,
    private val isSelected: (OfflineMapRegion) -> Boolean
) : androidx.recyclerview.widget.ListAdapter<OfflineMapRegion, RegionAdapter.RegionViewHolder>(RegionDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RegionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_offline_map_region, parent, false)
        return RegionViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: RegionViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }
    
    inner class RegionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textRegionName: TextView = itemView.findViewById(R.id.textRegionName)
        private val textRegionBounds: TextView = itemView.findViewById(R.id.textRegionBounds)
        private val textZoomLevels: TextView = itemView.findViewById(R.id.textZoomLevels)
        private val textRegionSize: TextView = itemView.findViewById(R.id.textRegionSize)
        private val textDownloadDate: TextView = itemView.findViewById(R.id.textDownloadDate)
        private val textExpiryDate: TextView = itemView.findViewById(R.id.textExpiryDate)
        private val imageExpired: android.widget.ImageView = itemView.findViewById(R.id.imageExpired)
        private val checkboxSelect: CheckBox = itemView.findViewById(R.id.checkboxSelect)
        
        fun bind(region: OfflineMapRegion, @Suppress("UNUSED_PARAMETER") position: Int) {
            textRegionName.text = region.name
            val minLat = String.format(Locale.getDefault(), "%.4f", region.minLatitude)
            val minLon = String.format(Locale.getDefault(), "%.4f", region.minLongitude)
            val maxLat = String.format(Locale.getDefault(), "%.4f", region.maxLatitude)
            val maxLon = String.format(Locale.getDefault(), "%.4f", region.maxLongitude)
            textRegionBounds.text = itemView.context.getString(R.string.bounds_format, minLat, minLon, maxLat, maxLon)
            textZoomLevels.text = itemView.context.getString(R.string.zoom_range, region.minZoom, region.maxZoom)
            
            // Calculate and display size
            val sizeBytes = mapsManager.estimateRegionSize(region)
            val sizeMB = sizeBytes / (1024.0 * 1024.0)
            textRegionSize.text = itemView.context.getString(R.string.size_mb, String.format(Locale.getDefault(), "%.2f", sizeMB))
            
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            textDownloadDate.text = itemView.context.getString(R.string.downloaded_at, dateFormat.format(Date(region.downloadedAt)))
            
            if (region.expiresAt != null) {
                textExpiryDate.text = itemView.context.getString(R.string.expires_at, dateFormat.format(Date(region.expiresAt)))
                textExpiryDate.visibility = View.VISIBLE
                
                if (region.isExpired()) {
                    imageExpired.visibility = View.VISIBLE
                    textExpiryDate.setTextColor(itemView.context.getColor(android.R.color.holo_red_dark))
                } else {
                    imageExpired.visibility = View.GONE
                    textExpiryDate.setTextColor(itemView.context.getColor(android.R.color.darker_gray))
                }
            } else {
                textExpiryDate.text = itemView.context.getString(R.string.expires_never)
                textExpiryDate.visibility = View.VISIBLE
                imageExpired.visibility = View.GONE
            }
            
            // Selection mode
            val selectionMode = isSelectionMode()
            checkboxSelect.visibility = if (selectionMode) View.VISIBLE else View.GONE
            
            // Clear any existing listeners to avoid duplicates
            checkboxSelect.setOnCheckedChangeListener(null)
            checkboxSelect.setOnClickListener(null)
            
            // Set the checked state without triggering listeners
            checkboxSelect.isChecked = isSelected(region)
            
            // Use OnCheckedChangeListener - but we need to sync with actual selection state
            // The checkbox state might be out of sync, so we check the actual selection state
            checkboxSelect.setOnCheckedChangeListener { _, isChecked ->
                // Check the actual current selection state
                val actuallySelected = isSelected(region)
                // Only toggle if the checkbox state doesn't match the actual selection state
                if (isChecked != actuallySelected) {
                    onRegionClick(region)
                }
            }
            
            itemView.setOnClickListener {
                // In selection mode, clicking the item toggles selection
                onRegionClick(region)
            }
            
            itemView.setOnLongClickListener {
                onRegionLongClick(region)
            }
        }
    }
    
    class RegionDiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<OfflineMapRegion>() {
        override fun areItemsTheSame(oldItem: OfflineMapRegion, newItem: OfflineMapRegion): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: OfflineMapRegion, newItem: OfflineMapRegion): Boolean {
            return oldItem == newItem
        }
    }
}

