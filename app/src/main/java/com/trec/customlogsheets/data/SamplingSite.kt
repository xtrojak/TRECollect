package com.trec.customlogsheets.data

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Entity(tableName = "sampling_sites")
@Parcelize
data class SamplingSite(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val status: SiteStatus,
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable

enum class SiteStatus {
    ONGOING,
    FINISHED
}

