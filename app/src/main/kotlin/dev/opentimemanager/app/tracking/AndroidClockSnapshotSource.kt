package dev.opentimemanager.app.tracking

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dev.opentimemanager.core.model.ClockSnapshot

class AndroidClockSnapshotSource(context: Context) : ClockSnapshotSource {
    private val resolver = context.applicationContext.contentResolver
    override fun now(): ClockSnapshot {
        val bootCount = try {
            Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT).toLong().takeIf { it >= 0 }
        } catch (_: Settings.SettingNotFoundException) { null }
          catch (_: SecurityException) { null }
        return ClockSnapshot(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount)
    }
}
