package dev.autoflow.core.expr

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.pow
import kotlin.random.Random

class ExprException(message: String) : Exception(message)

/** What an expression can see while it runs. */
class EvalEnv(
    val lookup: (String) -> Value?,
    val now: () -> Long = { System.currentTimeMillis() },
    val zone: ZoneId = ZoneId.systemDefault(),
    val random: Random = Random.Default,
)

/**
 * A tiny expression language for conditions and values, e.g.
 * `battery < 20 && !charging`, `"Level: " + battery + "%"`, `round(x / 3, 2)`.
 */
sealed class Expr {
    abstract fun eval(env: EvalEnv): Value

    data class Lit(val value: Value) : Expr() {
        override fun eval(env: EvalEnv) = value
    }

    data class Var(val name: String) : Expr() {
        override fun eval(env: EvalEnv) = env.lookup(name) ?: Value.Null
    }

    data class Unary(val op: String, val operand: Expr) : Expr() {
        override fun eval(env: EvalEnv): Value {
            val v = operand.eval(env)
            return when (op) {
                "-" -> Value.Num(-v.asNumber())
                "!" -> Value.of(!v.isTruthy())
                else -> throw ExprException("Unknown operator $op")
            }
        }
    }

    data class Binary(val op: String, val left: Expr, val right: Expr) : Expr() {
        override fun eval(env: EvalEnv): Value {
            if (op == "&&") return Value.of(left.eval(env).isTruthy() && right.eval(env).isTruthy())
            if (op == "||") return Value.of(left.eval(env).isTruthy() || right.eval(env).isTruthy())
            val a = left.eval(env)
            val b = right.eval(env)
            return when (op) {
                "+" -> if (isNumeric(a) && isNumeric(b)) Value.Num(a.asNumber() + b.asNumber())
                else Value.Str(a.asText() + b.asText())
                "-" -> Value.Num(a.asNumber() - b.asNumber())
                "*" -> Value.Num(a.asNumber() * b.asNumber())
                "/" -> Value.Num(a.asNumber() / b.asNumber())
                "%" -> Value.Num(a.asNumber() % b.asNumber())
                "^" -> Value.Num(a.asNumber().pow(b.asNumber()))
                "==" -> Value.of(looseEquals(a, b))
                "!=" -> Value.of(!looseEquals(a, b))
                "<" -> Value.of(compare(a, b) < 0)
                "<=" -> Value.of(compare(a, b) <= 0)
                ">" -> Value.of(compare(a, b) > 0)
                ">=" -> Value.of(compare(a, b) >= 0)
                else -> throw ExprException("Unknown operator $op")
            }
        }
    }

    data class Ternary(val cond: Expr, val yes: Expr, val no: Expr) : Expr() {
        override fun eval(env: EvalEnv) = if (cond.eval(env).isTruthy()) yes.eval(env) else no.eval(env)
    }

    data class Call(val name: String, val args: List<Expr>) : Expr() {
        override fun eval(env: EvalEnv): Value = Functions.call(name, args.map { it.eval(env) }, env)
    }

    companion object {
        private val cache = object : LinkedHashMap<String, Expr>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Expr>?) = size > 256
        }

        /** Parses (with a small cache, since flows re-evaluate the same text in loops). */
        fun parse(source: String): Expr = synchronized(cache) {
            cache.getOrPut(source) { Parser(source).parseAll() }
        }

        fun evaluate(source: String, env: EvalEnv): Value =
            if (source.isBlank()) Value.Null else parse(source).eval(env)

        fun isNumeric(v: Value): Boolean = when (v) {
            is Value.Num, is Value.Bool -> true
            Value.Null -> false
            is Value.Str -> v.v.isNotBlank() && v.v.trim().toDoubleOrNull() != null
        }

        fun looseEquals(a: Value, b: Value): Boolean = when {
            a is Value.Null || b is Value.Null -> a.asText() == b.asText()
            a is Value.Bool || b is Value.Bool -> a.isTruthy() == b.isTruthy() &&
                (a is Value.Bool || a.asText() in setOf("true", "false", "0", "1")) &&
                (b is Value.Bool || b.asText() in setOf("true", "false", "0", "1"))
            isNumeric(a) && isNumeric(b) -> a.asNumber() == b.asNumber()
            else -> a.asText() == b.asText()
        }

        fun compare(a: Value, b: Value): Int =
            if (isNumeric(a) && isNumeric(b)) a.asNumber().compareTo(b.asNumber())
            else a.asText().compareTo(b.asText())
    }
}

