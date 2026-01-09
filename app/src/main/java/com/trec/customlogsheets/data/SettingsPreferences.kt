package com.trec.customlogsheets.data

import android.content.Context
import android.content.SharedPreferences

class SettingsPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_SUBMISSION_PATH = "submission_path"
        private const val KEY_SAMPLING_TEAM = "sampling_team"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_MAP_EXPIRY_DAYS = "map_expiry_days"
        private const val KEY_APP_UUID = "app_uuid"
        private const val KEY_OWNCLOUD_FOLDER_VERIFIED = "owncloud_folder_verified"
        
        const val DEFAULT_TEAM = "LSI"
        const val DEFAULT_MAP_EXPIRY_DAYS = 30L // Default: 30 days
    }
    
    fun getSubmissionPath(): String {
        return prefs.getString(KEY_SUBMISSION_PATH, "") ?: ""
    }
    
    fun setSubmissionPath(path: String) {
        prefs.edit().putString(KEY_SUBMISSION_PATH, path).apply()
    }
    
    fun getFolderUri(): String {
        return prefs.getString(KEY_FOLDER_URI, "") ?: ""
    }
    
    fun setFolderUri(uri: String) {
        prefs.edit().putString(KEY_FOLDER_URI, uri).apply()
    }
    
    fun getSamplingTeam(): String {
        return prefs.getString(KEY_SAMPLING_TEAM, DEFAULT_TEAM) ?: DEFAULT_TEAM
    }
    
    fun setSamplingTeam(team: String) {
        prefs.edit().putString(KEY_SAMPLING_TEAM, team).apply()
    }
    
    /**
     * Gets the map expiry time in days (0 means never expire)
     */
    fun getMapExpiryDays(): Long {
        return prefs.getLong(KEY_MAP_EXPIRY_DAYS, DEFAULT_MAP_EXPIRY_DAYS)
    }
    
    /**
     * Sets the map expiry time in days (0 means never expire)
     */
    fun setMapExpiryDays(days: Long) {
        prefs.edit().putLong(KEY_MAP_EXPIRY_DAYS, days).apply()
    }
    
    /**
     * Gets the app UUID (generates if not exists)
     */
    fun getAppUuid(): String {
        var uuid = prefs.getString(KEY_APP_UUID, null)
        if (uuid == null) {
            uuid = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_APP_UUID, uuid).apply()
        }
        return uuid
    }
    
    /**
     * Checks if ownCloud folder has been verified/created
     */
    fun isOwnCloudFolderVerified(): Boolean {
        return prefs.getBoolean(KEY_OWNCLOUD_FOLDER_VERIFIED, false)
    }
    
    /**
     * Marks ownCloud folder as verified/created
     */
    fun setOwnCloudFolderVerified(verified: Boolean) {
        prefs.edit().putBoolean(KEY_OWNCLOUD_FOLDER_VERIFIED, verified).apply()
    }
}

