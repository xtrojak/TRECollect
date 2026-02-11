package com.trec.customlogsheets.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "form_completions",
    indices = [Index(value = ["siteName", "formId"], unique = true)]
)
data class FormCompletion(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val siteName: String,
    val formId: String,
    val completedAt: Long = System.currentTimeMillis()
)

