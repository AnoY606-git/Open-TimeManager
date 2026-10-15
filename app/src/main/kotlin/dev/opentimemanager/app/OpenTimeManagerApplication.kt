package dev.opentimemanager.app

import android.app.Application
import dev.opentimemanager.app.di.AppContainer
import dev.opentimemanager.app.system.SystemSurfaceUpdater
import kotlinx.coroutines.*

class OpenTimeManagerApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var container: AppContainer
        private set
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        applicationScope.launch {
            val surfaces = SystemSurfaceUpdater(this@OpenTimeManagerApplication)
            container.trackingCoordinator.observeSnapshot().collect { surfaces.refresh(it) }
        }
    }
}
