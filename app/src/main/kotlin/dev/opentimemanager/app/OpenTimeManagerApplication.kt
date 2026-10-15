package dev.opentimemanager.app

import android.app.Application
import android.content.res.Configuration
import dev.opentimemanager.app.di.AppContainer
import dev.opentimemanager.app.system.SystemSurfaceUpdater
import dev.opentimemanager.app.system.containSystemSurfaceFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class OpenTimeManagerApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var container: AppContainer
        private set
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        applicationScope.launch {
            containSystemSurfaceFailure {
                val surfaces = SystemSurfaceUpdater(this@OpenTimeManagerApplication)
                container.trackingCoordinator.observeSnapshot().collect { snapshot ->
                    containSystemSurfaceFailure { surfaces.refresh(snapshot) }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applicationScope.launch {
            containSystemSurfaceFailure {
                val snapshot = container.trackingCoordinator.observeSnapshot().first()
                SystemSurfaceUpdater(this@OpenTimeManagerApplication).refresh(snapshot)
            }
        }
    }
}
