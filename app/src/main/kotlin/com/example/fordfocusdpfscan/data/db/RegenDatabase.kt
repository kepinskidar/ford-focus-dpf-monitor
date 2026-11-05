package com.example.fordfocusdpfscan.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// ═══════════════════════════════════════════════════════════════════════════════
// RegenDatabase.kt — Room database definition.
//
// Single instance (singleton) shared across the app via getInstance().
// Stores regen_sessions, regen_data_points, and maintenance_reminders tables.
// exportSchema = false → no schema JSON files in the build output.
//
// Version history:
//   1 → 2: cleared false-positive regen records (pre-v4.3, destructive)
//   2 → 3: added maintenance_reminders table (v4.7, non-destructive migration)
// ═══════════════════════════════════════════════════════════════════════════════

@Database(
    entities  = [RegenSession::class, RegenDataPoint::class, MaintenanceReminder::class],
    version   = 4,
    exportSchema = false
)
abstract class RegenDatabase : RoomDatabase() {

    abstract fun regenDao(): RegenDao
    abstract fun maintenanceDao(): MaintenanceDao

    companion object {

        @Volatile
        private var INSTANCE: RegenDatabase? = null

        /**
         * Migration 2 → 3: adds the maintenance_reminders table.
         * All existing regen sessions and data points are preserved.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS maintenance_reminders (
                        id               INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title            TEXT    NOT NULL,
                        intervalKm       INTEGER NOT NULL,
                        lastDoneKm       INTEGER NOT NULL,
                        isAutoManaged    INTEGER NOT NULL DEFAULT 0,
                        notif1000Sent    INTEGER NOT NULL DEFAULT 0,
                        notif500Sent     INTEGER NOT NULL DEFAULT 0,
                        notifOverdueSent INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /** Migration 3 -> 4: adds the peak outlet-EGT column to regen_sessions. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE regen_sessions ADD COLUMN peakEgtOutC REAL NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): RegenDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    RegenDatabase::class.java,
                    "regen_history.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)        // preserves regen history
                    .fallbackToDestructiveMigration()    // safety net for unexpected jumps
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
