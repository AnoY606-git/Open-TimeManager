package dev.opentimemanager.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.opentimemanager.app.ui.millisUntilNextLocalDay
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
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = Instant.now()
                date = now.atZone(zoneId).toLocalDate()
                delay(millisUntilNextLocalDay(now, zoneId))
            }
        }
    }
    return date
}
