package com.trec.customlogsheets.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SamplingSite::class, FormCompletion::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun samplingSiteDao(): SamplingSiteDao
    abstract fun formCompletionDao(): FormCompletionDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create form_completions table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS form_completions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        siteId INTEGER NOT NULL,
                        formId TEXT NOT NULL,
                        completedAt INTEGER NOT NULL,
                        FOREIGN KEY(siteId) REFERENCES sampling_sites(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                
                // Create unique index
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_form_completions_siteId_formId 
                    ON form_completions(siteId, formId)
                """.trimIndent())
            }
        }
        
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Migrate form_completions to use siteName instead of siteId
                // First, create new table with siteName
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS form_completions_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        siteName TEXT NOT NULL,
                        formId TEXT NOT NULL,
                        completedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                
                // Copy data from old table to new table, joining with sampling_sites to get site names
                database.execSQL("""
                    INSERT INTO form_completions_new (id, siteName, formId, completedAt)
                    SELECT fc.id, ss.name, fc.formId, fc.completedAt
                    FROM form_completions fc
                    INNER JOIN sampling_sites ss ON fc.siteId = ss.id
                """.trimIndent())
                
                // Drop old table
                database.execSQL("DROP TABLE IF EXISTS form_completions")
                
                // Rename new table
                database.execSQL("ALTER TABLE form_completions_new RENAME TO form_completions")
                
                // Create new unique index
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_form_completions_siteName_formId 
                    ON form_completions(siteName, formId)
                """.trimIndent())
            }
        }
        
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

