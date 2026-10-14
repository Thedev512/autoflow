package dev.autoflow.runtime

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.autoflow.core.engine.RunPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while at least one flow is active, as Android requires for background
 * work, and stops itself as soon as nothing runs or everything is asleep until a scheduled time.
 */
class FlowService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        goForeground("Starting…", 0)
        if (!Runtime.isReady) {
            stopSelf()
            return
        }
        val controller = Runtime.controller
        scope.launch {
            controller.statuses.collectLatest { map ->
                val active = map.values.filter { it.isActive }
                if (active.isEmpty()) {
                    // Flows being resumed or restored may still be loading; give them a moment.
                    delay(5_000)
                    if (controller.engine.activeCount() == 0) finish()
                    return@collectLatest
                }
                delay(400) // let bursts of status changes settle before touching the notification
                val waiting = active.count { it.phase == RunPhase.WAITING }
                val text = active.joinToString(" · ") { it.detail.ifBlank { "Running" } }.take(200)
                if (text != lastText) {
                    lastText = text
                    goForeground(text, active.size, waiting)
                }
                // Quiet for a while? Maybe everything just waits for a far-off time: sleep fully.
                while (true) {
                    delay(30_000)
                    if (controller.tryHibernate()) {
                        finish()
                        return@collectLatest
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must be answered with startForeground() quickly.
        goForeground(lastText.ifEmpty { "Starting…" }, 0)
        if (!Runtime.isReady) return START_NOT_STICKY
        val controller = Runtime.controller
        when (intent?.action) {
            ACTION_STOP_ALL -> controller.stopAll()
            ACTION_RESUME -> scope.launch { controller.resumeDue() }
            ACTION_BOOT -> scope.launch { controller.onBoot() }
            null -> if (controller.engine.activeCount() == 0) scope.launch { controller.restoreAfterKill() }
        }
        return START_STICKY
    }

    private fun goForeground(text: String, count: Int, waiting: Int = 0) {
        val title = when {
            count == 0 -> "AutoFlow"
            count == 1 -> "1 flow active"
            else -> "$count flows active"
        }
        val stopAll = PendingIntent.getService(
            this, 2, Intent(this, FlowService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, Notifier.CH_RUNTIME)
            .setSmallIcon(Notifier.smallIcon(this))
            .setContentTitle(title)
            .setContentText(if (waiting == count && count > 0) "Waiting quietly · $text" else text)
            .setContentIntent(Notifier.openApp(this, 1))
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Stop all", stopAll).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (Runtime.isReady && Runtime.controller.engine.activeCount() == 0) Runtime.controller.host.shutdownTts()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 7
        const val ACTION_STOP_ALL = "dev.autoflow.STOP_ALL"
        const val ACTION_RESUME = "dev.autoflow.RESUME"
        const val ACTION_BOOT = "dev.autoflow.BOOT"
    }
}

/** Alarm wake-ups: ticks for long waits inside a running flow, and resuming sleeping flows. */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Hold the CPU briefly so the woken flow gets to run before the device dozes off again.
        context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoFlow:wake")
            .acquire(10_000)
        when (intent.action) {
            ACTION_TICK -> if (Runtime.isReady) Runtime.controller.host.alarmTicks.tryEmit(intent.getLongExtra(EXTRA_AT, 0))
            ACTION_RESUME -> context.startForegroundService(Intent(context, FlowService::class.java).setAction(FlowService.ACTION_RESUME))
        }
    }

    companion object {
        const val ACTION_TICK = "dev.autoflow.TICK"
        const val ACTION_RESUME = "dev.autoflow.WAKE_RESUME"
        const val EXTRA_AT = "at"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        context.startForegroundService(Intent(context, FlowService::class.java).setAction(FlowService.ACTION_BOOT))
    }
}

/** Passes notifications from other apps to "Notification received" triggers. */
class AutoFlowNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        NotificationBus.post(NotificationEvent(sbn.packageName, app, title, text))
    }
}
