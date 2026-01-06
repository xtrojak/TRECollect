package com.trec.customlogsheets.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "form_completions",
    foreignKeys = [
        ForeignKey(
            entity = SamplingSite::class,
            parentColumns = ["id"],
            childColumns = ["siteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["siteId", "formId"], unique = true)]
)
data class FormCompletion(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val siteId: Long,
    val formId: String,
    val completedAt: Long = System.currentTimeMillis()
)

