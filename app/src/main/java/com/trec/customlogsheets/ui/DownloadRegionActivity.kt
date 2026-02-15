@file:Suppress("DEPRECATION")

package com.trec.customlogsheets.ui

import android.app.ProgressDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.OfflineMapRegion
import com.trec.customlogsheets.data.OfflineMapsManager
import com.trec.customlogsheets.util.AppLogger
import kotlinx.coroutines.launch
import org.osmdroid.api.IMapController
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

/**
 * Activity for downloading a new offline map region
 * Users can pan and zoom the map, and the bounding box is determined by the visible map area.
 */
class DownloadRegionActivity : AppCompatActivity() {
    private lateinit var mapsManager: OfflineMapsManager
    private lateinit var mapView: MapView
    private lateinit var editTextName: EditText
    private lateinit var textMinLat: TextView
    private lateinit var textMaxLat: TextView
    private lateinit var textMinLon: TextView
    private lateinit var textMaxLon: TextView
    private lateinit var editTextMinZoom: EditText
    private lateinit var editTextMaxZoom: EditText
    private lateinit var buttonDownload: MaterialButton
    private var mapController: IMapController? = null
    private var boundingBoxOverlay: Polyline? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_region)
        
        // Initialize OSMDroid
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidBasePath = cacheDir
        Configuration.getInstance().osmdroidTileCache = cacheDir
        
        mapsManager = OfflineMapsManager(this)
        
        setupToolbar()
        setupViews()
    }
    
    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
    
    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }
    
    private fun setupViews() {
        mapView = findViewById(R.id.mapView)
        editTextName = findViewById(R.id.editTextName)
        textMinLat = findViewById(R.id.textMinLat)
        textMaxLat = findViewById(R.id.textMaxLat)
        textMinLon = findViewById(R.id.textMinLon)
        textMaxLon = findViewById(R.id.textMaxLon)
        editTextMinZoom = findViewById(R.id.editTextMinZoom)
        editTextMaxZoom = findViewById(R.id.editTextMaxZoom)
        buttonDownload = findViewById(R.id.buttonDownload)
        
        // Configure map
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapController = mapView.controller
        mapController?.setZoom(10.0)
        mapController?.setCenter(GeoPoint(0.0, 0.0))
        
        // Set default zoom levels
        editTextMinZoom.setText(getString(R.string.default_zoom_min))
        editTextMaxZoom.setText(getString(R.string.default_zoom_max))
        
        // Update bounding box when map moves
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                updateBoundingBox()
                return false
            }
            
            override fun onZoom(event: ZoomEvent?): Boolean {
                updateBoundingBox()
                return false
            }
        })
        
        // Initial bounding box update
        mapView.post {
            updateBoundingBox()
        }
        
        buttonDownload.setOnClickListener {
            downloadRegion()
        }
    }
    
    private fun updateBoundingBox() {
        val boundingBox = mapView.boundingBox
        if (boundingBox != null) {
            val minLat = boundingBox.latSouth
            val maxLat = boundingBox.latNorth
            val minLon = boundingBox.lonWest
            val maxLon = boundingBox.lonEast
            
            textMinLat.text = String.format(Locale.getDefault(), "%.6f", minLat)
            textMaxLat.text = String.format(Locale.getDefault(), "%.6f", maxLat)
            textMinLon.text = String.format(Locale.getDefault(), "%.6f", minLon)
            textMaxLon.text = String.format(Locale.getDefault(), "%.6f", maxLon)
            
            // Draw bounding box overlay
            drawBoundingBox(boundingBox)
        }
    }
    
    private fun drawBoundingBox(boundingBox: BoundingBox) {
        // Remove existing overlay
        boundingBoxOverlay?.let {
            mapView.overlays.remove(it)
        }
        
        // Create rectangle overlay
        val points = listOf(
            GeoPoint(boundingBox.latNorth, boundingBox.lonWest),
            GeoPoint(boundingBox.latNorth, boundingBox.lonEast),
            GeoPoint(boundingBox.latSouth, boundingBox.lonEast),
            GeoPoint(boundingBox.latSouth, boundingBox.lonWest),
            GeoPoint(boundingBox.latNorth, boundingBox.lonWest) // Close the rectangle
        )
        
        boundingBoxOverlay = Polyline().apply {
            setPoints(points)
            @Suppress("DEPRECATION")
            color = android.graphics.Color.RED
            @Suppress("DEPRECATION")
            width = 5.0f
        }
        mapView.overlays.add(boundingBoxOverlay)
        mapView.invalidate()
    }
    
    private fun downloadRegion() {
        val name = editTextName.text?.toString()?.trim() ?: ""
        if (name.isEmpty()) {
            Toast.makeText(this, getString(R.string.please_enter_region_name), Toast.LENGTH_SHORT).show()
            return
        }
        
        // Get bounding box from visible map area
        val boundingBox = mapView.boundingBox
        if (boundingBox == null) {
            Toast.makeText(this, getString(R.string.unable_to_determine_bounds), Toast.LENGTH_SHORT).show()
            return
        }
        
        val minLat = boundingBox.latSouth
        val maxLat = boundingBox.latNorth
        val minLon = boundingBox.lonWest
        val maxLon = boundingBox.lonEast
        
        val minZoom = editTextMinZoom.text?.toString()?.toIntOrNull()
        val maxZoom = editTextMaxZoom.text?.toString()?.toIntOrNull()
        
        if (minZoom == null || maxZoom == null || minZoom < 0 || maxZoom > 18 || minZoom > maxZoom) {
            Toast.makeText(this, getString(R.string.please_enter_valid_zoom), Toast.LENGTH_SHORT).show()
            return
        }
        
        val region = OfflineMapRegion(
            name = name,
            minLatitude = minLat,
            maxLatitude = maxLat,
            minLongitude = minLon,
            maxLongitude = maxLon,
            minZoom = minZoom,
            maxZoom = maxZoom
        )
        
        // Show confirmation dialog with estimated tile count
        val estimatedTiles = estimateTileCount(region)
        val estimatedMB = estimatedTiles * 0.02 // Rough estimate: ~20KB per tile
        val minLatStr = String.format(Locale.getDefault(), "%.6f", minLat)
        val minLonStr = String.format(Locale.getDefault(), "%.6f", minLon)
        val maxLatStr = String.format(Locale.getDefault(), "%.6f", maxLat)
        val maxLonStr = String.format(Locale.getDefault(), "%.6f", maxLon)
        val estimatedMBStr = String.format(Locale.getDefault(), "%.1f", estimatedMB)
        
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_download)
            .setMessage(getString(R.string.confirm_download_message, name, minLatStr, minLonStr, maxLatStr, maxLonStr, minZoom, maxZoom, estimatedTiles, estimatedMBStr))
            .setPositiveButton(R.string.download_button) { _, _ ->
                startDownload(region)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun estimateTileCount(region: OfflineMapRegion): Int {
        var total = 0
        for (zoom in region.minZoom..region.maxZoom) {
            val tilesPerSide = 1 shl zoom // 2^zoom
            val latTiles = ((region.maxLatitude - region.minLatitude) / 180.0 * tilesPerSide).toInt() + 1
            val lonTiles = ((region.maxLongitude - region.minLongitude) / 360.0 * tilesPerSide).toInt() + 1
            total += latTiles * lonTiles
        }
        return total
    }
    
    @Suppress("DEPRECATION")
    private fun startDownload(region: OfflineMapRegion) {
        val progressDialog = ProgressDialog(this).apply {
            setTitle("Downloading Map Region")
            setMessage("Please wait...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = true
            setCancelable(false)
        }
        progressDialog.show()
        
        lifecycleScope.launch {
            val success = mapsManager.downloadRegion(region) { downloaded, total ->
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    if (total > 0) {
                        progressDialog.isIndeterminate = false
                        progressDialog.max = total
                        progressDialog.progress = downloaded
                        progressDialog.setMessage(this@DownloadRegionActivity.getString(R.string.download_progress_tiles, downloaded, total))
                    } else {
                        progressDialog.setMessage(this@DownloadRegionActivity.getString(R.string.download_progress_downloading, downloaded))
                    }
                }
            }
            if (isDestroyed || isFinishing) return@launch
            progressDialog.dismiss()
            if (success) {
                AppLogger.i("DownloadRegionActivity", "Map region download completed successfully: name='${region.name}'")
                Toast.makeText(this@DownloadRegionActivity, getString(R.string.region_downloaded_success), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                AppLogger.e("DownloadRegionActivity", "Map region download failed: name='${region.name}'")
                Toast.makeText(this@DownloadRegionActivity, getString(R.string.region_download_error), Toast.LENGTH_LONG).show()
            }
        }
    }
}

