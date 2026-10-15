package dev.opentimemanager.app

import android.app.Application
import dev.opentimemanager.app.di.AppContainer

class OpenTimeManagerApplication : Application() {
    lateinit var container: AppContainer
        private set
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
