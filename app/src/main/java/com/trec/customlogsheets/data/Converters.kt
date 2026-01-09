package com.trec.customlogsheets.data

import androidx.room.TypeConverter
import com.trec.customlogsheets.util.AppLogger

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
                AppLogger.w("Converters", "Invalid SiteStatus ordinal: $ordinal, defaulting to ONGOING")
            }
            result
        } catch (e: Exception) {
            AppLogger.e("Converters", "Error converting SiteStatus from ordinal $ordinal", e)
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
                AppLogger.w("Converters", "Invalid UploadStatus ordinal: $ordinal, defaulting to NOT_UPLOADED")
            }
            result
        } catch (e: Exception) {
            AppLogger.e("Converters", "Error converting UploadStatus from ordinal $ordinal", e)
            UploadStatus.NOT_UPLOADED
        }
    }
}

