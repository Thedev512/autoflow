package dev.autoflow.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifier {
    const val CH_RUNTIME = "runtime"
    const val CH_FLOWS = "flows"
    const val CH_FLOWS_SILENT = "flows_silent"
    const val CH_DIALOGS = "dialogs"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_RUNTIME, "Running flows", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a flow is active. Android requires it for background work."
                    setShowBadge(false)
                },
                NotificationChannel(CH_FLOWS, "Flow notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Notifications your flows show"
                },
                NotificationChannel(CH_FLOWS_SILENT, "Silent flow notifications", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_DIALOGS, "Flow questions", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When a flow asks you something"
                },
            )
        )
    }

    fun openApp(context: Context, requestCode: Int = 0): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setPackage(context.packageName)
        return PendingIntent.getActivity(
            context, requestCode, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Resource id of the small notification icon, looked up by name so runtime code needs no R class. */
    fun smallIcon(context: Context): Int =
        context.resources.getIdentifier("ic_stat_flow", "drawable", context.packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
}
