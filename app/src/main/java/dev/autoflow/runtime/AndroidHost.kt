package dev.autoflow.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.autoflow.core.engine.BlockException
import dev.autoflow.core.engine.Host
import dev.autoflow.core.model.FlowDoc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Where flows are loaded from (the Room repository in the app). */
interface FlowSource {
    suspend fun load(id: String): FlowDoc?
    suspend fun bootFlows(): List<FlowDoc>
}

/** Tracks whether one of our activities is on screen; Android lets visible apps start activities freely. */
object AppForeground {
    private val started = AtomicInteger(0)
    val isVisible get() = started.get() > 0
    fun onStart() { started.incrementAndGet() }
    fun onStop() { started.updateAndGet { maxOf(0, it - 1) } }
}

/** The engine's view of the device: timers that survive deep sleep, events, TTS, torch, files, dialogs. */
class AndroidHost(val context: Context, val prefs: dev.autoflow.data.Prefs) : Host {
    val events = EventHub(context)
    val files = FileAccess(context) { prefs.folderUri }
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    // ---------- long waits ----------

    /** Fired by [WakeReceiver] when an alarm we set goes off. */
    val alarmTicks = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    override suspend fun delayUntil(epochMs: Long) {
        val first = epochMs - clock.now()
        if (first <= 0) return
        if (first < ALARM_THRESHOLD_MS) {
            delay(first)
            return
        }
        // Coroutine timers stop while the CPU sleeps, so an alarm wakes the device for long waits.
        val pi = scheduleWake(epochMs)
        try {
            while (true) {
                val left = epochMs - clock.now()
                if (left <= 0) break
                withTimeoutOrNull(minOf(left, 60_000L)) { alarmTicks.first { it >= epochMs - 1_000 } }
            }
        } finally {
            alarmManager.cancel(pi)
        }
    }

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun scheduleWake(at: Long): PendingIntent {
        val pi = wakeIntent(context, at, WakeReceiver.ACTION_TICK)
        setAlarm(at, pi)
        return pi
    }

    fun setAlarm(at: Long, pi: PendingIntent) {
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancelAlarm(pi: PendingIntent) = alarmManager.cancel(pi)

    // ---------- text to speech ----------

    private var tts: TextToSpeech? = null
    private var ttsReady: CompletableDeferred<Boolean>? = null
    private val utterances = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private suspend fun ttsEngine(): TextToSpeech {
        val ready = synchronized(this) {
            ttsReady ?: CompletableDeferred<Boolean>().also { d ->
                ttsReady = d
                val engine = TextToSpeech(context.applicationContext) { status -> d.complete(status == TextToSpeech.SUCCESS) }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) = Unit
                    override fun onDone(utteranceId: String) { utterances.remove(utteranceId)?.complete(Unit) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String) { utterances.remove(utteranceId)?.complete(Unit) }
                })
                tts = engine
            }
        }
        if (!ready.await()) {
            shutdownTts()
            throw BlockException("Text to speech isn't available. Check that a speech engine is installed.")
        }
        return tts!!
    }

    suspend fun speak(text: String, rate: Float, wait: Boolean) {
        val engine = ttsEngine()
        engine.language = Locale.getDefault()
        engine.setSpeechRate(rate.coerceIn(0.1f, 4f))
        val id = "u" + System.nanoTime()
        val done = CompletableDeferred<Unit>()
        utterances[id] = done
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (wait) {
            try {
                withTimeoutOrNull(5 * 60_000L) { done.await() }
            } finally {
                utterances.remove(id)
            }
        }
    }

    fun shutdownTts() = synchronized(this) {
        tts?.shutdown()
        tts = null
        ttsReady = null
        utterances.values.forEach { it.complete(Unit) }
        utterances.clear()
    }

    // ---------- flashlight ----------

    private var torchOn = false
    private var torchCallbackRegistered = false

    fun setTorch(mode: String) {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cid ->
            val c = cm.getCameraCharacteristics(cid)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull { cid ->
            cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw BlockException("This device has no flashlight")
        if (!torchCallbackRegistered) {
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == id) torchOn = enabled
                }
            }, Handler(Looper.getMainLooper()))
            torchCallbackRegistered = true
        }
        val on = when (mode) {
            "on" -> true
            "off" -> false
            else -> !torchOn
        }
        cm.setTorchMode(id, on)
        torchOn = on
    }

    // ---------- misc ----------

    val isInteractive: Boolean get() = context.getSystemService(PowerManager::class.java).isInteractive

    suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    fun startActivityFromFlow(intent: Intent, what: String) {
        if (!AppForeground.isVisible && !Perms.isGranted(context, dev.autoflow.core.blocks.Permission.OVERLAY, prefs)) {
            throw BlockException(
                "Android blocks apps from opening $what in the background. Allow “Display over other apps” for AutoFlow."
            )
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        /** Waits shorter than this just use a coroutine timer. */
        const val ALARM_THRESHOLD_MS = 45_000L

        fun wakeIntent(context: Context, at: Long, action: String): PendingIntent {
            val intent = Intent(context, WakeReceiver::class.java).setAction(action).putExtra(WakeReceiver.EXTRA_AT, at)
            val code = if (action == WakeReceiver.ACTION_RESUME) 1 else ((at / 1000) % Int.MAX_VALUE).toInt()
            return PendingIntent.getBroadcast(
                context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
