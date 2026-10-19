package dev.opentimemanager.app

import android.os.Bundle
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.ui.AppViewModel
import dev.opentimemanager.app.ui.OpenTimeManagerApp
import dev.opentimemanager.app.ui.DeviceSetupState
import dev.opentimemanager.app.ui.NotificationControlStatus
import dev.opentimemanager.app.ui.NotificationControlAction
import dev.opentimemanager.app.ui.notificationControlAction
import dev.opentimemanager.app.ui.notificationSettingsIntent
import dev.opentimemanager.app.ui.notificationControlStatus
import dev.opentimemanager.app.ui.applicationLanguage
import dev.opentimemanager.app.ui.applicationLocales
import dev.opentimemanager.app.system.SystemSurfaceUpdater
import dev.opentimemanager.app.system.TrackingNotification
import dev.opentimemanager.app.system.containSystemSurfaceFailure
import dev.opentimemanager.app.system.awaitStoredApplicationLocales
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val application = application as OpenTimeManagerApplication
        setContent {
            val model: AppViewModel = viewModel(factory = AppViewModel.factory(application))
            val state by model.state.collectAsStateWithLifecycle()
            val selectedLanguage = applicationLanguage(AppCompatDelegate.getApplicationLocales())
            fun currentNotificationStatus(): NotificationControlStatus {
                val manager = getSystemService(NotificationManager::class.java)
                val permissionGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    POST_NOTIFICATIONS_PERMISSION,
                ) == PackageManager.PERMISSION_GRANTED
                val preferences = getSharedPreferences(NOTIFICATION_PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
                return notificationControlStatus(
                    sdkInt = Build.VERSION.SDK_INT,
                    permissionGranted = permissionGranted,
                    permissionDeniedOnce = preferences.getBoolean(NOTIFICATION_PERMISSION_DENIED_ONCE, false),
                    shouldShowPermissionRationale = Build.VERSION.SDK_INT >= 33 && ActivityCompat.shouldShowRequestPermissionRationale(
                        this@MainActivity,
                        POST_NOTIFICATIONS_PERMISSION,
                    ),
                    notificationsEnabled = manager.areNotificationsEnabled(),
                    channelImportance = manager.getNotificationChannel(TrackingNotification.CHANNEL_ID)?.importance,
                )
            }
            val notificationStatus = remember { mutableStateOf(currentNotificationStatus()) }
            val rationaleBeforePermissionRequest = remember { mutableStateOf(false) }
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                val shouldShowRationale = Build.VERSION.SDK_INT >= 33 && ActivityCompat.shouldShowRequestPermissionRationale(
                    this@MainActivity,
                    POST_NOTIFICATIONS_PERMISSION,
                )
                if (dev.opentimemanager.app.ui.shouldRecordNotificationDenial(
                        granted,
                        rationaleBeforePermissionRequest.value,
                        shouldShowRationale,
                    )
                ) {
                    getSharedPreferences(NOTIFICATION_PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
                        .edit { putBoolean(NOTIFICATION_PERMISSION_DENIED_ONCE, true) }
                }
                notificationStatus.value = currentNotificationStatus()
            }
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        notificationStatus.value = currentNotificationStatus()
                        model.reconcileSystemControls()
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            val useDarkSystemBars = when (state.preferences.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !useDarkSystemBars
                    isAppearanceLightNavigationBars = !useDarkSystemBars
                }
            }
            OpenTimeManagerTheme(state.preferences.theme, state.preferences.dynamicColor) {
                OpenTimeManagerApp(
                    state,
                    model,
                    notificationStatus.value,
                    onManageNotifications = {
                        when (val action = notificationControlAction(notificationStatus.value)) {
                            NotificationControlAction.REQUEST_PERMISSION -> {
                                rationaleBeforePermissionRequest.value = Build.VERSION.SDK_INT >= 33 &&
                                    ActivityCompat.shouldShowRequestPermissionRationale(
                                        this@MainActivity,
                                        POST_NOTIFICATIONS_PERMISSION,
                                    )
                                permissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
                            }
                            NotificationControlAction.OPEN_APP_SETTINGS,
                            NotificationControlAction.OPEN_CHANNEL_SETTINGS ->
                                openNotificationSettings(action)
                            NotificationControlAction.NONE -> openNotificationSettings(NotificationControlAction.OPEN_CHANNEL_SETTINGS)
                        }
                    },
                    selectedLanguage = selectedLanguage,
                    onLanguage = { language ->
                        val locales = applicationLocales(language)
                        if (AppCompatDelegate.getApplicationLocales() != locales) {
                            AppCompatDelegate.setApplicationLocales(locales)
                            application.applicationScope.launch {
                                containSystemSurfaceFailure {
                                    awaitStoredApplicationLocales(application, locales)
                                    val latestSnapshot = application.container.trackingCoordinator
                                        .observeSystemSnapshot()
                                        .first()
                                    SystemSurfaceUpdater(application).refresh(latestSnapshot)
                                }
                            }
                        }
                    },
                    showDeviceSetupInitially = application.deviceSetupPreferences.state() == DeviceSetupState.PENDING,
                    onFinishDeviceSetup = application.deviceSetupPreferences::finish,
                )
            }
        }
    }

    private fun openNotificationSettings(action: NotificationControlAction) {
        val intent = notificationSettingsIntent(action, packageName) ?: return
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            dev.opentimemanager.app.ui.openAppSystemSettings(this)
        } catch (_: SecurityException) {
            dev.opentimemanager.app.ui.openAppSystemSettings(this)
        }
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_PREFERENCES = "notification_permission"
        const val NOTIFICATION_PERMISSION_DENIED_ONCE = "denied_once"
        const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    }
}
