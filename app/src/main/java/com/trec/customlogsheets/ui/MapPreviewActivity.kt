package com.trec.customlogsheets.ui

import android.os.Bundle
import com.google.android.material.appbar.MaterialToolbar
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.OfflineMapRegion
import org.osmdroid.api.IMapController
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/**
 * Activity to preview an offline map region on a map.
 * Shows the bounding box rectangle and auto-fits the view to display the entire region.
 */
class MapPreviewActivity : androidx.appcompat.app.AppCompatActivity() {
    private lateinit var mapView: MapView
    private var mapController: IMapController? = null
    private var boundingBoxOverlay: Polyline? = null
    private lateinit var region: OfflineMapRegion
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_preview)
        
        // Get region from intent
        val regionJson = intent.getStringExtra("region")
        val parsedRegion = if (regionJson != null) {
            OfflineMapRegion.fromJson(regionJson)
        } else {
            null
        }
        
        if (parsedRegion == null) {
            finish()
            return
        }
        
        region = parsedRegion
        
        // Initialize OSMDroid
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidBasePath = cacheDir
        Configuration.getInstance().osmdroidTileCache = cacheDir
        
        setupToolbar()
        setupMap()
    }
    
    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = region.name
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }
    
    private fun setupMap() {
        mapView = findViewById(R.id.mapView)
        
        // Configure map
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapController = mapView.controller
        
        // Create bounding box from region
        val boundingBox = BoundingBox(
            region.maxLatitude,
            region.maxLongitude,
            region.minLatitude,
            region.minLongitude
        )
        
        // Draw bounding box rectangle
        drawBoundingBox(boundingBox)
        
        // Auto-fit map to show entire bounding box with some padding
        mapView.post {
            mapView.zoomToBoundingBox(boundingBox, true)
        }
    }
    
    private fun drawBoundingBox(boundingBox: BoundingBox) {
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
            width = 8.0f
        }
        mapView.overlays.add(boundingBoxOverlay)
        mapView.invalidate()
    }
    
    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
}

