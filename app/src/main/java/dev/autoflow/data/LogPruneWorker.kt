package dev.autoflow.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.autoflow.AutoFlowApp

/** Daily cleanup: drops logs older than two weeks and keeps each flow's log to a fixed size. */
class LogPruneWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val db = (applicationContext as AutoFlowApp).db
        db.logs().deleteOlderThan(System.currentTimeMillis() - 14L * 24 * 3_600_000)
        for (id in db.logs().flowIds()) db.logs().prune(id, RoomLogSink.KEEP)
        return Result.success()
    }
}
