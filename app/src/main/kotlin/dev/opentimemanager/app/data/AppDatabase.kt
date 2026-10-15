package dev.opentimemanager.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.opentimemanager.app.backup.RestoreDao

@Database(entities = [ProjectEntity::class, EntryEntity::class, SegmentEntity::class, TrackingSlotEntity::class, AppliedCommandEntity::class, PendingPreferencesRestoreEntity::class], version = 4, exportSchema = true)
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
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracking_slot ADD COLUMN generation TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_projects_archivedAtUtcMillis ON projects(archivedAtUtcMillis)")
                db.execSQL("UPDATE projects SET name = '@default' WHERE id = '00000000-0000-4000-8000-000000000001'")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS pending_preferences_restore (
                        id INTEGER NOT NULL,
                        operationId TEXT NOT NULL,
                        theme TEXT NOT NULL,
                        dynamicColor INTEGER NOT NULL,
                        privacyMode INTEGER NOT NULL,
                        dailyGoalMinutes INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )""".trimIndent(),
                )
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN confirmedDurationMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE entries ADD COLUMN lastConfirmedEndUtcMillis INTEGER")
                db.execSQL(
                    """UPDATE entries SET
                        confirmedDurationMillis = COALESCE((
                            SELECT SUM(durationMillis) FROM segments
                            WHERE segments.entryId = entries.id AND durationMillis IS NOT NULL
                        ), 0),
                        lastConfirmedEndUtcMillis = (
                            SELECT MAX(endUtcMillis) FROM segments
                            WHERE segments.entryId = entries.id AND durationMillis IS NOT NULL
                        )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_projects_archivedAtUtcMillis_createdAtUtcMillis_id " +
                        "ON projects(archivedAtUtcMillis, createdAtUtcMillis, id)",
                )
            }
        }
    }
}
