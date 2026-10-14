package dev.autoflow.runtime

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connects the "User dialog" block to the dialog screen. The block waits on [ask]; the dialog
 * screen reads the request, shows it, and calls [answer].
 */
object DialogBroker {
    data class Request(
        val id: Int,
        val kind: String,
        val title: String,
        val message: String,
        val default: String,
        val flowName: String,
    )

    data class Answer(val ok: Boolean, val text: String = "")

    const val EXTRA_ID = "dialog_id"
    private const val ACTIVITY = "dev.autoflow.ui.android.DialogActivity"

    private val ids = AtomicInteger(1000)
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<Answer>>()
    private val _open = MutableStateFlow<Map<Int, Request>>(emptyMap())

    /** Requests still waiting for an answer; the dialog screen closes itself when its request goes away. */
    val open: StateFlow<Map<Int, Request>> = _open

    suspend fun ask(host: AndroidHost, kind: String, title: String, message: String, default: String, flowName: String): Answer {
        val context = host.context
        val req = Request(ids.incrementAndGet(), kind, title, message, default, flowName)
        val deferred = CompletableDeferred<Answer>()
        waiting[req.id] = deferred
        _open.update { it + (req.id to req) }
        val intent = Intent().setClassName(context, ACTIVITY).putExtra(EXTRA_ID, req.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val nm = context.getSystemService(NotificationManager::class.java)
        try {
            // A heads-up notification always works; opening the dialog directly needs AutoFlow on
            // screen or "Display over other apps".
            Notifier.ensureChannels(context)
            val pi = PendingIntent.getActivity(
                context, req.id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                req.id,
                Notification.Builder(context, Notifier.CH_DIALOGS)
                    .setSmallIcon(Notifier.smallIcon(context))
                    .setContentTitle(title.ifBlank { flowName })
                    .setContentText(message.ifBlank { "Tap to answer" })
                    .setContentIntent(pi)
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setOngoing(true)
                    .build(),
            )
            runCatching { host.startActivityFromFlow(intent, "a dialog") }
            return deferred.await()
        } finally {
            waiting.remove(req.id)
            _open.update { it - req.id }
            nm.cancel(req.id)
        }
    }

    fun request(id: Int): Request? = _open.value[id]

    fun answer(id: Int, answer: Answer) {
        waiting[id]?.complete(answer)
    }
}
