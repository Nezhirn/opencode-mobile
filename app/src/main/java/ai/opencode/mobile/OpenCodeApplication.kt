package ai.opencode.mobile

import android.app.Application
import ai.opencode.mobile.data.AppRepository
import ai.opencode.mobile.data.local.SettingsStore

class OpenCodeApplication : Application() {
    lateinit var repository: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(SettingsStore(this))
    }
}
