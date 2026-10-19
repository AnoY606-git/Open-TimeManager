package dev.opentimemanager.app.di

import android.content.Context
import androidx.room.Room
import androidx.core.content.ContextCompat
import dev.opentimemanager.app.R
import dev.opentimemanager.app.data.*
import dev.opentimemanager.app.backup.BackupFileCoordinator
import dev.opentimemanager.app.backup.RestoreRepository
import dev.opentimemanager.app.backup.RoomSettingsRecoveryJournal
import dev.opentimemanager.app.dataexport.CsvExportCoordinator
import dev.opentimemanager.app.dataexport.ResolverDocumentStore
import dev.opentimemanager.app.tracking.*
import dev.opentimemanager.app.settings.UserSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map

/** Владелец process-scoped БД; зависимости передаются потребителям явно. */
class AppContainer(context: Context, dispatcher: CoroutineDispatcher = Dispatchers.IO, clock: ClockSnapshotSource = AndroidClockSnapshotSource(context)) {
    val database: AppDatabase = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "open-time-manager.db")
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
        .build()
    val projects = ProjectRepository(database, dispatcher)
    val completedEntries = CompletedEntryRepository(database, dispatcher)
    val activeNotes = ActiveEntryNoteRepository(database, dispatcher)
    private val settingsJournal = RoomSettingsRecoveryJournal(database, dispatcher)
    val settings = UserSettings(context, recoveryJournal = settingsJournal)
    val trackingCoordinator = TrackingCoordinator(
        RoomTrackingRepository(database, clock, dispatcher),
        defaultProjectId = settings.defaultProjectId,
        dailyGoalMinutes = settings.values.map { it.dailyGoalMinutes },
    )
    val restore = RestoreRepository(database, dispatcher, settings = settings, settingsJournal = settingsJournal)
    val csvExport = CsvExportCoordinator(
        database,
        ResolverDocumentStore(context),
        dispatcher,
        cacheDirectory = context.applicationContext.cacheDir,
        projectDisplayNameForSnapshot = {
            // Resolve once per export, not once per process (or once per archived row).
            val defaultName = ContextCompat.getString(context.applicationContext, R.string.default_project_name)
            val displayName: (String, String) -> String = { id, storedName ->
                if (id == ProjectRepository.DEFAULT_PROJECT_ID.value) defaultName else storedName
            }
            displayName
        },
    )
    val backupFiles = BackupFileCoordinator(
        database,
        ResolverDocumentStore(context),
        restore,
        dispatcher,
        settings = settings,
    )
}
