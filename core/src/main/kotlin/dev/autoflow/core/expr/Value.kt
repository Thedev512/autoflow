package dev.autoflow.core.expr

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.floor

/** A runtime value: flows only deal with numbers, text, booleans and null. */
sealed class Value {
    data class Num(val v: Double) : Value()
    data class Str(val v: String) : Value()
    data class Bool(val v: Boolean) : Value()
    data object Null : Value()

    fun asText(): String = when (this) {
        is Num -> formatNumber(v)
        is Str -> v
        is Bool -> v.toString()
        Null -> ""
    }

    /** Numbers stay numbers; numeric text is parsed; anything else becomes NaN. */
    fun asNumber(): Double = when (this) {
        is Num -> v
        is Str -> v.trim().toDoubleOrNull() ?: Double.NaN
        is Bool -> if (v) 1.0 else 0.0
        Null -> 0.0
    }

    fun isTruthy(): Boolean = when (this) {
        is Num -> v != 0.0 && !v.isNaN()
        is Str -> v.isNotEmpty() && v != "false" && v != "0"
        is Bool -> v
        Null -> false
    }

    fun toJson(): JsonPrimitive = when (this) {
        is Num -> JsonPrimitive(v)
        is Str -> JsonPrimitive(v)
        is Bool -> JsonPrimitive(v)
        Null -> JsonNull
    }

    companion object {
        val TRUE = Bool(true)
        val FALSE = Bool(false)
        val EMPTY = Str("")

        fun of(b: Boolean): Value = if (b) TRUE else FALSE
        fun of(d: Double): Value = Num(d)
        fun of(s: String): Value = Str(s)

        fun fromJson(p: JsonPrimitive): Value = when {
            p is JsonNull -> Null
            p.isString -> Str(p.content)
            p.booleanOrNull != null -> Bool(p.booleanOrNull!!)
            p.doubleOrNull != null -> Num(p.doubleOrNull!!)
            else -> Str(p.content)
        }

        fun formatNumber(d: Double): String = when {
            d.isNaN() -> "NaN"
            d.isInfinite() -> if (d > 0) "Infinity" else "-Infinity"
            d == floor(d) && abs(d) < 1e15 -> d.toLong().toString()
            else -> {
                // Trim float noise like 0.1 + 0.2 = 0.30000000000000004
                val s = "%.10f".format(java.util.Locale.ROOT, d).trimEnd('0').trimEnd('.')
                if (s == "-0") "0" else s
            }
        }
    }
}
