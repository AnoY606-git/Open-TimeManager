package dev.opentimemanager.app

import android.os.Bundle
import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.os.LocaleListCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.ui.AppViewModel
import dev.opentimemanager.app.ui.OpenTimeManagerApp
import dev.opentimemanager.app.ui.NotificationControlStatus
import dev.opentimemanager.app.ui.notificationControlStatus
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val application = application as OpenTimeManagerApplication
        setContent {
            val model: AppViewModel = viewModel(factory = AppViewModel.factory(application))
            val state by model.state.collectAsStateWithLifecycle()
            fun currentNotificationStatus(): NotificationControlStatus {
                val permissionGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                val enabled = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
                return notificationControlStatus(permissionGranted, enabled)
            }
            val notificationStatus = remember { mutableStateOf(currentNotificationStatus()) }
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                notificationStatus.value = currentNotificationStatus()
            }
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) notificationStatus.value = currentNotificationStatus()
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(state.preferences.language) {
                if (state.snapshot == null) return@LaunchedEffect
                val locales = when (state.preferences.language) {
                    AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
                    AppLanguage.ENGLISH -> LocaleListCompat.forLanguageTags("en")
                    AppLanguage.RUSSIAN -> LocaleListCompat.forLanguageTags("ru")
                }
                if (AppCompatDelegate.getApplicationLocales() != locales) {
                    AppCompatDelegate.setApplicationLocales(locales)
                }
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
                        if (notificationStatus.value == NotificationControlStatus.PERMISSION_REQUIRED && Build.VERSION.SDK_INT >= 33) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                        }
                    },
                )
            }
        }
    }
}
