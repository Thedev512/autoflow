package dev.autoflow.core

import dev.autoflow.core.blocks.BlockCatalog
import dev.autoflow.core.blocks.BlockSpec
import dev.autoflow.core.blocks.Category
import dev.autoflow.core.blocks.Ports
import dev.autoflow.core.engine.BlockException
import dev.autoflow.core.engine.Clock
import dev.autoflow.core.engine.Executor
import dev.autoflow.core.engine.FlowEngine
import dev.autoflow.core.engine.Host
import dev.autoflow.core.engine.LogEntry
import dev.autoflow.core.engine.LogLevel
import dev.autoflow.core.engine.LogicExecutors
import dev.autoflow.core.engine.RunPhase
import dev.autoflow.core.model.FlowDoc
import dev.autoflow.core.templates.FlowBuilder
import dev.autoflow.core.templates.Templates
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EngineTest {
    private val base = 1_800_000_000_000L
    private val logs: MutableList<LogEntry> = Collections.synchronizedList(mutableListOf())
    private val records: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val neverFires = CompletableDeferred<Unit>()

    private val testSpecs = mapOf(
        "test.record" to BlockSpec("test.record", Category.ACTION, "Record", "", "log"),
        "test.event" to BlockSpec("test.event", Category.TRIGGER, "Some event", "", "notification", waits = true),
        "test.flaky" to BlockSpec("test.flaky", Category.ACTION, "Flaky", "", "http", outputs = listOf(Ports.OK, Ports.FAIL)),
    )

    private val fakeAndroid: Map<String, Executor> = mapOf(
        "test.record" to { c -> records += c.text("text"); "next" },
        "action.notify" to { c -> records += "notify:" + c.text("text"); "next" },
        "test.event" to { _ -> neverFires.await(); "next" },
        "test.flaky" to { _ -> throw BlockException("Server said no") },
    )

    private fun TestScope.engine(scope: CoroutineScope = backgroundScope) = FlowEngine(
        executors = LogicExecutors.all + fakeAndroid,
        host = TestHost(testScheduler),
        logSink = { logs += it },
        scope = scope,
        specs = { BlockCatalog[it] ?: testSpecs[it] },
    )

    private inner class TestHost(val s: TestCoroutineScheduler) : Host {
        override val clock = object : Clock {
            override fun now() = base + s.currentTime
            override val zone: ZoneId = ZoneId.of("UTC")
        }
    }

    /** Background work isn't driven by advanceUntilIdle, so step virtual time explicitly. */
    private fun TestScope.settle() {
        advanceTimeBy(60_000); runCurrent()
    }

    private fun flow(build: FlowBuilder.() -> Unit): FlowDoc = FlowBuilder("Test", "").apply(build).build()

    @Test fun diceTemplateRunsToTheEnd() = runTest {
        val e = engine()
        val doc = Templates.all.first { it.title == "Dice roller" }.build()
        assertTrue(e.start(doc))
        settle()
        val status = e.statuses.value[doc.id]!!
        assertEquals(RunPhase.FINISHED, status.phase)
        val total = records.single().removePrefix("notify:You rolled ").removeSuffix(" in total").toInt()
        assertTrue(total in 5..30)
        assertEquals(5, logs.count { it.message.startsWith("Roll ") })
    }

    @Test fun ifElseBranches() = runTest {
        val e = engine()
        val doc = flow {
            val s = block("flow.start", 0, 0)
            val set = block("logic.set", 0, 1, "name" to "battery", "value" to "12")
            val iff = block("logic.if", 0, 2, "condition" to "battery < 20")
            val yes = block("test.record", 0, 3, "text" to "low {battery}")
            val no = block("test.record", 1, 3, "text" to "fine")
            chain(s, set, iff); link(iff, yes, "yes"); link(iff, no, "no")
        }
        e.start(doc); settle()
        assertEquals(listOf("low 12"), records)
    }

    @Test fun waitUsesTheClock() = runTest {
        val e = engine()
        val doc = flow {
            chain(block("flow.start", 0, 0), block("logic.wait", 0, 1, "seconds" to "10"), block("test.record", 0, 2, "text" to "done"))
        }
        e.start(doc)
        advanceTimeBy(9_000); runCurrent()
        assertTrue(records.isEmpty())
        assertEquals(RunPhase.WAITING, e.statuses.value[doc.id]!!.phase)
        assertEquals(base + 10_000, e.statuses.value[doc.id]!!.wakeAt)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(listOf("done"), records)
    }

    @Test fun manyFlowsRunAtOnce() = runTest {
        val e = engine()
        val docs = (1..5).map { n ->
            flow { chain(block("flow.start", 0, 0), block("logic.wait", 0, 1, "seconds" to "30"), block("test.record", 0, 2, "text" to "$n")) }
        }
        docs.forEach { assertTrue(e.start(it)) }
        assertEquals(5, e.activeCount())
        advanceTimeBy(31_000); runCurrent()
        assertEquals(setOf("1", "2", "3", "4", "5"), records.toSet(), "all finish after 30 s, not 150 s")
        assertEquals(0, e.activeCount())
    }

    @Test fun twoStartBlocksRunSideBySide() = runTest {
        val e = engine()
        val doc = flow {
            chain(block("flow.start", 0, 0), block("logic.wait", 0, 1, "seconds" to "5"), block("test.record", 0, 2, "text" to "a"))
            chain(block("flow.start", 1, 0), block("logic.wait", 1, 1, "seconds" to "5"), block("test.record", 1, 2, "text" to "b"))
        }
        e.start(doc)
        advanceTimeBy(5_500); runCurrent()
        assertEquals(setOf("a", "b"), records.toSet())
    }

    @Test fun pauseAndResume() = runTest {
        val e = engine()
        val doc = flow {
            chain(block("flow.start", 0, 0), block("logic.wait", 0, 1, "seconds" to "5"), block("test.record", 0, 2, "text" to "after"))
        }
        e.start(doc)
        advanceTimeBy(1_000); runCurrent()
        e.pause(doc.id)
        advanceTimeBy(60_000); runCurrent()
        assertTrue(records.isEmpty(), "paused flows don't move on")
        assertEquals(RunPhase.PAUSED, e.statuses.value[doc.id]!!.phase)
        e.resume(doc.id)
        runCurrent()
        assertEquals(listOf("after"), records)
    }

    @Test fun stopEndsTheRun() = runTest {
        val e = engine()
        val doc = flow { chain(block("flow.start", 0, 0), block("test.event", 0, 1), block("test.record", 0, 2, "text" to "x")) }
        e.start(doc)
        runCurrent()
        assertEquals(RunPhase.WAITING, e.statuses.value[doc.id]!!.phase)
        assertTrue(e.statuses.value[doc.id]!!.detail.contains("Some event"))
        e.stop(doc.id)
        runCurrent()
        assertEquals(RunPhase.STOPPED, e.statuses.value[doc.id]!!.phase)
        assertTrue(!e.isActive(doc.id))
        assertTrue(e.start(doc), "can start again after stopping")
    }

    @Test fun errorsFailTheRunOrFollowTheFailPort() = runTest {
        val e = engine()
        val bad = flow { chain(block("flow.start", 0, 0), block("logic.math", 0, 1, "op" to "divide", "a" to "1", "b" to "0")) }
        e.start(bad); settle()
        val st = e.statuses.value[bad.id]!!
        assertEquals(RunPhase.FAILED, st.phase)
        assertTrue(st.detail.contains("divide by zero"), st.detail)
        assertTrue(logs.any { it.level == LogLevel.ERROR && it.message.contains("divide by zero") })

        val handled = flow {
            val f = block("test.flaky", 0, 1)
            chain(block("flow.start", 0, 0), f)
            link(f, block("test.record", 0, 2, "text" to "recovered: {error}"), "fail")
        }
        e.start(handled); settle()
        assertEquals(listOf("recovered: Server said no"), records)
        assertEquals(RunPhase.FINISHED, e.statuses.value[handled.id]!!.phase)
    }

    @Test fun missingStartBlockIsReported() = runTest {
        val e = engine()
        val doc = flow { block("test.record", 0, 0) }
        assertTrue(!e.start(doc))
        assertEquals(RunPhase.FAILED, e.statuses.value[doc.id]!!.phase)
    }

    @Test fun busyLoopIsThrottledAndStoppable() = runTest {
        val e = engine()
        val doc = flow {
            val s = block("flow.start", 0, 0)
            val inc = block("logic.set", 0, 1, "name" to "x", "value" to "x + 1")
            link(s, inc); link(inc, inc)
        }
        e.start(doc)
        advanceTimeBy(1_000); runCurrent()
        val steps = e.statuses.value[doc.id]!!.steps
        assertTrue(steps in 1_000..100_000, "steps=$steps")
        assertTrue(logs.size < 300, "step logging is capped in busy loops (${logs.size})")
        e.stop(doc.id); runCurrent()
        assertEquals(RunPhase.STOPPED, e.statuses.value[doc.id]!!.phase)
    }

    @Test fun hibernateAndWakeResumesMidWait() = runTest {
        val e1 = engine()
        val doc = flow {
            val s = block("flow.start", 0, 0)
            val set = block("logic.set", 0, 1, "name" to "count", "value" to "1")
            val t = block("trigger.time", 0, 2, "mode" to "interval", "minutes" to "60")
            val add = block("logic.math", 0, 3, "op" to "add", "a" to "count", "b" to "1", "result" to "count")
            val rec = block("test.record", 0, 4, "text" to "count={count}")
            chain(s, set, t, add, rec); link(rec, t)
        }
        e1.start(doc)
        advanceTimeBy(1_000); runCurrent()
        assertNull(e1.hibernate(minSleepMs = 2 * 3_600_000L), "the wait is shorter than the minimum")
        val snaps = assertNotNull(e1.hibernate(minSleepMs = 5 * 60_000L))
        runCurrent()
        val snap = snaps.single()
        assertEquals(base + 3_600_000L, snap.wakeAt)
        assertEquals(RunPhase.SLEEPING, e1.statuses.value[doc.id]!!.phase)
        assertEquals(0, e1.activeCount())

        // Much later, a fresh engine (as after the app process was killed) picks it up.
        advanceTimeBy(3_600_000L); runCurrent()
        val e2 = engine()
        assertTrue(e2.start(doc, snap))
        runCurrent()
        assertEquals(listOf("count=2"), records, "fires once for the missed hour, not again immediately")
        assertEquals(RunPhase.WAITING, e2.statuses.value[doc.id]!!.phase)
        advanceTimeBy(3_600_000L); runCurrent()
        assertEquals(listOf("count=2", "count=3"), records)
        e2.stop(doc.id)
    }

    @Test fun cantHibernateWhileWaitingOnEvents() = runTest {
        val e = engine()
        val timer = flow { chain(block("flow.start", 0, 0), block("logic.wait", 0, 1, "seconds" to "3600")) }
        val event = flow { chain(block("flow.start", 0, 0), block("test.event", 0, 1)) }
        e.start(timer); e.start(event)
        runCurrent()
        assertNull(e.hibernate(60_000))
        e.stopAll(); runCurrent()
    }
}
