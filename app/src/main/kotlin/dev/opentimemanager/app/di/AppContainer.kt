package dev.opentimemanager.app.di

import android.content.Context
import androidx.room.Room
import dev.opentimemanager.app.data.*
import dev.opentimemanager.app.tracking.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Владелец process-scoped БД; зависимости передаются потребителям явно. */
class AppContainer(context: Context, dispatcher: CoroutineDispatcher = Dispatchers.IO, clock: ClockSnapshotSource = AndroidClockSnapshotSource(context)) {
    val database: AppDatabase = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "open-time-manager.db").build()
    val trackingCoordinator = TrackingCoordinator(RoomTrackingRepository(database, clock, dispatcher))
    val projects = ProjectRepository(database, dispatcher)
    val completedEntries = CompletedEntryRepository(database, dispatcher)
}
