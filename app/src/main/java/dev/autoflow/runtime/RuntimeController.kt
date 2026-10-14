package dev.autoflow.runtime

import android.content.Context
import android.content.Intent
import dev.autoflow.core.engine.FlowEngine
import dev.autoflow.core.engine.LogEntry
import dev.autoflow.core.engine.LogLevel
import dev.autoflow.core.engine.LogSink
import dev.autoflow.core.engine.LogicExecutors
import dev.autoflow.core.engine.RunPhase
import dev.autoflow.core.engine.RunSnapshot
import dev.autoflow.core.engine.RunStatus
import dev.autoflow.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Lets the service and receivers reach the app-wide controller without depending on the app class. */
object Runtime {
    lateinit var controller: RuntimeController
    val isReady get() = ::controller.isInitialized
}

/**
 * Starts, stops, pauses and resumes flows, keeps the foreground service alive only while a flow is
 * active, and puts flows to sleep (no service at all) while they only wait for a far-off time.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class RuntimeController(
    private val context: Context,
    private val source: FlowSource,
    private val logSink: LogSink,
    val prefs: Prefs,
    val scope: CoroutineScope,
) {
    val host = AndroidHost(context, prefs)
    val engine = FlowEngine(LogicExecutors.all + AndroidExecutors.all, host, logSink, scope)
    private val store = SleepStore(File(context.filesDir, "sleeping.json"))
    private val activePrefs = context.getSharedPreferences("runtime", Context.MODE_PRIVATE)

    val statuses: StateFlow<Map<String, RunStatus>> get() = engine.statuses

    init {
        store.load().forEach { engine.setExternalStatus(sleeping(it)) }
        // Remember which flows are active, to bring them back if Android kills the process.
        scope.launch {
            engine.statuses
                .map { m -> m.values.filter { it.isActive }.map { it.flowId }.toSet() }
                .distinctUntilChanged()
                .debounce(500)
                .collect { ids -> activePrefs.edit().putStringSet(KEY_ACTIVE, ids).apply() }
        }
    }

    fun start(flowId: String) {
        scope.launch {
            val doc = source.load(flowId) ?: return@launch
            val snap = store.remove(flowId)
            if (engine.start(doc, snap)) ensureService()
            rescheduleResume()
        }
    }

    fun stop(flowId: String) {
        if (engine.isActive(flowId)) {
            engine.stop(flowId)
        } else {
            store.remove(flowId)?.let { s ->
                engine.setExternalStatus(RunStatus(flowId, s.runId, RunPhase.STOPPED, s.startedAt, detail = "Stopped"))
                rescheduleResume()
            }
        }
    }

    fun pause(flowId: String) = engine.pause(flowId)
    fun resume(flowId: String) = engine.resume(flowId)

    fun stopAll() {
        engine.stopAll()
        store.load().forEach { s ->
            engine.setExternalStatus(RunStatus(s.flowId, s.runId, RunPhase.STOPPED, s.startedAt, detail = "Stopped"))
        }
        store.clear()
        rescheduleResume()
    }

    fun forget(flowId: String) {
        stop(flowId)
        engine.clearStatus(flowId)
    }

    fun ensureService() {
        context.startForegroundService(Intent(context, FlowService::class.java))
    }

    /**
     * Called by the service while flows are quiet. If every active flow is only waiting for a time
     * at least [MIN_SLEEP_MS] away, saves them, sets one exact alarm, and lets the service stop.
     */
    fun tryHibernate(): Boolean {
        // Without exact alarms Android won't let the alarm restart the service, so stay awake.
        if (!host.canScheduleExact()) return false
        val snaps = engine.hibernate(MIN_SLEEP_MS) ?: return false
        store.addAll(snaps)
        rescheduleResume()
        return true
    }

    /** Wakes the flows that are due (called from the alarm). */
    suspend fun resumeDue() {
        val now = System.currentTimeMillis()
        val due = store.load().filter { it.wakeAt <= now + 2 * 60_000L }
        for (s in due) {
            store.remove(s.flowId)
            val doc = source.load(s.flowId)
            if (doc == null) continue
            engine.start(doc, s)
        }
        rescheduleResume()
    }

    /** After a reboot: wake sleeping flows that are due and start flows marked "start when tablet turns on". */
    suspend fun onBoot() {
        resumeDue()
        for (doc in source.bootFlows()) {
            if (!engine.isActive(doc.id) && store.load().none { it.flowId == doc.id }) engine.start(doc)
        }
    }

    /** When Android restarts the service after killing the app, start the flows that were active again. */
    suspend fun restoreAfterKill() {
        val ids = activePrefs.getStringSet(KEY_ACTIVE, emptySet()).orEmpty()
        for (id in ids) {
            if (engine.isActive(id)) continue
            val doc = source.load(id) ?: continue
            if (engine.start(doc)) {
                logSink.log(LogEntry(id, "restart", System.currentTimeMillis(), LogLevel.WARN, null, null,
                    "Android closed AutoFlow, so this flow was started again from the beginning"))
            }
        }
    }

    private fun rescheduleResume() {
        val pi = AndroidHost.wakeIntent(context, 0, WakeReceiver.ACTION_RESUME)
        val next = store.load().minOfOrNull { it.wakeAt }
        if (next == null) {
            host.cancelAlarm(pi)
        } else {
            host.setAlarm(next - 5_000, AndroidHost.wakeIntent(context, next, WakeReceiver.ACTION_RESUME))
        }
    }

    private fun sleeping(s: RunSnapshot) =
        RunStatus(s.flowId, s.runId, RunPhase.SLEEPING, s.startedAt, detail = "Sleeping", wakeAt = s.wakeAt, steps = s.steps)

    companion object {
        const val MIN_SLEEP_MS = 5 * 60_000L
        private const val KEY_ACTIVE = "active"
    }
}

/** Snapshots of sleeping flows, in a small JSON file. */
class SleepStore(private val file: File) {
    private val serializer = ListSerializer(RunSnapshot.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): List<RunSnapshot> = runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    @Synchronized
    fun addAll(snaps: List<RunSnapshot>) = save(load().filter { s -> snaps.none { it.flowId == s.flowId } } + snaps)

    @Synchronized
    fun remove(flowId: String): RunSnapshot? {
        val all = load()
        val hit = all.firstOrNull { it.flowId == flowId } ?: return null
        save(all - hit)
        return hit
    }

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(list: List<RunSnapshot>) {
        if (list.isEmpty()) {
            file.delete()
            return
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, list))
        tmp.renameTo(file)
    }
}
