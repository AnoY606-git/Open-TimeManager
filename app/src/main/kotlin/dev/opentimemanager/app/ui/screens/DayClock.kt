package dev.opentimemanager.app.ui.screens

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.opentimemanager.app.ui.millisUntilNextLocalDay
import dev.opentimemanager.app.system.TrackingNotification
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay

/** A lifecycle-bounded local-day clock aligned to the zone's actual next midnight. */
@Composable
internal fun rememberCurrentDate(zoneId: ZoneId): LocalDate {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var date by remember(zoneId) { mutableStateOf(LocalDate.now(zoneId)) }
    LaunchedEffect(lifecycle, zoneId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val now = Instant.now()
                date = now.atZone(zoneId).toLocalDate()
                delay(millisUntilNextLocalDay(now, zoneId))
            }
        }
    }
    return date
}

@Composable
internal fun rememberTimerElapsedRealtime(session: TrackingSession, enabled: Boolean): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var nowElapsed by remember(session) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(lifecycle, session, enabled) {
        if (enabled) observeResumedTimer(lifecycle, session) { nowElapsed = it }
    }
    return nowElapsed
}

internal fun timerTickDelayMillis(elapsedMillis: Long): Long = 1_000L - elapsedMillis.coerceAtLeast(0L) % 1_000L

/** No polling when paused, behind another activity, or removed from composition. */
internal suspend fun observeResumedTimer(
    lifecycle: Lifecycle,
    session: TrackingSession,
    now: () -> Long = SystemClock::elapsedRealtime,
    wait: suspend (Long) -> Unit = { delay(it) },
    onTick: (Long) -> Unit,
) {
    if (session.state != TrackingState.RUNNING) return
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while (true) {
            // Re-read the monotonic clock immediately on resume; never accumulate tick counts.
            val elapsedRealtime = now()
            onTick(elapsedRealtime)
            wait(timerTickDelayMillis(TrackingNotification.elapsedMillis(session, elapsedRealtime)))
        }
    }
}
