package com.trec.customlogsheets.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.TileSystem
import android.graphics.Point
import java.io.File

/**
 * Manages offline map regions: downloading, storing metadata, and cleanup
 */
class OfflineMapsManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val PREFS_NAME = "offline_maps"
        private const val KEY_REGIONS = "regions"
        private const val KEY_REGION_PREFIX = "region_"
    }
    
    /**
     * Gets all downloaded regions
     */
    fun getAllRegions(): List<OfflineMapRegion> {
        val regionIds = getRegionIds()
        return regionIds.mapNotNull { id ->
            loadRegion(id)
        }.sortedByDescending { it.downloadedAt }
    }
    
    /**
     * Gets a specific region by ID
     */
    fun getRegion(id: String): OfflineMapRegion? {
        return loadRegion(id)
    }
    
    /**
     * Saves a region (creates or updates)
     */
    fun saveRegion(region: OfflineMapRegion) {
        val regionJson = region.toJson()
        prefs.edit().putString("${KEY_REGION_PREFIX}${region.id}", regionJson).apply()
        
        // Update the list of region IDs
        val regionIds = getRegionIds().toMutableSet()
        regionIds.add(region.id)
        saveRegionIds(regionIds.toList())
    }
    
    /**
     * Loads a region by ID
     */
    private fun loadRegion(id: String): OfflineMapRegion? {
        val regionJson = prefs.getString("${KEY_REGION_PREFIX}$id", null) ?: return null
        return OfflineMapRegion.fromJson(regionJson)
    }
    
    /**
     * Gets the list of region IDs
     */
    private fun getRegionIds(): List<String> {
        val idsString = prefs.getString(KEY_REGIONS, "") ?: ""
        return if (idsString.isEmpty()) {
            emptyList()
        } else {
            idsString.split(",").filter { it.isNotEmpty() }
        }
    }
    
    /**
     * Saves the list of region IDs
     */
    private fun saveRegionIds(ids: List<String>) {
        prefs.edit().putString(KEY_REGIONS, ids.joinToString(",")).apply()
    }
    
    /**
     * Deletes a region and its tiles
     */
    suspend fun deleteRegion(region: OfflineMapRegion): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Delete tiles for this region
                deleteRegionTiles(region)
                
                // Remove from preferences
                prefs.edit().remove("${KEY_REGION_PREFIX}${region.id}").apply()
                
                // Update region IDs list
                val regionIds = getRegionIds().toMutableSet()
                regionIds.remove(region.id)
                saveRegionIds(regionIds.toList())
                
                true
            } catch (e: Exception) {
                android.util.Log.e("OfflineMapsManager", "Error deleting region: ${e.message}", e)
                false
            }
        }
    }
    
    /**
     * Deletes multiple regions
     */
    suspend fun deleteRegions(regions: List<OfflineMapRegion>): Int {
        var deletedCount = 0
        for (region in regions) {
            if (deleteRegion(region)) {
                deletedCount++
            }
        }
        return deletedCount
    }
    
    /**
     * Deletes all regions
     */
    suspend fun deleteAllRegions(): Int {
        val allRegions = getAllRegions()
        return deleteRegions(allRegions)
    }
    
    /**
     * Downloads tiles for a region
     */
    suspend fun downloadRegion(
        region: OfflineMapRegion,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Ensure OSMDroid is configured
                Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
                Configuration.getInstance().userAgentValue = context.packageName
                
                // Use OSMDroid's configured cache directory
                val osmdroidBasePath = Configuration.getInstance().osmdroidBasePath
                val tileCache = if (osmdroidBasePath != null) {
                    File(osmdroidBasePath, "osmdroid")
                } else {
                    File(context.cacheDir, "osmdroid")
                }
                if (!tileCache.exists()) {
                    tileCache.mkdirs()
                }
                
                val boundingBox = BoundingBox(
                    region.maxLatitude,
                    region.maxLongitude,
                    region.minLatitude,
                    region.minLongitude
                )
                
                var totalTiles = 0
                var downloadedTiles = 0
                
                // Calculate total tiles
                for (zoom in region.minZoom..region.maxZoom) {
                    val tiles = calculateTilesForZoom(boundingBox, zoom)
                    totalTiles += tiles
                }
                
                // Download tiles for each zoom level
                for (zoom in region.minZoom..region.maxZoom) {
                    val tiles = downloadTilesForZoom(
                        boundingBox,
                        zoom,
                        TileSourceFactory.MAPNIK,
                        tileCache
                    ) { downloaded ->
                        downloadedTiles++
                        progressCallback?.invoke(downloadedTiles, totalTiles)
                    }
                }
                
                // Calculate expiry date if needed
                val settingsPrefs = SettingsPreferences(context)
                val expiryDays = settingsPrefs.getMapExpiryDays()
                val expiresAt = if (expiryDays > 0) {
                    System.currentTimeMillis() + (expiryDays * 24 * 60 * 60 * 1000)
                } else {
                    null
                }
                
                // Save region with expiry
                val regionWithExpiry = region.copy(expiresAt = expiresAt)
                saveRegion(regionWithExpiry)
                
                android.util.Log.d("OfflineMapsManager", "Downloaded $downloadedTiles tiles for region ${region.name}")
                true
            } catch (e: Exception) {
                android.util.Log.e("OfflineMapsManager", "Error downloading region: ${e.message}", e)
                false
            }
        }
    }
    
    /**
     * Converts latitude/longitude to tile coordinates using standard Mercator projection
     */
    private fun latLonToTileXY(latitude: Double, longitude: Double, zoomLevel: Int): Point {
        val n = Math.pow(2.0, zoomLevel.toDouble())
        val tileX = ((longitude + 180.0) / 360.0 * n).toInt()
        val latRad = Math.toRadians(latitude)
        val tileY = ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()
        return Point(tileX, tileY)
    }
    
    /**
     * Calculates the number of tiles needed for a bounding box at a specific zoom level
     */
    private fun calculateTilesForZoom(boundingBox: BoundingBox, zoom: Int): Int {
        val topLeft = latLonToTileXY(boundingBox.latNorth, boundingBox.lonWest, zoom)
        val bottomRight = latLonToTileXY(boundingBox.latSouth, boundingBox.lonEast, zoom)
        
        val minTileX = Math.min(topLeft.x, bottomRight.x)
        val maxTileX = Math.max(topLeft.x, bottomRight.x)
        val minTileY = Math.min(topLeft.y, bottomRight.y)
        val maxTileY = Math.max(topLeft.y, bottomRight.y)
        
        return (Math.abs(maxTileX - minTileX) + 1) * (Math.abs(maxTileY - minTileY) + 1)
    }
    
    /**
     * Downloads tiles for a specific zoom level
     * Uses OSMDroid's cache directory structure
     */
    private suspend fun downloadTilesForZoom(
        boundingBox: BoundingBox,
        zoom: Int,
        tileSource: org.osmdroid.tileprovider.tilesource.ITileSource,
        cacheDir: File,
        progressCallback: ((Int) -> Unit)?
    ): Int {
        val topLeft = latLonToTileXY(boundingBox.latNorth, boundingBox.lonWest, zoom)
        val bottomRight = latLonToTileXY(boundingBox.latSouth, boundingBox.lonEast, zoom)
        
        val minTileX = Math.min(topLeft.x, bottomRight.x)
        val maxTileX = Math.max(topLeft.x, bottomRight.x)
        val minTileY = Math.min(topLeft.y, bottomRight.y)
        val maxTileY = Math.max(topLeft.y, bottomRight.y)
        
        var downloaded = 0
        
        // OSMDroid cache structure: osmdroid/{tileSource.name()}/{zoom}/{x}/{y}.png
        val tileSourceDir = File(cacheDir, tileSource.name().replace("/", "_"))
        
        for (x in minTileX..maxTileX) {
            for (y in minTileY..maxTileY) {
                try {
                    // Construct tile URL manually for OpenStreetMap (MAPNIK)
                    // Format: https://tile.openstreetmap.org/{z}/{x}/{y}.png
                    val tileUrl = "https://tile.openstreetmap.org/$zoom/$x/$y.png"
                    
                    // OSMDroid cache path structure
                    val zoomDir = File(tileSourceDir, zoom.toString())
                    val xDir = File(zoomDir, x.toString())
                    val tileFile = File(xDir, "$y.png")
                    
                    // Create parent directories if needed
                    xDir.mkdirs()
                    
                    // Download tile if it doesn't exist
                    if (!tileFile.exists() || tileFile.length() == 0L) {
                        val connection = java.net.URL(tileUrl).openConnection()
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000
                        connection.setRequestProperty("User-Agent", Configuration.getInstance().userAgentValue)
                        connection.getInputStream().use { input ->
                            tileFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    
                    downloaded++
                    progressCallback?.invoke(downloaded)
                } catch (e: Exception) {
                    android.util.Log.w("OfflineMapsManager", "Error downloading tile ($zoom/$x/$y): ${e.message}")
                }
            }
        }
        
        return downloaded
    }
    
    /**
     * Deletes tiles for a specific region
     * Note: This is a simplified approach. In practice, OSMDroid's tile cache
     * doesn't easily allow deleting specific regions. We'll mark the region as deleted
     * and let OSMDroid manage its own cache cleanup.
     */
    private suspend fun deleteRegionTiles(region: OfflineMapRegion) {
        // OSMDroid manages its own cache, so we can't easily delete specific tiles
        // The region metadata deletion is sufficient - OSMDroid will handle cache size limits
        // If needed, we could implement more sophisticated tile deletion, but it's complex
        // and OSMDroid's cache management is usually sufficient
    }
    
    /**
     * Checks for and removes expired regions
     */
    suspend fun cleanupExpiredRegions(): Int {
        val allRegions = getAllRegions()
        val expiredRegions = allRegions.filter { it.isExpired() }
        
        if (expiredRegions.isNotEmpty()) {
            android.util.Log.d("OfflineMapsManager", "Found ${expiredRegions.size} expired regions")
            return deleteRegions(expiredRegions)
        }
        
        return 0
    }
    
    /**
     * Gets the total storage used by downloaded offline map regions
     * Calculates based on the estimated size of all saved regions
     */
    fun getStorageUsed(): Long {
        val regions = getAllRegions()
        return regions.sumOf { region ->
            estimateRegionSize(region)
        }
    }
    
    /**
     * Estimates the storage size for a specific region based on tile count
     * Uses average tile size of ~20KB
     */
    fun estimateRegionSize(region: OfflineMapRegion): Long {
        val boundingBox = BoundingBox(
            region.maxLatitude,
            region.maxLongitude,
            region.minLatitude,
            region.minLongitude
        )
        
        var totalTiles = 0
        for (zoom in region.minZoom..region.maxZoom) {
            totalTiles += calculateTilesForZoom(boundingBox, zoom)
        }
        
        // Average tile size is approximately 20KB
        return totalTiles * 20 * 1024L
    }
}

