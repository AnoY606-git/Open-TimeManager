package dev.opentimemanager.app.di

import android.content.Context
import androidx.room.Room
import dev.opentimemanager.app.data.*
import dev.opentimemanager.app.backup.BackupFileCoordinator
import dev.opentimemanager.app.backup.RestoreRepository
import dev.opentimemanager.app.dataexport.CsvExportCoordinator
import dev.opentimemanager.app.dataexport.ResolverDocumentStore
import dev.opentimemanager.app.tracking.*
import dev.opentimemanager.app.settings.UserSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.ZoneId

/** Владелец process-scoped БД; зависимости передаются потребителям явно. */
class AppContainer(context: Context, dispatcher: CoroutineDispatcher = Dispatchers.IO, clock: ClockSnapshotSource = AndroidClockSnapshotSource(context)) {
    val database: AppDatabase = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "open-time-manager.db")
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
        .build()
    val trackingCoordinator = TrackingCoordinator(RoomTrackingRepository(database, clock, dispatcher))
    val projects = ProjectRepository(database, dispatcher)
    val completedEntries = CompletedEntryRepository(database, dispatcher)
    val activeNotes = ActiveEntryNoteRepository(database, dispatcher)
    val settings = UserSettings(context)
    val restore = RestoreRepository(database, dispatcher)
    val csvExport = CsvExportCoordinator(
        database,
        ResolverDocumentStore(context),
        dispatcher,
        ZoneId.systemDefault(),
    )
    val backupFiles = BackupFileCoordinator(
        database,
        ResolverDocumentStore(context),
        restore,
        dispatcher,
    )
}