private object Functions {
    fun call(name: String, a: List<Value>, env: EvalEnv): Value {
        fun n(i: Int) = a.getOrNull(i)?.asNumber() ?: throw ExprException("$name() needs ${i + 1} argument(s)")
        fun s(i: Int) = a.getOrNull(i)?.asText() ?: throw ExprException("$name() needs ${i + 1} argument(s)")
        fun time(pattern: String) = DateTimeFormatter.ofPattern(pattern)
            .format(Instant.ofEpochMilli(env.now()).atZone(env.zone))
        return when (name.lowercase()) {
            "abs" -> Value.Num(kotlin.math.abs(n(0)))
            "round" -> {
                val digits = if (a.size > 1) n(1).toInt() else 0
                val f = 10.0.pow(digits)
                Value.Num(kotlin.math.round(n(0) * f) / f)
            }
            "floor" -> Value.Num(kotlin.math.floor(n(0)))
            "ceil" -> Value.Num(kotlin.math.ceil(n(0)))
            "sqrt" -> Value.Num(kotlin.math.sqrt(n(0)))
            "pow" -> Value.Num(n(0).pow(n(1)))
            "min" -> Value.Num(a.minOfOrNull { it.asNumber() } ?: throw ExprException("min() needs arguments"))
            "max" -> Value.Num(a.maxOfOrNull { it.asNumber() } ?: throw ExprException("max() needs arguments"))
            "num" -> Value.Num(n(0))
            "str", "text" -> Value.Str(s(0))
            "len", "length" -> Value.Num(s(0).length.toDouble())
            "upper" -> Value.Str(s(0).uppercase())
            "lower" -> Value.Str(s(0).lowercase())
            "trim" -> Value.Str(s(0).trim())
            "contains" -> Value.of(s(0).contains(s(1), ignoreCase = true))
            "startswith" -> Value.of(s(0).startsWith(s(1), ignoreCase = true))
            "endswith" -> Value.of(s(0).endsWith(s(1), ignoreCase = true))
            "indexof" -> Value.Num(s(0).indexOf(s(1)).toDouble())
            "replace" -> Value.Str(s(0).replace(s(1), s(2)))
            "substr" -> {
                val str = s(0)
                val start = n(1).toInt().coerceIn(0, str.length)
                val end = if (a.size > 2) (start + n(2).toInt()).coerceIn(start, str.length) else str.length
                Value.Str(str.substring(start, end))
            }
            "join" -> Value.Str(a.joinToString("") { it.asText() })
            "random" -> if (a.isEmpty()) Value.Num(env.random.nextDouble())
            else Value.Num(n(0) + env.random.nextDouble() * (n(1) - n(0)))
            "randint" -> {
                val lo = n(0).toLong(); val hi = n(1).toLong()
                if (hi < lo) throw ExprException("randint(min, max): max is smaller than min")
                Value.Num(env.random.nextLong(lo, hi + 1).toDouble())
            }
            "if" -> if (a.getOrNull(0)?.isTruthy() == true) a.getOrElse(1) { Value.Null } else a.getOrElse(2) { Value.Null }
            "now" -> Value.Num(env.now().toDouble())
            "time" -> Value.Str(time(if (a.isNotEmpty()) s(0) else "HH:mm"))
            "date" -> Value.Str(time(if (a.isNotEmpty()) s(0) else "yyyy-MM-dd"))
            "hour" -> Value.Num(Instant.ofEpochMilli(env.now()).atZone(env.zone).hour.toDouble())
            "minute" -> Value.Num(Instant.ofEpochMilli(env.now()).atZone(env.zone).minute.toDouble())
            "weekday" -> Value.Num(Instant.ofEpochMilli(env.now()).atZone(env.zone).dayOfWeek.value.toDouble())
            else -> throw ExprException("Unknown function $name()")
        }
    }
}

/** Function names, for hints in the editor. */
val ExpressionFunctions: List<String> get() = listOf(
    "abs", "round", "floor", "ceil", "sqrt", "pow", "min", "max", "num", "str", "len", "upper", "lower",
    "trim", "contains", "startsWith", "endsWith", "indexOf", "replace", "substr", "join", "random",
    "randInt", "if", "now", "time", "date", "hour", "minute", "weekday",
)

private class Parser(private val src: String) {
    private var pos = 0

    fun parseAll(): Expr {
        val e = ternary()
        skipWs()
        if (pos < src.length) throw ExprException("Unexpected '${src[pos]}' at ${pos + 1}")
        return e
    }

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun peekWord(): String? {
        skipWs()
        var p = pos
        while (p < src.length && (src[p].isLetterOrDigit() || src[p] == '_')) p++
        return if (p > pos && !src[pos].isDigit()) src.substring(pos, p) else null
    }

