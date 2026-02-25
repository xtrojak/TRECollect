package com.trec.trecollect.data

import org.json.JSONObject
import java.util.UUID

/**
 * Represents a downloaded offline map region
 */
data class OfflineMapRegion(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
    val minZoom: Int,
    val maxZoom: Int,
    val downloadedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null // null means never expires
) {
    /**
     * Converts to JSON for storage
     */
    fun toJson(): String {
        val json = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("minLatitude", minLatitude)
            put("maxLatitude", maxLatitude)
            put("minLongitude", minLongitude)
            put("maxLongitude", maxLongitude)
            put("minZoom", minZoom)
            put("maxZoom", maxZoom)
            put("downloadedAt", downloadedAt)
            if (expiresAt != null) {
                put("expiresAt", expiresAt)
            }
        }
        return json.toString()
    }
    
    companion object {
        /**
         * Creates from JSON
         */
        fun fromJson(jsonString: String): OfflineMapRegion? {
            return try {
                val json = JSONObject(jsonString)
                OfflineMapRegion(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    minLatitude = json.getDouble("minLatitude"),
                    maxLatitude = json.getDouble("maxLatitude"),
                    minLongitude = json.getDouble("minLongitude"),
                    maxLongitude = json.getDouble("maxLongitude"),
                    minZoom = json.getInt("minZoom"),
                    maxZoom = json.getInt("maxZoom"),
                    downloadedAt = json.getLong("downloadedAt"),
                    expiresAt = if (json.has("expiresAt") && !json.isNull("expiresAt")) {
                        json.getLong("expiresAt")
                    } else {
                        null
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("OfflineMapRegion", "Error parsing JSON: ${e.message}", e)
                null
            }
        }
    }
    
    /**
     * Checks if this region has expired
     */
    fun isExpired(): Boolean {
        return expiresAt != null && System.currentTimeMillis() > expiresAt
    }
    
}

