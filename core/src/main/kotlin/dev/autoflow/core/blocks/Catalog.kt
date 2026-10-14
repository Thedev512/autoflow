package dev.autoflow.core.blocks

import dev.autoflow.core.blocks.ParamKind.APP
import dev.autoflow.core.blocks.ParamKind.BOOLEAN
import dev.autoflow.core.blocks.ParamKind.CHOICE
import dev.autoflow.core.blocks.ParamKind.DAYS
import dev.autoflow.core.blocks.ParamKind.EXPRESSION
import dev.autoflow.core.blocks.ParamKind.MULTILINE
import dev.autoflow.core.blocks.ParamKind.NUMBER
import dev.autoflow.core.blocks.ParamKind.TEXT
import dev.autoflow.core.blocks.ParamKind.TIME
import dev.autoflow.core.blocks.ParamKind.VARIABLE

/**
 * Every block the editor knows about. To add a block:
 *  1. add a [BlockSpec] here (what the editor shows and stores),
 *  2. register an executor for its `type` (pure logic in core `LogicExecutors`,
 *     Android work in the app's `AndroidExecutors`).
 */
object BlockCatalog {
    private fun choices(vararg pairs: Pair<String, String>) = pairs.map { Choice(it.first, it.second) }
    private fun labelOf(spec: List<Choice>, v: String?) = spec.firstOrNull { it.value == v }?.label ?: v.orEmpty()
    private fun appName(p: Map<String, String>, key: String = "package") =
        p["${key}Name"].takeUnless { it.isNullOrBlank() } ?: p[key].orEmpty()

    // ---------- Triggers ----------

    private val timeModes = choices("daily" to "At a time of day", "interval" to "Every few minutes")
    private val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun daysLabel(days: String): String {
        val set = days.mapNotNull { it.digitToIntOrNull() }.filter { it in 1..7 }.toSortedSet()
        return when {
            set.size == 7 || set.isEmpty() -> "every day"
            set == sortedSetOf(1, 2, 3, 4, 5) -> "weekdays"
            set == sortedSetOf(6, 7) -> "weekends"
            else -> set.joinToString(" ") { dayNames[it - 1] }
        }
    }

    val time = BlockSpec(
        type = "trigger.time", category = Category.TRIGGER, title = "Time / schedule", icon = "schedule",
        description = "Waits until a time of day, or for a repeating interval, then continues.",
        waits = true,
        params = listOf(
            ParamSpec("mode", "Fire", CHOICE, "daily", timeModes),
            ParamSpec("time", "Time", TIME, "08:00", hint = "24-hour, like 07:30", showWhen = "mode" to setOf("daily")),
            ParamSpec("days", "Days", DAYS, "1234567", showWhen = "mode" to setOf("daily")),
            ParamSpec("minutes", "Every (minutes)", NUMBER, "15", showWhen = "mode" to setOf("interval")),
        ),
        permissions = setOf(Permission.EXACT_ALARM),
        summary = { p ->
            if (p["mode"] == "interval") "Every ${p["minutes"]} min" else "${p["time"]}, ${daysLabel(p["days"].orEmpty())}"
        },
    )

    private val belowAbove = choices("below" to "Drops below", "above" to "Rises above")
    val battery = BlockSpec(
        type = "trigger.battery", category = Category.TRIGGER, title = "Battery level", icon = "battery",
        description = "Waits until the battery drops below or rises above a level. Saves the level in a variable.",
        waits = true,
        params = listOf(
            ParamSpec("when", "When it", CHOICE, "below", belowAbove),
            ParamSpec("level", "Level (%)", NUMBER, "20"),
            ParamSpec("immediate", "Continue right away if already true", BOOLEAN, "false"),
            ParamSpec("save", "Save level to", VARIABLE, "battery"),
        ),
        summary = { p -> "${labelOf(belowAbove, p["when"])} ${p["level"]}%" },
    )

    private val chargeStates = choices("plugged" to "Plugged in", "unplugged" to "Unplugged", "change" to "Either")
    val charging = BlockSpec(
        type = "trigger.charging", category = Category.TRIGGER, title = "Charging", icon = "power",
        description = "Waits for the charger to be plugged in or unplugged.",
        waits = true,
        params = listOf(
            ParamSpec("state", "When", CHOICE, "plugged", chargeStates),
            ParamSpec("immediate", "Continue right away if already true", BOOLEAN, "false"),
            ParamSpec("save", "Save charging state to", VARIABLE, "charging"),
        ),
        summary = { p -> labelOf(chargeStates, p["state"]) },
    )

