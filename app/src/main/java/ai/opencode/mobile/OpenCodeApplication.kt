package ai.opencode.mobile

import ai.opencode.mobile.data.AppRepository
import ai.opencode.mobile.data.local.SettingsStore
import android.app.Activity
import android.app.Application
import android.os.Bundle

class OpenCodeApplication : Application() {
    lateinit var repository: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(SettingsStore(this))
        registerActivityLifecycleCallbacks(ForegroundTracker { repository.setForeground(it) })
    }

    /**
     * Reports whether any activity is started. A recreation (rotation is
     * handled in place, but language or window-size changes are not) stops the
     * old instance before starting the new one; it is flagged as changing
     * configurations and must not read as "went to the background".
     */
    private class ForegroundTracker(private val onChange: (Boolean) -> Unit) : ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) onChange(true)
        }

        override fun onActivityStopped(activity: Activity) {
            if (--started == 0 && !activity.isChangingConfigurations) onChange(false)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
