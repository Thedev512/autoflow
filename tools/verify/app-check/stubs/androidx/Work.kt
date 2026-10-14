// Mirrors androidx.work 2.10.x.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.work

import android.content.Context
import java.util.concurrent.TimeUnit

class WorkerParameters private constructor()

abstract class ListenableWorker(appContext: Context, params: WorkerParameters) {
    val applicationContext: Context get() = TODO() // Java getter in the real class

    abstract class Result internal constructor() {
        companion object {
            @JvmStatic fun success(): Result = TODO()
            @JvmStatic fun retry(): Result = TODO()
            @JvmStatic fun failure(): Result = TODO()
        }
    }
}

abstract class CoroutineWorker(appContext: Context, params: WorkerParameters) : ListenableWorker(appContext, params) {
    abstract suspend fun doWork(): Result
}

enum class ExistingPeriodicWorkPolicy { REPLACE, KEEP, UPDATE, CANCEL_AND_REENQUEUE }

class PeriodicWorkRequest private constructor() {
    class Builder internal constructor() {
        fun build(): PeriodicWorkRequest = TODO()
    }
}

inline fun <reified W : ListenableWorker> PeriodicWorkRequestBuilder(
    repeatInterval: Long,
    repeatIntervalTimeUnit: TimeUnit,
): PeriodicWorkRequest.Builder = TODO()

interface Operation

abstract class WorkManager {
    abstract fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        periodicWork: PeriodicWorkRequest,
    ): Operation

    companion object {
        @JvmStatic fun getInstance(context: Context): WorkManager = TODO()
    }
}
