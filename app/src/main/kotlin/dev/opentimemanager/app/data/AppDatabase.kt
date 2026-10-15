package dev.opentimemanager.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ProjectEntity::class, EntryEntity::class, SegmentEntity::class, TrackingSlotEntity::class, AppliedCommandEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackingDao(): TrackingDao
    abstract fun projectDao(): ProjectDao
    abstract fun reportDao(): ReportDao
}
