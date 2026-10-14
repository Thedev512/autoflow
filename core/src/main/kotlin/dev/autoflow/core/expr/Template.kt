package dev.autoflow.core.expr

/**
 * Text with embedded expressions: `Battery at {battery}%` or `Hi {upper(name)}`.
 * A backslash keeps a brace literal: `\{not code\}`.
 */
object Template {
    fun render(text: String, env: EvalEnv): String {
        if ('{' !in text && '\\' !in text) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && (text[i + 1] == '{' || text[i + 1] == '}') -> {
                    out.append(text[i + 1]); i += 2
                }
                c == '{' -> {
                    val end = findClose(text, i + 1)
                    if (end < 0) {
                        out.append(text, i, text.length); i = text.length
                    } else {
                        val code = text.substring(i + 1, end)
                        out.append(if (code.isBlank()) "" else Expr.evaluate(code, env).asText())
                        i = end + 1
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** Finds the matching '}' while skipping braces inside quoted text. */
    private fun findClose(text: String, from: Int): Int {
        var i = from
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '}') {
                return i
            }
            i++
        }
        return -1
    }
}
