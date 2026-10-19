package dev.opentimemanager.app.ui

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.opentimemanager.app.settings.UserSettings

enum class DeviceSetupState { PENDING, SKIPPED, COMPLETED, EXISTING_INSTALLATION }

/** Локальный выбор устройства: не входит в пользовательские настройки или копии OTM. */
class DeviceSetupPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("device_setup", Context.MODE_PRIVATE)

    fun state(): DeviceSetupState? = try {
        preferences.getString("state", null)?.let { value ->
            DeviceSetupState.entries.firstOrNull { it.name == value } ?: DeviceSetupState.EXISTING_INSTALLATION
        }
    } catch (_: ClassCastException) {
        DeviceSetupState.EXISTING_INSTALLATION
    }

    /** Вызывать до открытия БД и DataStore: их создание само по себе не доказывает прошлый запуск. */
    fun initialize(existingInstallation: Boolean): Boolean {
        val current = state()
        if (current != null) return current == DeviceSetupState.PENDING
        val initial = if (existingInstallation) DeviceSetupState.EXISTING_INSTALLATION else DeviceSetupState.PENDING
        return save(initial) && initial == DeviceSetupState.PENDING
    }

    fun finish(outcome: DeviceSetupState): Boolean {
        require(outcome == DeviceSetupState.SKIPPED || outcome == DeviceSetupState.COMPLETED)
        return save(outcome)
    }

    private fun save(value: DeviceSetupState): Boolean = try {
        preferences.edit().putString("state", value.name).commit()
    } catch (_: Exception) {
        false
    }
}

/** При неизвестной истории безопаснее оставить только добровольный вход из настроек. */
internal fun isExistingDeviceSetupInstallation(
    firstInstallTime: Long?,
    lastUpdateTime: Long?,
    hasLocalData: Boolean,
): Boolean = hasLocalData || firstInstallTime == null || lastUpdateTime == null ||
    firstInstallTime <= 0L || lastUpdateTime <= 0L || firstInstallTime != lastUpdateTime

internal fun existingDeviceSetupInstallation(context: Context): Boolean {
    val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    return isExistingDeviceSetupInstallation(
        packageInfo?.firstInstallTime,
        packageInfo?.lastUpdateTime,
        hasLocalData = context.getDatabasePath("open-time-manager.db").exists() ||
            context.preferencesDataStoreFile(UserSettings.FILE_NAME).exists(),
    )
}