    private fun accept(vararg ops: String): String? {
        skipWs()
        for (op in ops) {
            if (src.startsWith(op, pos)) {
                // don't read "<" from "<=", or "=" from "=="
                val after = src.getOrNull(pos + op.length)
                if (op.length == 1 && after == '=' && op in setOf("<", ">", "!", "=")) continue
                pos += op.length
                return op
            }
        }
        return null
    }

    private fun acceptWord(word: String): Boolean {
        if (peekWord()?.equals(word, ignoreCase = true) == true) {
            pos += word.length
            return true
        }
        return false
    }

    private fun ternary(): Expr {
        val cond = or()
        if (accept("?") != null) {
            val yes = ternary()
            if (accept(":") == null) throw ExprException("Expected ':' in a ? b : c")
            val no = ternary()
            return Expr.Ternary(cond, yes, no)
        }
        return cond
    }

    private fun or(): Expr {
        var left = and()
        while (accept("||") != null || acceptWord("or")) left = Expr.Binary("||", left, and())
        return left
    }

    private fun and(): Expr {
        var left = equality()
        while (accept("&&") != null || acceptWord("and")) left = Expr.Binary("&&", left, equality())
        return left
    }

    private fun equality(): Expr {
        var left = comparison()
        while (true) {
            val op = accept("==", "!=", "=") ?: return left
            left = Expr.Binary(if (op == "=") "==" else op, left, comparison())
        }
    }

    private fun comparison(): Expr {
        var left = additive()
        while (true) {
            val op = accept("<=", ">=", "<", ">") ?: return left
            left = Expr.Binary(op, left, additive())
        }
    }

    private fun additive(): Expr {
        var left = multiplicative()
        while (true) {
            val op = accept("+", "-") ?: return left
            left = Expr.Binary(op, left, multiplicative())
        }
    }

    private fun multiplicative(): Expr {
        var left = unary()
        while (true) {
            val op = accept("*", "/", "%") ?: return left
            left = Expr.Binary(op, left, unary())
        }
    }

    private fun unary(): Expr {
        if (accept("-") != null) return Expr.Unary("-", unary())
        if (accept("!") != null || acceptWord("not")) return Expr.Unary("!", unary())
        return power()
    }

    private fun power(): Expr {
        val base = primary()
        if (accept("^") != null) return Expr.Binary("^", base, unary())
        return base
    }

    private fun primary(): Expr {
        skipWs()
        if (pos >= src.length) throw ExprException("Expression ends too early")
        val c = src[pos]
        if (c == '(') {
            pos++
            val e = ternary()
            if (accept(")") == null) throw ExprException("Missing ')'")
            return e
        }
        if (c == '"' || c == '\'') return Expr.Lit(Value.Str(string(c)))
        if (c.isDigit() || (c == '.' && src.getOrNull(pos + 1)?.isDigit() == true)) return number()
        val word = peekWord() ?: throw ExprException("Unexpected '$c' at ${pos + 1}")
        pos += word.length
        when (word.lowercase()) {
            "true" -> return Expr.Lit(Value.TRUE)
            "false" -> return Expr.Lit(Value.FALSE)
            "null" -> return Expr.Lit(Value.Null)
        }
        if (accept("(") != null) {
            val args = mutableListOf<Expr>()
            if (accept(")") == null) {
                do { args += ternary() } while (accept(",") != null)
                if (accept(")") == null) throw ExprException("Missing ')' after arguments of $word()")
            }
            return Expr.Call(word, args)
        }
        return Expr.Var(word)
    }

    private fun number(): Expr {
        val start = pos
        while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            while (pos < src.length && src[pos].isDigit()) pos++
        }
        val text = src.substring(start, pos)
        return Expr.Lit(Value.Num(text.toDoubleOrNull() ?: throw ExprException("Bad number '$text'")))
    }

    private fun string(quote: Char): String {
        pos++ // opening quote
        val sb = StringBuilder()
        while (pos < src.length && src[pos] != quote) {
            if (src[pos] == '\\' && pos + 1 < src.length) {
                pos++
                sb.append(
                    when (src[pos]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> src[pos]
                    }
                )
            } else {
                sb.append(src[pos])
            }
            pos++
        }
        if (pos >= src.length) throw ExprException("Text is missing its closing $quote")
        pos++
        return sb.toString()
    }
}
