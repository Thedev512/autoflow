package dev.autoflow.core.templates

import dev.autoflow.core.model.Block
import dev.autoflow.core.model.Comment
import dev.autoflow.core.model.FlowDoc
import dev.autoflow.core.model.Ids
import dev.autoflow.core.model.Link

data class Template(val title: String, val description: String, val icon: String, val build: () -> FlowDoc)

/** Small helper for laying out template flows on a grid. */
class FlowBuilder(private val name: String, private val description: String) {
    private val blocks = mutableListOf<Block>()
    private val links = mutableListOf<Link>()
    private val comments = mutableListOf<Comment>()

    fun block(type: String, col: Int, row: Int, vararg params: Pair<String, String>): String {
        val id = Ids.next()
        blocks += Block(id, type, col * COL + 40f, row * ROW + 40f, params.toMap())
        return id
    }

    fun link(from: String, to: String, port: String = "next") {
        links += Link(from, port, to)
    }

    fun chain(vararg ids: String) = ids.toList().zipWithNext().forEach { (a, b) -> link(a, b) }

    fun note(col: Float, row: Float, text: String) {
        comments += Comment(Ids.next(), col * COL + 40f, row * ROW + 40f, text)
    }

    fun build() = FlowDoc(Ids.next(), name, description, blocks.toList(), links.toList(), comments.toList())

    companion object {
        const val COL = 260f
        const val ROW = 130f
    }
}