    private val wifiEvents = choices(
        "on" to "Turned on", "off" to "Turned off", "connected" to "Connected", "disconnected" to "Disconnected",
    )
    val wifi = BlockSpec(
        type = "trigger.wifi", category = Category.TRIGGER, title = "Wi-Fi change", icon = "wifi",
        description = "Waits for Wi-Fi to turn on or off, or to connect to or disconnect from a network.",
        waits = true,
        params = listOf(
            ParamSpec("event", "When Wi-Fi is", CHOICE, "connected", wifiEvents),
            ParamSpec("immediate", "Continue right away if already true", BOOLEAN, "false"),
        ),
        summary = { p -> labelOf(wifiEvents, p["event"]) },
    )

    private val btEvents = choices(
        "on" to "Turned on", "off" to "Turned off", "connected" to "Device connected", "disconnected" to "Device disconnected",
    )
    val bluetooth = BlockSpec(
        type = "trigger.bluetooth", category = Category.TRIGGER, title = "Bluetooth change", icon = "bluetooth",
        description = "Waits for Bluetooth to turn on or off, or for a device to connect or disconnect. Saves the device name.",
        waits = true,
        params = listOf(
            ParamSpec("event", "When Bluetooth is", CHOICE, "connected", btEvents),
            ParamSpec("immediate", "Continue right away if already true", BOOLEAN, "false", showWhen = "event" to setOf("on", "off")),
            ParamSpec("save", "Save device name to", VARIABLE, "bt_device", showWhen = "event" to setOf("connected", "disconnected")),
        ),
        permissions = setOf(Permission.BLUETOOTH),
        summary = { p -> labelOf(btEvents, p["event"]) },
    )

    val appOpened = BlockSpec(
        type = "trigger.app", category = Category.TRIGGER, title = "App opened", icon = "apps",
        description = "Waits until an app comes to the screen. Leave the app empty to fire for any app.",
        waits = true,
        params = listOf(
            ParamSpec("package", "App", APP, ""),
            ParamSpec("save", "Save package name to", VARIABLE, "app"),
        ),
        permissions = setOf(Permission.USAGE_ACCESS),
        summary = { p -> appName(p).ifBlank { "Any app" } },
    )

    val notification = BlockSpec(
        type = "trigger.notification", category = Category.TRIGGER, title = "Notification received", icon = "notification",
        description = "Waits for a notification from another app. Saves notif_title, notif_text and notif_app.",
        waits = true,
        params = listOf(
            ParamSpec("package", "From app", APP, "", hint = "Empty means any app"),
            ParamSpec("contains", "Text contains", TEXT, "", hint = "Empty means anything"),
        ),
        permissions = setOf(Permission.NOTIFICATION_LISTENER),
        summary = { p ->
            val app = appName(p).ifBlank { "Any app" }
            if (p["contains"].isNullOrBlank()) app else "$app · “${p["contains"]}”"
        },
    )

    private val screenEvents = choices("on" to "Turns on", "off" to "Turns off", "unlocked" to "Is unlocked")
    val screen = BlockSpec(
        type = "trigger.screen", category = Category.TRIGGER, title = "Screen on/off", icon = "screen",
        description = "Waits for the screen to turn on, turn off, or be unlocked.",
        waits = true,
        params = listOf(ParamSpec("event", "When the screen", CHOICE, "on", screenEvents)),
        summary = { p -> labelOf(screenEvents, p["event"]) },
    )

    private val plugEvents = choices("connected" to "Connected", "disconnected" to "Disconnected")
    val headphones = BlockSpec(
        type = "trigger.headphones", category = Category.TRIGGER, title = "Headphones", icon = "headphones",
        description = "Waits for wired, USB or Bluetooth headphones to connect or disconnect.",
        waits = true,
        params = listOf(
            ParamSpec("event", "When headphones are", CHOICE, "connected", plugEvents),
            ParamSpec("immediate", "Continue right away if already true", BOOLEAN, "false"),
            ParamSpec("save", "Save state to", VARIABLE, "headphones"),
        ),
        summary = { p -> labelOf(plugEvents, p["event"]) },
    )

