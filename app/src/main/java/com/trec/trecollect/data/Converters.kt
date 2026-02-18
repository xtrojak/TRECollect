package com.trec.trecollect.data

import androidx.room.TypeConverter
import com.trec.trecollect.util.AppLogger

class Converters {
    @TypeConverter
    fun fromSiteStatus(status: SiteStatus): Int {
        return status.ordinal
    }
    
    @TypeConverter
    fun toSiteStatus(ordinal: Int): SiteStatus {
        return try {
            val result = SiteStatus.values().getOrElse(ordinal) { SiteStatus.ONGOING }
            if (ordinal < 0 || ordinal >= SiteStatus.values().size) {
                try {
                    AppLogger.w("Converters", "Invalid SiteStatus ordinal: $ordinal, defaulting to ONGOING")
                } catch (e: Exception) {
                    // Ignore logging errors in test environments
                }
            }
            result
        } catch (e: Exception) {
            try {
                AppLogger.e("Converters", "Error converting SiteStatus from ordinal $ordinal", e)
            } catch (logError: Exception) {
                // Ignore logging errors in test environments
            }
            SiteStatus.ONGOING
        }
    }
    
    @TypeConverter
    fun fromUploadStatus(status: UploadStatus): Int {
        return status.ordinal
    }
    
    @TypeConverter
    fun toUploadStatus(ordinal: Int): UploadStatus {
        return try {
            val result = UploadStatus.values().getOrElse(ordinal) { UploadStatus.NOT_UPLOADED }
            if (ordinal < 0 || ordinal >= UploadStatus.values().size) {
                try {
                    AppLogger.w("Converters", "Invalid UploadStatus ordinal: $ordinal, defaulting to NOT_UPLOADED")
                } catch (e: Exception) {
                    // Ignore logging errors in test environments
                }
            }
            result
        } catch (e: Exception) {
            try {
                AppLogger.e("Converters", "Error converting UploadStatus from ordinal $ordinal", e)
            } catch (logError: Exception) {
                // Ignore logging errors in test environments
            }
            UploadStatus.NOT_UPLOADED
        }
    }
}

