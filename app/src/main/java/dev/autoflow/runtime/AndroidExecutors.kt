package dev.autoflow.runtime

import android.app.Notification
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Toast
import dev.autoflow.core.blocks.Permission
import dev.autoflow.core.engine.BlockException
import dev.autoflow.core.engine.Executor
import dev.autoflow.core.engine.RunContext
import dev.autoflow.core.expr.Value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Executors for blocks that need the device. Each one is a small suspend function keyed by
 * the block type from the catalog; add a new block by adding its spec there and a line here.
 */
object AndroidExecutors {
    private fun ex(f: Executor): Executor = f
    private val RunContext.android get() = host as AndroidHost

    private fun RunContext.require(p: Permission) {
        if (!Perms.isGranted(android.context, p, android.prefs)) {
            throw BlockException("Needs “${p.title}”. Open the flow and tap Allow on this block.")
        }
    }

    val all: Map<String, Executor> = mapOf(
        // ---------- triggers ----------
        "trigger.battery" to ex { c ->
            val below = c.raw("when") != "above"
            val level = c.number("level")
            val s = c.android.events.awaitTransition(c.android.events.battery(), c.bool("immediate")) {
                if (below) it.percent < level else it.percent > level
            }
            c.variableName("save")?.let { c.setVar(it, Value.Num(s.percent.toDouble())) }
            "next"
        },
        "trigger.charging" to ex { c ->
            val want = c.raw("state")
            val states = c.android.events.battery().distinctPlugged()
            val s = if (want == "change") {
                states.drop(1).first() // the first value is the current state; wait for the next change
            } else {
                c.android.events.awaitTransition(states, c.bool("immediate")) { it.plugged == (want == "plugged") }
            }
            c.variableName("save")?.let { c.setVar(it, Value.of(s.plugged)) }
            "next"
        },
        "trigger.wifi" to ex { c ->
            val e = c.android.events
            when (val ev = c.raw("event")) {
                "on", "off" -> e.awaitTransition(e.wifiEnabled(), c.bool("immediate")) { it == (ev == "on") }
                else -> e.awaitTransition(e.wifiConnected(), c.bool("immediate")) { it == (ev == "connected") }
            }
            "next"
        },
        "trigger.bluetooth" to ex { c ->
            c.require(Permission.BLUETOOTH)
            val e = c.android.events
            when (val ev = c.raw("event")) {
                "on", "off" -> e.awaitTransition(e.bluetoothEnabled(), c.bool("immediate")) { it == (ev == "on") }
                else -> {
                    val d = e.bluetoothDevices().first { it.connected == (ev == "connected") }
                    c.variableName("save")?.let { c.setVar(it, Value.Str(d.name)) }
                }
            }
            "next"
        },
        "trigger.app" to ex { c ->
            c.require(Permission.USAGE_ACCESS)
            val pkg = c.raw("package").trim()
            val opened = c.android.events.appsOpened().first { pkg.isEmpty() || it == pkg }
            c.variableName("save")?.let { c.setVar(it, Value.Str(opened)) }
            "next"
        },
        "trigger.notification" to ex { c ->
            c.require(Permission.NOTIFICATION_LISTENER)
            val pkg = c.raw("package").trim()
            val needle = c.text("contains")
            val n = c.android.events.notifications().first {
                (pkg.isEmpty() || it.packageName == pkg) &&
                    (needle.isEmpty() || it.title.contains(needle, true) || it.text.contains(needle, true))
            }
            c.setVar("notif_title", Value.Str(n.title))
            c.setVar("notif_text", Value.Str(n.text))
            c.setVar("notif_app", Value.Str(n.appName))
            "next"
        },
        "trigger.screen" to ex { c ->
            val want = c.raw("event")
            c.android.events.screenEvents().first { it == want }
            "next"
        },
        "trigger.headphones" to ex { c ->
            val want = c.raw("event") == "connected"
            val s = c.android.events.awaitTransition(c.android.events.headphones(), c.bool("immediate")) { it == want }
            c.variableName("save")?.let { c.setVar(it, Value.of(s)) }
            "next"
        },

        // ---------- actions ----------
        "action.notify" to ex { c ->
            c.require(Permission.NOTIFICATIONS)
            val ctx = c.android.context
            Notifier.ensureChannels(ctx)
            val channel = if (c.bool("silent")) Notifier.CH_FLOWS_SILENT else Notifier.CH_FLOWS
            val text = c.text("text")
            val n = Notification.Builder(ctx, channel)
                .setSmallIcon(Notifier.smallIcon(ctx))
                .setContentTitle(c.text("title"))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(Notifier.openApp(ctx))
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify((c.flow.id + c.block.id).hashCode(), n)
            "next"
        },
        "action.toast" to ex { c ->
            val text = c.text("text")
            val len = if (c.bool("long")) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            c.android.onMain { Toast.makeText(c.android.context, text, len).show() }
            "next"
        },
        "action.open" to ex { c ->
            val ctx = c.android.context
            if (c.raw("target") == "url") {
                val url = c.text("url").trim()
                if (url.isEmpty() || url == "https://") throw BlockException("Enter a link to open")
                c.android.startActivityFromFlow(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "links")
            } else {
                val pkg = c.raw("package").trim()
                if (pkg.isEmpty()) throw BlockException("Pick an app to open")
                val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    ?: throw BlockException("$pkg isn't installed, or can't be opened")
                c.android.startActivityFromFlow(launch, "apps")
            }
            "next"
        },
        "action.flashlight" to ex { c ->
            c.android.setTorch(c.raw("state"))
            "next"
        },
        "action.volume" to ex { c ->
            val am = c.android.context.getSystemService(AudioManager::class.java)
            val stream = when (c.raw("stream")) {
                "ring" -> AudioManager.STREAM_RING
                "notification" -> AudioManager.STREAM_NOTIFICATION
                "alarm" -> AudioManager.STREAM_ALARM
                else -> AudioManager.STREAM_MUSIC
            }
            val pct = c.number("level").coerceIn(0.0, 100.0)
            val max = am.getStreamMaxVolume(stream)
            val flags = if (c.bool("showUi")) AudioManager.FLAG_SHOW_UI else 0
            try {
                am.setStreamVolume(stream, (max * pct / 100.0).roundToInt(), flags)
            } catch (e: SecurityException) {
                throw BlockException("Android won't change this volume while Do Not Disturb is on")
            }
            "next"
        },
        "action.brightness" to ex { c ->
            c.require(Permission.WRITE_SETTINGS)
            val cr = c.android.context.contentResolver
            if (c.raw("mode") == "auto") {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            } else {
                val pct = c.number("level").coerceIn(0.0, 100.0)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (1 + 254 * pct / 100.0).roundToInt())
            }
            "next"
        },
        "action.settings" to ex { c ->
            val intent = when (c.raw("panel")) {
                "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                "internet" -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIRELESS_SETTINGS)
                else -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            c.android.startActivityFromFlow(intent, "settings")
            "next"
        },
        "action.vibrate" to ex { c ->
            val ctx = c.android.context
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            }
            if (!vibrator.hasVibrator()) throw BlockException("This device can't vibrate")
            val effect = when (c.raw("pattern")) {
                "long" -> VibrationEffect.createOneShot(900, VibrationEffect.DEFAULT_AMPLITUDE)
                "double" -> VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1)
                "custom" -> VibrationEffect.createOneShot(c.number("ms").toLong().coerceIn(1, 10_000), VibrationEffect.DEFAULT_AMPLITUDE)
                else -> VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrator.vibrate(effect)
            "next"
        },
        "action.speak" to ex { c ->
            c.android.speak(c.text("text"), c.number("rate").toFloat(), c.bool("wait"))
            "next"
        },
        "action.clipboard" to ex { c ->
            val cm = c.android.context.getSystemService(ClipboardManager::class.java)
            if (c.raw("mode") == "get") {
                val text = c.android.onMain { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(c.android.context)?.toString() }
                if (text == null && !AppForeground.isVisible) {
                    c.log("Android only shares the clipboard with the app on screen, so this read came back empty")
                }
                c.variableName("result")?.let { c.setVar(it, Value.Str(text.orEmpty())) }
            } else {
                val text = c.text("text")
                c.android.onMain { cm.setPrimaryClip(ClipData.newPlainText("AutoFlow", text)) }
            }
            "next"
        },
        "action.file.write" to ex { c ->
            val where = c.android.files.write(c.text("name").trim(), c.text("text"), c.bool("append"))
            c.log("Wrote $where")
            "ok"
        },
        "action.file.read" to ex { c ->
            val text = c.android.files.read(c.text("name").trim())
            c.variableName("result")?.let { c.setVar(it, Value.Str(text)) }
            "ok"
        },
        "action.http" to ex { c -> http(c) },
        "action.share" to ex { c ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c.text("text"))
            c.android.startActivityFromFlow(Intent.createChooser(send, c.text("title")), "the share sheet")
            "next"
        },

        // ---------- logic that needs the screen ----------
        "logic.dialog" to ex { c ->
            val kind = c.raw("kind")
            val answer = DialogBroker.ask(c.android, kind, c.text("title"), c.text("message"), c.text("default"), c.flow.name)
            if (kind == "input" && answer.ok) c.variableName("result")?.let { c.setVar(it, Value.Str(answer.text)) }
            if (answer.ok) "ok" else "cancel"
        },
    )

    private fun kotlinx.coroutines.flow.Flow<BatteryState>.distinctPlugged() =
        kotlinx.coroutines.flow.flow {
            var last: Boolean? = null
            collect { s -> if (s.plugged != last) { last = s.plugged; emit(s) } }
        }

    private suspend fun http(c: RunContext): String {
        val method = c.raw("method").ifBlank { "GET" }
        val url = c.text("url").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw BlockException("The URL should start with https://")
        val headers = c.text("headers").lines().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }
        val body = if (method == "POST" || method == "PUT") c.text("body") else null
        var attempt = 0
        while (true) {
            try {
                val (code, text) = withContext(Dispatchers.IO) {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    try {
                        conn.requestMethod = method
                        conn.connectTimeout = 15_000
                        conn.readTimeout = 20_000
                        conn.setRequestProperty("User-Agent", "AutoFlow/0.1 (Android)")
                        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                        if (body != null) {
                            conn.doOutput = true
                            if (headers.none { it.first.equals("Content-Type", true) }) {
                                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                            }
                            conn.outputStream.use { it.write(body.toByteArray()) }
                        }
                        val code = conn.responseCode
                        val stream = if (code >= 400) conn.errorStream else conn.inputStream
                        // Cap the size so a huge page can't eat the tablet's memory.
                        val bytes = stream?.use { s -> s.readNBytesCompat(MAX_RESPONSE) } ?: ByteArray(0)
                        code to bytes.decodeToString()
                    } finally {
                        conn.disconnect()
                    }
                }
                c.variableName("result")?.let { c.setVar(it, Value.Str(text)) }
                c.variableName("status")?.let { c.setVar(it, Value.Num(code.toDouble())) }
                return if (code in 200..399) "ok" else {
                    c.setVar("error", Value.Str("HTTP $code"))
                    c.log("Server answered $code", dev.autoflow.core.engine.LogLevel.WARN)
                    "fail"
                }
            } catch (e: java.io.IOException) {
                if (++attempt >= 2) {
                    c.variableName("status")?.let { c.setVar(it, Value.Num(0.0)) }
                    throw BlockException("Request failed: ${e.message ?: e::class.simpleName}")
                }
                delay(1_500)
            }
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private const val MAX_RESPONSE = 512 * 1024
}