    // ---------- Actions ----------

    val notify = BlockSpec(
        type = "action.notify", category = Category.ACTION, title = "Notification", icon = "notify",
        description = "Shows a notification.",
        params = listOf(
            ParamSpec("title", "Title", TEXT, "AutoFlow"),
            ParamSpec("text", "Text", TEXT, "Hello from your flow"),
            ParamSpec("silent", "Silent", BOOLEAN, "false"),
        ),
        permissions = setOf(Permission.NOTIFICATIONS),
        summary = { p -> p["title"].orEmpty() },
    )

    val toast = BlockSpec(
        type = "action.toast", category = Category.ACTION, title = "Toast", icon = "toast",
        description = "Shows a short message at the bottom of the screen.",
        params = listOf(
            ParamSpec("text", "Message", TEXT, "Hello"),
            ParamSpec("long", "Show longer", BOOLEAN, "false"),
        ),
        summary = { p -> p["text"].orEmpty() },
    )

    private val openTargets = choices("app" to "An app", "url" to "A link")
    val open = BlockSpec(
        type = "action.open", category = Category.ACTION, title = "Open app or URL", icon = "open",
        description = "Opens an app, or a link in the browser.",
        params = listOf(
            ParamSpec("target", "Open", CHOICE, "app", openTargets),
            ParamSpec("package", "App", APP, "", showWhen = "target" to setOf("app")),
            ParamSpec("url", "Link", TEXT, "https://", showWhen = "target" to setOf("url")),
        ),
        permissions = setOf(Permission.OVERLAY),
        summary = { p -> if (p["target"] == "url") p["url"].orEmpty() else appName(p).ifBlank { "Pick an app" } },
    )

    private val torchStates = choices("on" to "On", "off" to "Off", "toggle" to "Toggle")
    val flashlight = BlockSpec(
        type = "action.flashlight", category = Category.ACTION, title = "Flashlight", icon = "flashlight",
        description = "Turns the camera flashlight on, off, or toggles it.",
        params = listOf(ParamSpec("state", "Turn", CHOICE, "toggle", torchStates)),
        summary = { p -> labelOf(torchStates, p["state"]) },
    )

    private val streams = choices("media" to "Media", "ring" to "Ringtone", "notification" to "Notifications", "alarm" to "Alarm")
    val volume = BlockSpec(
        type = "action.volume", category = Category.ACTION, title = "Volume", icon = "volume",
        description = "Sets a volume level in percent.",
        params = listOf(
            ParamSpec("stream", "Volume", CHOICE, "media", streams),
            ParamSpec("level", "Level (%)", NUMBER, "50"),
            ParamSpec("showUi", "Show volume slider", BOOLEAN, "false"),
        ),
        summary = { p -> "${labelOf(streams, p["stream"])} ${p["level"]}%" },
    )

    private val brightnessModes = choices("level" to "Set level", "auto" to "Automatic")
    val brightness = BlockSpec(
        type = "action.brightness", category = Category.ACTION, title = "Brightness", icon = "brightness",
        description = "Sets screen brightness, or switches to automatic.",
        params = listOf(
            ParamSpec("mode", "Mode", CHOICE, "level", brightnessModes),
            ParamSpec("level", "Level (%)", NUMBER, "60", showWhen = "mode" to setOf("level")),
        ),
        permissions = setOf(Permission.WRITE_SETTINGS),
        summary = { p -> if (p["mode"] == "auto") "Automatic" else "${p["level"]}%" },
    )

    private val panels = choices("wifi" to "Wi-Fi", "bluetooth" to "Bluetooth", "internet" to "Internet")
    val settingsPanel = BlockSpec(
        type = "action.settings", category = Category.ACTION, title = "Wi-Fi / Bluetooth settings", icon = "settings",
        description = "Opens the Wi-Fi, Bluetooth or internet panel. Android doesn't let apps flip these switches themselves.",
        params = listOf(ParamSpec("panel", "Open", CHOICE, "wifi", panels)),
        permissions = setOf(Permission.OVERLAY),
        summary = { p -> labelOf(panels, p["panel"]) },
    )

