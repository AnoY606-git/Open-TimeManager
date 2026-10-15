package dev.opentimemanager.app.tracking

import dev.opentimemanager.app.data.AppDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomTrackingRepository(private val database: AppDatabase, private val clock: ClockSnapshotSource, private val dispatcher: CoroutineDispatcher) {
    suspend fun execute(command: TrackingCommand): TrackingResult = withContext(dispatcher) {
        database.trackingDao().execute(command, clock)
    }
    /** Перед публикацией транзакционно сохраняет NEEDS_REVIEW при утрате достоверной boot-опоры. */
    fun observeSnapshot(): Flow<AppSnapshot> = database.invalidationTracker.createFlow("projects", "entries", "segments", "tracking_slot").map {
        withContext(dispatcher) { database.trackingDao().snapshot(clock) }
    }
}
