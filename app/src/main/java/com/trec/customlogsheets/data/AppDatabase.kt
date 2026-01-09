package com.trec.customlogsheets.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SamplingSite::class, FormCompletion::class], version = 5, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun samplingSiteDao(): SamplingSiteDao
    abstract fun formCompletionDao(): FormCompletionDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create form_completions table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS form_completions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        siteId INTEGER NOT NULL,
                        formId TEXT NOT NULL,
                        completedAt INTEGER NOT NULL,
                        FOREIGN KEY(siteId) REFERENCES sampling_sites(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                
                // Create unique index
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_form_completions_siteId_formId 
                    ON form_completions(siteId, formId)
                """.trimIndent())
            }
        }
        
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Migrate form_completions to use siteName instead of siteId
                // First, create new table with siteName
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS form_completions_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        siteName TEXT NOT NULL,
                        formId TEXT NOT NULL,
                        completedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                
                // Copy data from old table to new table, joining with sampling_sites to get site names
                db.execSQL("""
                    INSERT INTO form_completions_new (id, siteName, formId, completedAt)
                    SELECT fc.id, ss.name, fc.formId, fc.completedAt
                    FROM form_completions fc
                    INNER JOIN sampling_sites ss ON fc.siteId = ss.id
                """.trimIndent())
                
                // Drop old table
                db.execSQL("DROP TABLE IF EXISTS form_completions")
                
                // Rename new table
                db.execSQL("ALTER TABLE form_completions_new RENAME TO form_completions")
                
                // Create new unique index
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_form_completions_siteName_formId 
                    ON form_completions(siteName, formId)
                """.trimIndent())
            }
        }
        
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add uploadStatus column to sampling_sites table
                // Default to NOT_UPLOADED (0) for existing sites
                db.execSQL("""
                    ALTER TABLE sampling_sites 
                    ADD COLUMN uploadStatus INTEGER NOT NULL DEFAULT 0
                """.trimIndent())
            }
        }
        
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Convert status column from TEXT to INTEGER
                // Create new table with correct schema
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sampling_sites_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        status INTEGER NOT NULL,
                        uploadStatus INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                
                // Copy data from old table to new table, converting status from TEXT to INTEGER
                // ONGOING = 0, FINISHED = 1
                db.execSQL("""
                    INSERT INTO sampling_sites_new (id, name, status, uploadStatus, createdAt)
                    SELECT 
                        id,
                        name,
                        CASE 
                            WHEN status = 'ONGOING' THEN 0
                            WHEN status = 'FINISHED' THEN 1
                            ELSE 0
                        END as status,
                        COALESCE(uploadStatus, 0) as uploadStatus,
                        createdAt
                    FROM sampling_sites
                """.trimIndent())
                
                // Drop old table
                db.execSQL("DROP TABLE IF EXISTS sampling_sites")
                
                // Rename new table
                db.execSQL("ALTER TABLE sampling_sites_new RENAME TO sampling_sites")
            }
        }
        
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

