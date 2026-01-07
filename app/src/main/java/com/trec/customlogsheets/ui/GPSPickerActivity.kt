package com.trec.customlogsheets.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.trec.customlogsheets.R
import org.osmdroid.api.IMapController
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay

class GPSPickerActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var buttonUseLocation: MaterialButton
    private lateinit var buttonGetCurrentLocation: MaterialButton
    
    private var selectedLatitude: Double = 0.0
    private var selectedLongitude: Double = 0.0
    private var mapController: IMapController? = null
    private var marker: Marker? = null
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gps_picker)
        
        // Initialize OSMDroid
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        
        // Enable tile caching for offline support
        // OSMDroid will cache tiles automatically when loaded online
        // Cached tiles will be available when offline
        Configuration.getInstance().osmdroidBasePath = cacheDir
        Configuration.getInstance().osmdroidTileCache = cacheDir
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        setupToolbar()
        setupViews()
        loadInitialLocation()
    }
    
    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Select GPS Location"
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }
    
    private fun setupViews() {
        mapView = findViewById(R.id.mapView)
        buttonUseLocation = findViewById(R.id.buttonUseLocation)
        buttonGetCurrentLocation = findViewById(R.id.buttonGetCurrentLocation)
        
        // Configure map
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapController = mapView.controller
        mapController?.setZoom(15.0)
        
        // Handle map clicks
        val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p?.let {
                    updateMarker(it.latitude, it.longitude)
                }
                return true
            }
            
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let {
                    updateMarker(it.latitude, it.longitude)
                }
                return true
            }
        })
        mapView.overlays.add(0, mapEventsOverlay)
        
        buttonGetCurrentLocation.setOnClickListener {
            getCurrentLocation()
        }
        
        buttonUseLocation.setOnClickListener {
            if (selectedLatitude != 0.0 && selectedLongitude != 0.0) {
                val resultIntent = Intent().apply {
                    putExtra("fieldId", intent.getStringExtra("fieldId"))
                    putExtra("latitude", selectedLatitude)
                    putExtra("longitude", selectedLongitude)
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            } else {
                Toast.makeText(this, "Please select a location on the map", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun loadInitialLocation() {
        // Try to load existing coordinates from intent
        val existingLat = intent.getDoubleExtra("latitude", 0.0)
        val existingLon = intent.getDoubleExtra("longitude", 0.0)
        
        if (existingLat != 0.0 && existingLon != 0.0) {
            updateMarker(existingLat, existingLon)
            mapController?.setCenter(GeoPoint(existingLat, existingLon))
        } else {
            // Try to get current location
            getCurrentLocation()
        }
    }
    
    private fun updateMarker(latitude: Double, longitude: Double) {
        selectedLatitude = latitude
        selectedLongitude = longitude
        
        // Remove existing marker
        mapView.overlays.remove(marker)
        
        // Add new marker
        val geoPoint = GeoPoint(latitude, longitude)
        marker = Marker(mapView).apply {
            position = geoPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Selected Location"
            snippet = "Lat: $latitude, Lon: $longitude"
        }
        mapView.overlays.add(marker)
        mapView.invalidate()
        
        // Center map on marker
        mapController?.setCenter(geoPoint)
    }
    
    private fun getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_LOCATION_PERMISSION
            )
            return
        }
        
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMaxUpdateDelayMillis(2000)
            .build()
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    updateMarker(location.latitude, location.longitude)
                    fusedLocationClient.removeLocationUpdates(this)
                }
            }
        }
        
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            mainLooper
        )
        
        // Also try to get last known location immediately
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                updateMarker(it.latitude, it.longitude)
            }
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            getCurrentLocation()
        } else {
            Toast.makeText(this, "Location permission is required", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        mapView.onPause()
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
    }
    
    companion object {
        private const val REQUEST_LOCATION_PERMISSION = 1001
    }
}

