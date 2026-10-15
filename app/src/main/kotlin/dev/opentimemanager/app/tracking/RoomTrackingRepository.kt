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
    /** Малая проекция не зависит от размера архива. */
    fun observeSystemSnapshot(): Flow<SystemSnapshot> = database.invalidationTracker
        .createFlow("projects", "entries", "segments", "tracking_slot")
        .map { withContext(dispatcher) { database.trackingDao().systemSnapshot(clock) } }

    /** UI-история читает только явно ограниченное число последних entries. */
    fun observeSnapshot(historyLimit: Int): Flow<AppSnapshot> {
        require(historyLimit in 1..20_000)
        return database.invalidationTracker.createFlow("projects", "entries", "segments", "tracking_slot").map {
            withContext(dispatcher) { database.trackingDao().snapshot(clock, historyLimit) }
        }
    }
}
