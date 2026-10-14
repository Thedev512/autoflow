package dev.autoflow.core

import dev.autoflow.core.blocks.BlockCatalog
import dev.autoflow.core.blocks.Category
import dev.autoflow.core.blocks.Permission
import dev.autoflow.core.engine.Issue
import dev.autoflow.core.engine.LogicExecutors
import dev.autoflow.core.engine.Validator
import dev.autoflow.core.model.FlowJson
import dev.autoflow.core.model.History
import dev.autoflow.core.model.addBlock
import dev.autoflow.core.model.addComment
import dev.autoflow.core.model.connect
import dev.autoflow.core.model.copyItems
import dev.autoflow.core.model.moveItems
import dev.autoflow.core.model.paste
import dev.autoflow.core.model.removeItems
import dev.autoflow.core.model.setParam
import dev.autoflow.core.templates.Templates
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ModelTest {
    @Test fun editsAreImmutableAndLinksFollowBlocks() {
        val d0 = Templates.blank()
        val start = d0.blocks.single().id
        val (d1, a) = d0.addBlock("action.toast", 40f, 200f)
        val (d2, b) = d1.addBlock("action.toast", 300f, 200f)
        val d3 = d2.connect(start, "next", a)
        val d4 = d3.connect(start, "next", b)
        assertEquals(1, d4.links.size, "an output port has one arrow; reconnecting replaces it")
        assertEquals(b, d4.links.single().to)
        val d5 = d4.removeItems(setOf(b))
        assertTrue(d5.links.isEmpty(), "deleting a block removes its arrows")
        assertEquals(1, d0.blocks.size, "original untouched")
        val moved = d3.moveItems(setOf(a), 10f, -5f)
        assertEquals(50f, moved.block(a)!!.x)
        assertEquals(195f, moved.block(a)!!.y)
    }

    @Test fun copyPasteKeepsInternalArrowsWithFreshIds() {
        val doc = Templates.all.first { it.title == "Dice roller" }.build()
        val ids = doc.blocks.map { it.id }.toSet()
        val snippet = doc.copyItems(ids)
        val (pasted, newIds) = doc.paste(snippet, 40f, 40f)
        assertEquals(doc.blocks.size * 2, pasted.blocks.size)
        assertEquals(doc.links.size * 2, pasted.links.size)
        assertTrue(newIds.none { it in ids })
        assertTrue(pasted.links.filter { it.from in newIds }.all { it.to in newIds })
    }

    @Test fun historyUndoRedoAndCoalescing() {
        val h = History<Int>()
        var state = 0
        fun edit(v: Int, key: String? = null) { h.record(state, key); state = v }
        edit(1); edit(2, "typing"); edit(3, "typing"); edit(4, "typing")
        state = h.undo(state)!!
        assertEquals(1, state, "the three typing edits are one undo step")
        state = h.undo(state)!!
        assertEquals(0, state)
        assertEquals(null, h.undo(state))
        state = h.redo(state)!!; state = h.redo(state)!!
        assertEquals(4, state)
        edit(5)
        assertEquals(false, h.canRedo, "a new edit clears redo")
    }

    @Test fun jsonExportImportRoundTrip() {
        val docs = Templates.all.map { it.build() }
        val text = FlowJson.exportFile(docs)
        val back = FlowJson.importFile(text)
        assertEquals(docs.size, back.size)
        docs.zip(back).forEach { (a, b) ->
            assertNotEquals(a.id, b.id, "imports get new ids")
            assertEquals(a.copy(id = b.id), b)
        }
        // A bare flow object also imports
        val single = FlowJson.importFile(FlowJson.encode(docs[0]))
        assertEquals(docs[0].blocks, single.single().blocks)
        assertFailsWith<Exception> { FlowJson.importFile("{\"autoflow\":99,\"flows\":[]}") }
        assertFailsWith<Exception> { FlowJson.importFile("not json") }
    }

    @Test fun commentsAreCopiedAndMoved() {
        val (d, c) = Templates.blank().addComment(0f, 0f, "hello")
        val moved = d.moveItems(setOf(c), 5f, 5f)
        assertEquals(5f, moved.comments.single().x)
        val (p, ids) = moved.paste(moved.copyItems(setOf(c)), 10f, 0f)
        assertEquals(2, p.comments.size)
        assertEquals(1, ids.size)
    }

    @Test fun everyTemplateIsValidAndUsesKnownBlocks() {
        for (t in Templates.all) {
            val doc = t.build()
            val errors = Validator.check(doc).filter { it.severity == Issue.Severity.ERROR }
            assertTrue(errors.isEmpty(), "${t.title}: $errors")
            assertTrue(doc.links.all { doc.block(it.from) != null && doc.block(it.to) != null }, t.title)
            for (l in doc.links) {
                val spec = BlockCatalog[doc.block(l.from)!!.type]!!
                assertTrue(spec.outputs.any { it.id == l.port }, "${t.title}: ${spec.title} has no port ${l.port}")
            }
        }
    }

    @Test fun validatorCatchesMistakes() {
        val (d1, ifId) = Templates.blank().addBlock("logic.if", 0f, 200f, mapOf("condition" to "x <"))
        val d2 = d1.setParam(ifId, "condition", "x <")
        val issues = Validator.check(d2)
        assertTrue(issues.any { it.blockId == ifId && it.severity == Issue.Severity.ERROR })
        assertTrue(issues.any { it.blockId == ifId && it.severity == Issue.Severity.WARNING }, "unreachable warning")
        val noStart = d2.removeItems(setOf(d2.blocks.first { it.type == "flow.start" }.id))
        assertTrue(Validator.check(noStart).any { it.blockId == null })
    }

    @Test fun permissionsOnlyForBlocksPresent() {
        val doc = Templates.all.first { it.title == "Good morning" }.build()
        assertEquals(listOf(Permission.NOTIFICATIONS, Permission.EXACT_ALARM), Validator.permissions(doc))
    }

    @Test fun catalogIsConsistent() {
        assertEquals(BlockCatalog.all.size, BlockCatalog.all.map { it.type }.toSet().size, "unique types")
        assertEquals(9, BlockCatalog.byCategory(Category.TRIGGER).size)
        assertEquals(14, BlockCatalog.byCategory(Category.ACTION).size)
        assertTrue(BlockCatalog.search("batt").first().type == "trigger.battery")
        assertTrue(BlockCatalog.search("speak").any { it.type == "action.speak" })
        assertTrue(BlockCatalog.search("zzzz").isEmpty())
        for (spec in BlockCatalog.all) {
            spec.summaryOf(emptyMap()) // must not throw with defaults
            for (p in spec.params) p.showWhen?.let { (k, _) -> assertTrue(spec.params.any { it.key == k }, "${spec.type}.${p.key}") }
        }
    }

    @Test fun nextDailyTime() {
        val zone = ZoneId.of("Africa/Casablanca")
        fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
        // Tue 13 Oct 2026 14:05
        val now = at(13, 14, 5)
        assertEquals(at(14, 8, 0), LogicExecutors.nextDailyTime(now, "08:00", "1234567", zone))
        assertEquals(at(13, 18, 30), LogicExecutors.nextDailyTime(now, "18:30", "", zone))
        assertEquals(at(17, 9, 0), LogicExecutors.nextDailyTime(now, "9:00", "67", zone), "next Saturday")
        assertEquals(at(20, 14, 5), LogicExecutors.nextDailyTime(now, "14:05", "2", zone), "same minute fires next week")
        assertEquals(null, LogicExecutors.parseTime("25:00"))
    }
}