object Templates {
    val all: List<Template> = listOf(
        Template("Low battery alert", "Notifies you when the battery drops below 20%, once per charge.", "battery") {
            FlowBuilder("Low battery alert", "Warns once when the battery gets low, then waits for the charger.").run {
                val s = block("flow.start", 0, 0)
                val low = block("trigger.battery", 0, 1, "when" to "below", "level" to "20")
                val n = block("action.notify", 0, 2, "title" to "Battery low", "text" to "Only {battery}% left, plug me in")
                val plug = block("trigger.charging", 0, 3, "state" to "plugged")
                chain(s, low, n, plug)
                link(plug, low)
                note(1f, 1f, "Loops forever: after you plug in, it waits for the next low battery.")
                build()
            }
        },
        Template("Charging announcer", "Says out loud when the charger is connected or removed.", "power") {
            FlowBuilder("Charging announcer", "").run {
                val s = block("flow.start", 0, 0)
                val on = block("trigger.charging", 0, 1, "state" to "plugged")
                val say1 = block("action.speak", 0, 2, "text" to "Charging")
                val off = block("trigger.charging", 0, 3, "state" to "unplugged")
                val say2 = block("action.speak", 0, 4, "text" to "Charger removed")
                chain(s, on, say1, off, say2)
                link(say2, on)
                build()
            }
        },
        Template("Headphones → music", "Opens your music app and sets volume when headphones connect.", "headphones") {
            FlowBuilder("Headphones music", "").run {
                val s = block("flow.start", 0, 0)
                val hp = block("trigger.headphones", 0, 1, "event" to "connected")
                val vol = block("action.volume", 0, 2, "stream" to "media", "level" to "40")
                val open = block("action.open", 0, 3, "target" to "app", "package" to "com.spotify.music", "packageName" to "Spotify")
                val off = block("trigger.headphones", 0, 4, "event" to "disconnected")
                chain(s, hp, vol, open, off)
                link(off, hp)
                note(1f, 3f, "Tap the Open app block to pick your own music app.")
                build()
            }
        },
        Template("Good morning", "Every weekday at 07:30, fetches a quote from the web and shows it.", "schedule") {
            FlowBuilder("Good morning", "").run {
                val s = block("flow.start", 0, 0)
                val t = block("trigger.time", 0, 1, "mode" to "daily", "time" to "07:30", "days" to "12345")
                val h = block("action.http", 0, 2, "url" to "https://api.github.com/zen")
                val n = block("action.notify", 0, 3, "title" to "Good morning", "text" to "{response}")
                val f = block("action.notify", 1, 3, "title" to "Good morning", "text" to "No internet, have a good day anyway")
                chain(s, t, h)
                link(h, n, "ok")
                link(h, f, "fail")
                link(n, t)
                link(f, t)
                build()
            }
        },
        Template("Bedtime", "At 22:30 dims the screen and turns the media volume down.", "brightness") {
            FlowBuilder("Bedtime", "").run {
                val s = block("flow.start", 0, 0)
                val t = block("trigger.time", 0, 1, "mode" to "daily", "time" to "22:30", "days" to "1234567")
                val b = block("action.brightness", 0, 2, "mode" to "level", "level" to "15")
                val v = block("action.volume", 0, 3, "stream" to "media", "level" to "20")
                val n = block("action.toast", 0, 4, "text" to "Bedtime mode on")
                chain(s, t, b, v, n)
                link(n, t)
                build()
            }
        },
        Template("Ask my name", "Shows a dialog, then greets you with what you typed.", "chat") {
            FlowBuilder("Ask my name", "").run {
                val s = block("flow.start", 0, 0)
                val d = block("logic.dialog", 0, 1, "kind" to "input", "title" to "Hey", "message" to "What's your name?", "result" to "answer")
                val ok = block("action.toast", 0, 2, "text" to "Nice to meet you, {answer}")
                val no = block("action.toast", 1, 2, "text" to "Fine, keep your secrets")
                chain(s, d)
                link(d, ok, "ok")
                link(d, no, "cancel")
                build()
            }
        },
        Template("Dice roller", "Rolls five dice in a loop and logs each roll. Shows loops and variables.", "casino") {
            FlowBuilder("Dice roller", "").run {
                val s = block("flow.start", 0, 0)
                val total = block("logic.set", 0, 1, "name" to "total", "value" to "0")
                val loop = block("logic.loop", 0, 2, "count" to "5", "counter" to "i")
                val r = block("logic.random", 1, 3, "mode" to "int", "min" to "1", "max" to "6", "result" to "dice")
                val add = block("logic.math", 1, 4, "op" to "add", "a" to "total", "b" to "dice", "result" to "total")
                val l = block("logic.log", 1, 5, "message" to "Roll {i + 1}: {dice}")
                val n = block("action.notify", 0, 3, "title" to "Dice", "text" to "You rolled {total} in total")
                chain(s, total, loop)
                link(loop, r, "loop")
                chain(r, add, l)
                link(l, loop)
                link(loop, n, "done")
                build()
            }
        },
        Template("Notification logger", "Saves every notification you get to a text file.", "notification") {
            FlowBuilder("Notification logger", "").run {
                val s = block("flow.start", 0, 0)
                val n = block("trigger.notification", 0, 1)
                val w = block(
                    "action.file.write", 0, 2,
                    "name" to "notifications.txt",
                    "text" to "{date()} {time()} [{notif_app}] {notif_title}: {notif_text}\n",
                    "append" to "true",
                )
                chain(s, n, w)
                link(w, n, "ok")
                link(w, n, "fail")
                build()
            }
        },
        Template("Unlock counter", "Counts how many times you unlock your tablet today.", "screen") {
            FlowBuilder("Unlock counter", "").run {
                val s = block("flow.start", 0, 0)
                val zero = block("logic.set", 0, 1, "name" to "count", "value" to "0")
                val u = block("trigger.screen", 0, 2, "event" to "unlocked")
                val add = block("logic.math", 0, 3, "op" to "add", "a" to "count", "b" to "1", "result" to "count")
                val iff = block("logic.if", 0, 4, "condition" to "count % 10 == 0")
                val t = block("action.toast", 1, 5, "text" to "That's {count} unlocks. Touch grass maybe")
                chain(s, zero, u, add, iff)
                link(iff, t, "yes")
                link(iff, u, "no")
                link(t, u)
                build()
            }
        },
        Template("Wi-Fi watcher", "Tells you when Wi-Fi drops and when it comes back.", "wifi") {
            FlowBuilder("Wi-Fi watcher", "").run {
                val s = block("flow.start", 0, 0)
                val lost = block("trigger.wifi", 0, 1, "event" to "disconnected")
                val n = block("action.notify", 0, 2, "title" to "Wi-Fi dropped", "text" to "You're on mobile data or offline now")
                val back = block("trigger.wifi", 0, 3, "event" to "connected")
                val v = block("action.vibrate", 0, 4, "pattern" to "double")
                chain(s, lost, n, back, v)
                link(v, lost)
                build()
            }
        },
    )

    fun blank(name: String = "New flow") = FlowDoc(
        Ids.next(), name,
        blocks = listOf(Block(Ids.next(), "flow.start", 40f, 40f)),
    )
}
