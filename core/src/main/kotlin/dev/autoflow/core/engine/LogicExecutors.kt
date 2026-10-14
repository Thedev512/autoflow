package dev.autoflow.core.engine

import dev.autoflow.core.expr.Value
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.pow
import kotlin.math.roundToLong

/** Executors for blocks that need nothing from Android: flow control, variables, math, text, time. */
object LogicExecutors {

    private fun ex(f: Executor): Executor = f

    val all: Map<String, Executor> = mapOf(
        "flow.start" to ex { _ -> "next" },
        "flow.stop" to ex { _ -> null },
        "logic.if" to ex { c -> if (c.eval("condition").isTruthy()) "yes" else "no" },
        "logic.wait" to ex { c ->
            val s = c.number("seconds")
            if (s < 0) throw BlockException("Seconds can't be negative")
            c.sleep((s * 1000).roundToLong())
            "next"
        },
        "logic.loop" to ex { loop(it) },
        "logic.set" to ex { c ->
            val name = c.variableName("name") ?: throw BlockException("Give the variable a name")
            c.setVar(name, c.eval("value"))
            "next"
        },
        "logic.math" to ex { math(it) },
        "logic.text" to ex { text(it) },
        "logic.random" to ex { random(it) },
        "logic.log" to ex { c -> c.log(c.text("message")); "next" },
        "trigger.time" to ex { time(it) },
    )

    private fun loop(c: RunContext): String {
        val stateKey = "__loop_${c.block.id}"
        val count = c.number("count").toLong()
        val i = c.getVar(stateKey).let { if (it is Value.Num) it.v.toLong() else 0L }
        return if (i < count) {
            c.variableName("counter")?.let { c.setVar(it, Value.Num(i.toDouble())) }
            c.setVar(stateKey, Value.Num((i + 1).toDouble()))
            "loop"
        } else {
            c.removeVar(stateKey)
            "done"
        }
    }

    private fun math(c: RunContext): String {
        val op = c.raw("op")
        val a = c.number("a")
        val b = if (op in setOf("round", "abs", "sqrt")) 0.0 else c.number("b")
        val r = when (op) {
            "add" -> a + b
            "subtract" -> a - b
            "multiply" -> a * b
            "divide" -> if (b == 0.0) throw BlockException("Can't divide by zero") else a / b
            "modulo" -> if (b == 0.0) throw BlockException("Can't divide by zero") else a % b
            "power" -> a.pow(b)
            "min" -> minOf(a, b)
            "max" -> maxOf(a, b)
            "round" -> kotlin.math.round(a)
            "abs" -> kotlin.math.abs(a)
            "sqrt" -> if (a < 0) throw BlockException("Can't take the square root of a negative number") else kotlin.math.sqrt(a)
            else -> throw BlockException("Unknown operation $op")
        }
        c.variableName("result")?.let { c.setVar(it, Value.Num(r)) }
        return "next"
    }

    private fun text(c: RunContext): String {
        val s = c.text("text")
        val a1 = c.text("arg1")
        val a2 = c.text("arg2")
        fun intArg(v: String, what: String) = v.trim().toDoubleOrNull()?.toInt()
            ?: throw BlockException("$what should be a whole number, got “$v”")
        val r: Value = when (val op = c.raw("op")) {
            "join" -> Value.Str(s + a1)
            "upper" -> Value.Str(s.uppercase())
            "lower" -> Value.Str(s.lowercase())
            "length" -> Value.Num(s.length.toDouble())
            "trim" -> Value.Str(s.trim())
            "replace" -> Value.Str(if (a1.isEmpty()) s else s.replace(a1, a2))
            "substring" -> {
                val start = intArg(a1, "Start").coerceIn(0, s.length)
                val end = if (a2.isBlank()) s.length else (start + intArg(a2, "Length")).coerceIn(start, s.length)
                Value.Str(s.substring(start, end))
            }
            "split" -> {
                val parts = if (a1.isEmpty()) s.map { it.toString() } else s.split(a1)
                val idx = if (a2.isBlank()) 0 else intArg(a2, "Piece")
                Value.Str(parts.getOrNull(if (idx < 0) parts.size + idx else idx)?.trim() ?: "")
            }
            "contains" -> Value.of(s.contains(a1, ignoreCase = true))
            else -> throw BlockException("Unknown operation $op")
        }
        c.variableName("result")?.let { c.setVar(it, r) }
        return "next"
    }

    private fun random(c: RunContext): String {
        val rnd = c.env.random
        val r: Value = when (c.raw("mode")) {
            "pick" -> {
                val items = c.text("list").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (items.isEmpty()) throw BlockException("The list is empty")
                Value.Str(items[rnd.nextInt(items.size)])
            }
            "decimal" -> {
                val lo = c.number("min"); val hi = c.number("max")
                Value.Num(lo + rnd.nextDouble() * (hi - lo))
            }
            else -> {
                val lo = c.number("min").toLong(); val hi = c.number("max").toLong()
                if (hi < lo) throw BlockException("“To” is smaller than “From”")
                Value.Num(rnd.nextLong(lo, hi + 1).toDouble())
            }
        }
        c.variableName("result")?.let { c.setVar(it, r) }
        return "next"
    }

    private suspend fun time(c: RunContext): String {
        val now = c.host.clock.now()
        val next = if (c.raw("mode") == "interval") {
            val minutes = c.number("minutes")
            if (minutes < 1) throw BlockException("The interval must be at least 1 minute")
            now + (minutes * 60_000).roundToLong()
        } else {
            nextDailyTime(now, c.raw("time"), c.raw("days"), c.host.clock.zone)
        }
        c.waitUntil(next)
        return "next"
    }

    /** The next moment strictly after [now] that is at [hhmm] on one of [days] (1 = Monday). */
    fun nextDailyTime(now: Long, hhmm: String, days: String, zone: ZoneId): Long {
        val t = parseTime(hhmm) ?: throw BlockException("Time should look like 07:30, got “$hhmm”")
        val allowed = days.mapNotNull { it.digitToIntOrNull() }.filter { it in 1..7 }.map { DayOfWeek.of(it) }.toSet()
            .ifEmpty { DayOfWeek.entries.toSet() }
        val start = Instant.ofEpochMilli(now).atZone(zone)
        for (d in 0..7) {
            val date = start.toLocalDate().plusDays(d.toLong())
            if (date.dayOfWeek !in allowed) continue
            val candidate = ZonedDateTime.of(date, t, zone).toInstant().toEpochMilli()
            if (candidate > now) return candidate
        }
        throw BlockException("Couldn't find the next time") // unreachable: 8 days always contain a match
    }

    fun parseTime(s: String): LocalTime? {
        val m = Regex("""^\s*(\d{1,2})[:.h](\d{2})\s*$""").find(s) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) return null
        return LocalTime.of(h, min)
    }
}
