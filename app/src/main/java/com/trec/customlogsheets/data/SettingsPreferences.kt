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
        
        const val DEFAULT_TEAM = "LSI"
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
}

