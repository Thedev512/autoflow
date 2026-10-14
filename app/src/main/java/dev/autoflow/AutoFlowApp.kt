package dev.autoflow

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.autoflow.data.AppDatabase
import dev.autoflow.data.FlowRepository
import dev.autoflow.data.LogPruneWorker
import dev.autoflow.data.Prefs
import dev.autoflow.data.RoomLogSink
import dev.autoflow.runtime.AppForeground
import dev.autoflow.runtime.Notifier
import dev.autoflow.runtime.Runtime
import dev.autoflow.runtime.RuntimeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.TimeUnit

/** App-wide objects, created once per process. */
class AutoFlowApp : Application() {
    lateinit var db: AppDatabase
    lateinit var repo: FlowRepository
    lateinit var prefs: Prefs
    lateinit var controller: RuntimeController

    /** Flows run on a background pool so the UI thread stays smooth. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.create(this)
        repo = FlowRepository(db)
        prefs = Prefs(this)
        controller = RuntimeController(this, repo, RoomLogSink(db, appScope), prefs, appScope)
        Runtime.controller = controller
        Notifier.ensureChannels(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = AppForeground.onStart()
            override fun onActivityStopped(activity: Activity) = AppForeground.onStop()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // Housekeeping once a day: trim old logs. WorkManager picks a battery-friendly moment.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "log-prune",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LogPruneWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}