    private val vibePatterns = choices("short" to "Short", "long" to "Long", "double" to "Double", "custom" to "Custom")
    val vibrate = BlockSpec(
        type = "action.vibrate", category = Category.ACTION, title = "Vibrate", icon = "vibrate",
        description = "Vibrates the device.",
        params = listOf(
            ParamSpec("pattern", "Pattern", CHOICE, "short", vibePatterns),
            ParamSpec("ms", "Length (ms)", NUMBER, "400", showWhen = "pattern" to setOf("custom")),
        ),
        summary = { p -> labelOf(vibePatterns, p["pattern"]) },
    )

    val speak = BlockSpec(
        type = "action.speak", category = Category.ACTION, title = "Text to speech", icon = "speak",
        description = "Reads text out loud.",
        params = listOf(
            ParamSpec("text", "Say", TEXT, "Hello"),
            ParamSpec("rate", "Speed", NUMBER, "1", hint = "1 is normal, 0.5 is slow, 2 is fast"),
            ParamSpec("wait", "Wait until finished", BOOLEAN, "true"),
        ),
        summary = { p -> p["text"].orEmpty() },
    )

    private val clipModes = choices("set" to "Copy text", "get" to "Read clipboard")
    val clipboard = BlockSpec(
        type = "action.clipboard", category = Category.ACTION, title = "Clipboard", icon = "clipboard",
        description = "Copies text to the clipboard, or reads it. Android only allows reading while AutoFlow is on screen.",
        params = listOf(
            ParamSpec("mode", "Action", CHOICE, "set", clipModes),
            ParamSpec("text", "Text", TEXT, "", showWhen = "mode" to setOf("set")),
            ParamSpec("result", "Save to", VARIABLE, "clipboard", showWhen = "mode" to setOf("get")),
        ),
        summary = { p -> labelOf(clipModes, p["mode"]) },
    )

    val writeFile = BlockSpec(
        type = "action.file.write", category = Category.ACTION, title = "Write file", icon = "save",
        description = "Writes text to a file in your chosen folder.",
        params = listOf(
            ParamSpec("name", "File name", TEXT, "notes.txt"),
            ParamSpec("text", "Text", MULTILINE, "{time()} - hello"),
            ParamSpec("append", "Add to the end instead of replacing", BOOLEAN, "true"),
        ),
        outputs = listOf(Ports.OK, Ports.FAIL),
        permissions = setOf(Permission.FOLDER),
        summary = { p -> p["name"].orEmpty() },
    )

    val readFile = BlockSpec(
        type = "action.file.read", category = Category.ACTION, title = "Read file", icon = "file",
        description = "Reads a text file from your chosen folder into a variable.",
        params = listOf(
            ParamSpec("name", "File name", TEXT, "notes.txt"),
            ParamSpec("result", "Save to", VARIABLE, "file"),
        ),
        outputs = listOf(Ports.OK, Ports.FAIL),
        permissions = setOf(Permission.FOLDER),
        summary = { p -> p["name"].orEmpty() },
    )

