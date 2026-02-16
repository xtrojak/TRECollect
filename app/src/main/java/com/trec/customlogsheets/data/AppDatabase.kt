package com.trec.customlogsheets.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room database built at current schema only. No migrations: new installs get
 * the schema in one go; any existing DB from an older version is recreated via
 * [Room.fallbackToDestructiveMigration].
 */
@Database(entities = [SamplingSite::class, FormCompletion::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun samplingSiteDao(): SamplingSiteDao
    abstract fun formCompletionDao(): FormCompletionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

