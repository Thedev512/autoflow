package dev.autoflow.core

import dev.autoflow.core.expr.EvalEnv
import dev.autoflow.core.expr.Expr
import dev.autoflow.core.expr.ExprException
import dev.autoflow.core.expr.Template
import dev.autoflow.core.expr.Value
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExprTest {
    private val vars = mutableMapOf<String, Value>(
        "battery" to Value.Num(15.0),
        "charging" to Value.FALSE,
        "name" to Value.Str("Ryan"),
        "typed" to Value.Str("5"),
    )
    private val fixedNow = ZonedDateTime.of(2026, 10, 13, 14, 5, 0, 0, ZoneId.of("Africa/Casablanca")).toInstant().toEpochMilli()
    private val env = EvalEnv({ vars[it] }, { fixedNow }, ZoneId.of("Africa/Casablanca"), Random(42))

    private fun ev(s: String) = Expr.evaluate(s, env)
    private fun text(s: String) = ev(s).asText()

    @Test fun arithmeticAndPrecedence() {
        assertEquals("7", text("1 + 2 * 3"))
        assertEquals("9", text("(1 + 2) * 3"))
        assertEquals("512", text("2 ^ 3 ^ 2"))
        assertEquals("-4", text("-2 * 2"))
        assertEquals("1", text("10 % 3"))
        assertEquals("0.3", text("0.1 + 0.2"))
        assertEquals("2.5", text("5 / 2"))
    }

    @Test fun conditions() {
        assertTrue(ev("battery < 20 && !charging").isTruthy())
        assertTrue(ev("battery < 20 and not charging").isTruthy())
        assertEquals(false, ev("battery >= 20 || charging").isTruthy())
        assertTrue(ev("name == \"Ryan\"").isTruthy())
        assertTrue(ev("name = 'Ryan'").isTruthy(), "single = works as comparison for non-coders")
        assertTrue(ev("typed == 5").isTruthy(), "numeric text equals number")
        assertTrue(ev("battery != 16").isTruthy())
        assertEquals("low", text("battery < 20 ? \"low\" : \"ok\""))
    }

    @Test fun textConcatenationVersusAddition() {
        assertEquals("Battery: 15%", text("\"Battery: \" + battery + \"%\""))
        assertEquals("8", text("typed + 3"), "numeric text from a dialog adds as a number")
        assertEquals("Ryan!", text("name + \"!\""))
    }

    @Test fun functions() {
        assertEquals("3.14", text("round(3.14159, 2)"))
        assertEquals("RYAN", text("upper(name)"))
        assertEquals("4", text("len(name)"))
        assertEquals("true", text("contains(name, \"ya\")"))
        assertEquals("Ry", text("substr(name, 0, 2)"))
        assertEquals("14:05", text("time()"))
        assertEquals("2026-10-13", text("date()"))
        assertEquals("2", text("weekday()"))
        assertEquals("9", text("max(1, 9, 3)"))
        val r = ev("randInt(1, 6)").asNumber()
        assertTrue(r in 1.0..6.0)
    }

    @Test fun unknownVariableIsEmptyNotACrash() {
        assertEquals("", text("missing"))
        assertTrue(ev("missing == null").isTruthy())
    }

    @Test fun errorsAreReadable() {
        assertFailsWith<ExprException> { ev("1 +") }
        assertFailsWith<ExprException> { ev("nope(1)") }
        assertFailsWith<ExprException> { ev("\"open") }
        assertFailsWith<ExprException> { ev("(1 + 2") }
        assertFailsWith<ExprException> { ev("1 2") }
    }

    @Test fun templates() {
        assertEquals("Only 15% left, Ryan", Template.render("Only {battery}% left, {name}", env))
        assertEquals("Next: 16", Template.render("Next: {battery + 1}", env))
        assertEquals("{literal}", Template.render("\\{literal\\}", env))
        assertEquals("a } b", Template.render("{\"a } b\"}", env))
        assertEquals("no close {here", Template.render("no close {here", env))
    }
}
