package dev.opentimemanager.app.ui

import android.app.ActivityManager
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dev.opentimemanager.app.R
import dev.opentimemanager.app.system.TrackingTileService
import dev.opentimemanager.app.widget.TrackingWidgetProvider
import java.util.Locale

enum class DeviceFamily(val hint: Int) {
    XIAOMI(R.string.platform_oem_xiaomi),
    SAMSUNG(R.string.platform_oem_samsung),
    OPPO(R.string.platform_oem_oppo),
    OTHER(R.string.platform_oem_other),
}

/** Manufacturer hints are advisory, never proof of the installed ROM or a settings path. */
fun deviceFamily(manufacturer: String, brand: String): DeviceFamily {
    val names = setOf(manufacturer.trim().lowercase(Locale.ROOT), brand.trim().lowercase(Locale.ROOT))
    return when {
        names.any { it in setOf("xiaomi", "redmi", "poco") } -> DeviceFamily.XIAOMI
        "samsung" in names -> DeviceFamily.SAMSUNG
        names.any { it in setOf("oppo", "oneplus", "realme") } -> DeviceFamily.OPPO
        else -> DeviceFamily.OTHER
    }
}

data class DeviceBackgroundStatus(val powerSave: Boolean? = null, val backgroundRestricted: Boolean? = null)

fun readDeviceBackgroundStatus(context: Context): DeviceBackgroundStatus = DeviceBackgroundStatus(
    powerSave = runCatching { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode }.getOrNull(),
    backgroundRestricted = runCatching {
        context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
    }.getOrNull(),
)

/** Called only from an explicit user tap; decline/failure never schedules another prompt. */
fun requestTrackingTile(context: Context, result: (Int) -> Unit) {
    if (Build.VERSION.SDK_INT < 33) {
        result(R.string.platform_tile_manual)
        return
    }
    try {
        val manager = context.getSystemService(StatusBarManager::class.java)
        if (manager == null) {
            result(R.string.platform_tile_manual)
            return
        }
        manager.requestAddTileService(
            ComponentName(context, TrackingTileService::class.java),
            context.getString(R.string.app_name),
            Icon.createWithResource(context, R.drawable.ic_tracking),
            context.mainExecutor,
        ) { code ->
            result(when (code) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.platform_tile_added
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> R.string.platform_tile_declined
                else -> R.string.platform_tile_manual
            })
        }
    } catch (_: Exception) {
        result(R.string.platform_tile_manual)
    }
}

fun requestTrackingWidget(context: Context): Int = try {
    val manager = AppWidgetManager.getInstance(context)
    if (manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(
            ComponentName(context, TrackingWidgetProvider::class.java), null, null,
        )
    ) R.string.platform_widget_requested else R.string.platform_widget_manual
} catch (_: Exception) {
    R.string.platform_widget_manual
}

fun appSystemSettingsIntent(packageName: String): Intent = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null),
)

fun openAppSystemSettings(context: Context): Boolean = try {
    context.startActivity(appSystemSettingsIntent(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: Exception) {
    false
}