    private val methods = choices("GET" to "GET", "POST" to "POST", "PUT" to "PUT", "DELETE" to "DELETE")
    val http = BlockSpec(
        type = "action.http", category = Category.ACTION, title = "HTTP request", icon = "http",
        description = "Calls a web address and saves the response and status code.",
        params = listOf(
            ParamSpec("method", "Method", CHOICE, "GET", methods),
            ParamSpec("url", "URL", TEXT, "https://api.github.com/zen"),
            ParamSpec("headers", "Headers", MULTILINE, "", hint = "One per line, like Accept: application/json"),
            ParamSpec("body", "Body", MULTILINE, "", showWhen = "method" to setOf("POST", "PUT")),
            ParamSpec("result", "Save response to", VARIABLE, "response"),
            ParamSpec("status", "Save status code to", VARIABLE, "status"),
        ),
        outputs = listOf(Ports.OK, Ports.FAIL),
        summary = { p -> "${p["method"]} ${p["url"].orEmpty().removePrefix("https://").removePrefix("http://")}" },
    )

    val share = BlockSpec(
        type = "action.share", category = Category.ACTION, title = "Share", icon = "share",
        description = "Opens the share sheet with some text.",
        params = listOf(
            ParamSpec("text", "Text", TEXT, ""),
            ParamSpec("title", "Sheet title", TEXT, "Share"),
        ),
        permissions = setOf(Permission.OVERLAY),
        summary = { p -> p["text"].orEmpty() },
    )

    // ---------- Logic ----------

    val start = BlockSpec(
        type = "flow.start", category = Category.LOGIC, title = "Flow start", icon = "play",
        description = "Where the flow begins. Add more than one to run several paths side by side.",
        hasInput = false,
    )

    val stop = BlockSpec(
        type = "flow.stop", category = Category.LOGIC, title = "Stop flow", icon = "stop",
        description = "Ends this path of the flow.",
        outputs = emptyList(),
    )

    val ifElse = BlockSpec(
        type = "logic.if", category = Category.LOGIC, title = "If / else", icon = "branch",
        description = "Goes to Yes when the condition is true, otherwise to No.",
        params = listOf(
            ParamSpec("condition", "Condition", EXPRESSION, "battery < 20", hint = "e.g. x > 3 && name == \"Ryan\""),
        ),
        outputs = listOf(Ports.YES, Ports.NO),
        summary = { p -> p["condition"].orEmpty() },
    )

    val wait = BlockSpec(
        type = "logic.wait", category = Category.LOGIC, title = "Wait", icon = "timer",
        description = "Pauses this path for a while.",
        waits = true,
        params = listOf(ParamSpec("seconds", "Seconds", NUMBER, "5")),
        summary = { p -> "${p["seconds"]} s" },
    )

    val loop = BlockSpec(
        type = "logic.loop", category = Category.LOGIC, title = "Repeat", icon = "loop",
        description = "Goes to Repeat the given number of times, then to Done. Connect the end of the repeated part back to this block.",
        params = listOf(
            ParamSpec("count", "Times", NUMBER, "3"),
            ParamSpec("counter", "Counter variable", VARIABLE, "i", hint = "Counts 0, 1, 2, ..."),
        ),
        outputs = listOf(Ports.LOOP, Ports.DONE),
        summary = { p -> "${p["count"]} times" },
    )

    val setVar = BlockSpec(
        type = "logic.set", category = Category.LOGIC, title = "Set variable", icon = "variable",
        description = "Stores a value. Use it later as {name} in text, or by name in expressions.",
        params = listOf(
            ParamSpec("name", "Variable", VARIABLE, "x"),
            ParamSpec("value", "Value", EXPRESSION, "0", hint = "Text needs quotes: \"hello\""),
        ),
        summary = { p -> "${p["name"]} = ${p["value"]}" },
    )

    val mathOps = choices(
        "add" to "a + b", "subtract" to "a − b", "multiply" to "a × b", "divide" to "a ÷ b",
        "modulo" to "Remainder of a ÷ b", "power" to "a to the power b", "min" to "Smaller of a, b",
        "max" to "Larger of a, b", "round" to "Round a", "abs" to "Absolute a", "sqrt" to "Square root of a",
    )
    val unaryMath = setOf("round", "abs", "sqrt")
    val math = BlockSpec(
        type = "logic.math", category = Category.LOGIC, title = "Math", icon = "calculate",
        description = "Does arithmetic and saves the result.",
        params = listOf(
            ParamSpec("op", "Operation", CHOICE, "add", mathOps),
            ParamSpec("a", "a", NUMBER, "1"),
            ParamSpec("b", "b", NUMBER, "1", showWhen = "op" to (mathOps.map { it.value }.toSet() - unaryMath)),
            ParamSpec("result", "Save to", VARIABLE, "result"),
        ),
        summary = { p ->
            val a = p["a"].orEmpty()
            val b = p["b"].orEmpty()
            val op = when (p["op"]) {
                "add" -> "$a + $b"
                "subtract" -> "$a − $b"
                "multiply" -> "$a × $b"
                "divide" -> "$a ÷ $b"
                "modulo" -> "$a mod $b"
                "power" -> "$a ^ $b"
                "min" -> "min($a, $b)"
                "max" -> "max($a, $b)"
                "round" -> "round($a)"
                "abs" -> "abs($a)"
                "sqrt" -> "√$a"
                else -> "?"
            }
            "${p["result"]} = $op"
        },
    )

    val textOps = choices(
        "join" to "Join", "upper" to "UPPERCASE", "lower" to "lowercase", "length" to "Length", "trim" to "Trim spaces",
        "replace" to "Replace", "substring" to "Part of text", "split" to "Split and pick", "contains" to "Contains",
    )
    val text = BlockSpec(
        type = "logic.text", category = Category.LOGIC, title = "Text tools", icon = "text",
        description = "Changes or inspects text and saves the result.",
        params = listOf(
            ParamSpec("op", "Operation", CHOICE, "join", textOps),
            ParamSpec("text", "Text", TEXT, "Hello"),
            ParamSpec(
                "arg1", "With", TEXT, " world",
                hint = "Join: text to add. Replace: find. Part: start index. Split: separator. Contains: what to look for.",
                showWhen = "op" to setOf("join", "replace", "substring", "split", "contains"),
            ),
            ParamSpec(
                "arg2", "And", TEXT, "",
                hint = "Replace: replace with. Part: length. Split: which piece (0 is first).",
                showWhen = "op" to setOf("replace", "substring", "split"),
            ),
            ParamSpec("result", "Save to", VARIABLE, "text"),
        ),
        summary = { p -> "${p["result"]} = ${labelOf(textOps, p["op"])}" },
    )

    private val randomModes = choices("int" to "Whole number", "decimal" to "Decimal", "pick" to "Pick from a list")
    val random = BlockSpec(
        type = "logic.random", category = Category.LOGIC, title = "Random", icon = "casino",
        description = "Picks a random number or a random item from a list.",
        params = listOf(
            ParamSpec("mode", "Kind", CHOICE, "int", randomModes),
            ParamSpec("min", "From", NUMBER, "1", showWhen = "mode" to setOf("int", "decimal")),
            ParamSpec("max", "To", NUMBER, "6", showWhen = "mode" to setOf("int", "decimal")),
            ParamSpec("list", "Items", TEXT, "red, green, blue", hint = "Separated by commas", showWhen = "mode" to setOf("pick")),
            ParamSpec("result", "Save to", VARIABLE, "dice"),
        ),
        summary = { p ->
            if (p["mode"] == "pick") "${p["result"]} = one of ${p["list"]}" else "${p["result"]} = ${p["min"]}…${p["max"]}"
        },
    )

    private val dialogKinds = choices("message" to "Message", "confirm" to "Yes / No", "input" to "Ask for text")
    val dialog = BlockSpec(
        type = "logic.dialog", category = Category.LOGIC, title = "User dialog", icon = "chat",
        description = "Shows a dialog and waits for an answer. OK means Yes or a submitted answer.",
        waits = true,
        params = listOf(
            ParamSpec("kind", "Kind", CHOICE, "confirm", dialogKinds),
            ParamSpec("title", "Title", TEXT, "AutoFlow"),
            ParamSpec("message", "Message", TEXT, "Continue?"),
            ParamSpec("default", "Prefilled answer", TEXT, "", showWhen = "kind" to setOf("input")),
            ParamSpec("result", "Save answer to", VARIABLE, "answer", showWhen = "kind" to setOf("input")),
        ),
        outputs = listOf(Ports.OK, Ports.CANCEL),
        permissions = setOf(Permission.OVERLAY),
        summary = { p -> p["message"].orEmpty() },
    )

    val log = BlockSpec(
        type = "logic.log", category = Category.LOGIC, title = "Log", icon = "log",
        description = "Writes a line to this flow's log.",
        params = listOf(ParamSpec("message", "Message", TEXT, "x is {x}")),
        summary = { p -> p["message"].orEmpty() },
    )

    val all: List<BlockSpec> = listOf(
        time, battery, charging, wifi, bluetooth, appOpened, notification, screen, headphones,
        notify, toast, open, flashlight, volume, brightness, settingsPanel, vibrate, speak, clipboard,
        writeFile, readFile, http, share,
        start, stop, ifElse, wait, loop, setVar, math, text, random, dialog, log,
    )

    private val byType = all.associateBy { it.type }

    operator fun get(type: String): BlockSpec? = byType[type]

    fun byCategory(category: Category) = all.filter { it.category == category }

    fun search(query: String): List<BlockSpec> {
        val q = query.trim()
        if (q.isEmpty()) return all
        val words = q.lowercase().split(' ').filter { it.isNotBlank() }
        return all
            .map { spec ->
                val hay = "${spec.title} ${spec.description} ${spec.category.title} ${spec.type}".lowercase()
                var score = 0
                for (w in words) {
                    score += when {
                        spec.title.lowercase().startsWith(w) -> 3
                        spec.title.lowercase().contains(w) -> 2
                        hay.contains(w) -> 1
                        else -> -100
                    }
                }
                spec to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}
