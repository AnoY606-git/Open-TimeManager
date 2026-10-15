package dev.opentimemanager.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.opentimemanager.app.backup.RestoreDao

@Database(entities = [ProjectEntity::class, EntryEntity::class, SegmentEntity::class, TrackingSlotEntity::class, AppliedCommandEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackingDao(): TrackingDao
    abstract fun projectDao(): ProjectDao
    abstract fun reportDao(): ReportDao
    abstract fun restoreDao(): RestoreDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN zoneId TEXT NOT NULL DEFAULT 'UTC'")
            }
        }
    }
}
