package dev.autoflow.ui.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.autoflow.core.blocks.BlockCatalog
import dev.autoflow.core.blocks.BlockSpec
import dev.autoflow.core.engine.Issue
import dev.autoflow.core.engine.Validator
import dev.autoflow.core.model.Block
import dev.autoflow.core.model.FlowDoc
import dev.autoflow.core.model.History
import dev.autoflow.core.model.Snippet
import dev.autoflow.core.model.addBlock
import dev.autoflow.core.model.addComment
import dev.autoflow.core.model.bounds
import dev.autoflow.core.model.connect
import dev.autoflow.core.model.copyItems
import dev.autoflow.core.model.disconnect
import dev.autoflow.core.model.editComment
import dev.autoflow.core.model.moveItems
import dev.autoflow.core.model.paste
import dev.autoflow.core.model.removeItems
import dev.autoflow.core.model.setLabel
import dev.autoflow.core.model.setParam

/** Canvas geometry, in canvas units (dp at 100% zoom). */
object Geo {
    const val BLOCK_W = 200f
    const val BLOCK_H = 84f
    /** Room above and below a block's card for its port dots and labels. */
    const val PAD_TOP = 12f
    const val PAD_BOTTOM = 26f
    const val COMMENT_H = 96f
    const val MIN_SCALE = 0.25f
    const val MAX_SCALE = 2.5f

    fun inputPort(b: Block) = Offset(b.x + BLOCK_W / 2, b.y)

    fun outputPort(b: Block, index: Int, count: Int) =
        Offset(b.x + BLOCK_W * (index + 1) / (count + 1), b.y + BLOCK_H)

    fun contains(b: Block, p: Offset) =
        p.x >= b.x && p.x <= b.x + BLOCK_W && p.y >= b.y - PAD_TOP && p.y <= b.y + BLOCK_H + PAD_BOTTOM
}

/** An arrow being dragged out of an output port. */
data class DragLink(val from: String, val port: String, val start: Offset, val end: Offset)

/**
 * Everything the flow editor shows and edits. Edits go through [edit], which records undo history;
 * gestures (dragging blocks) preview changes live and record one undo step when they end.
 */
@Stable
class EditorState(initial: FlowDoc) {
    var doc by mutableStateOf(initial)
        private set

    private val history = History<FlowDoc>()
    private var historyVersion by mutableIntStateOf(0)
    val canUndo: Boolean get() = historyVersion >= 0 && history.canUndo
    val canRedo: Boolean get() = historyVersion >= 0 && history.canRedo

    /** Bumped whenever [doc] changes because of the user, for autosave. */
    var editCount by mutableIntStateOf(0)
        private set

    var scale by mutableFloatStateOf(1f)
    /** Screen position (px) of the canvas origin. */
    var pan by mutableStateOf(Offset(48f, 48f))
    var viewport by mutableStateOf(IntSize.Zero)
    var density = 1f

    var selection by mutableStateOf(emptySet<String>())
    var multiSelect by mutableStateOf(false)
    var clipboard by mutableStateOf<Snippet?>(null)
    var dragLink by mutableStateOf<DragLink?>(null)
        private set
    var dropTarget by mutableStateOf<String?>(null)
        private set

    var issues by mutableStateOf(Validator.check(initial))
        private set

    val selectedBlock: Block? get() = selection.singleOrNull()?.let { doc.block(it) }
    val selectedComment get() = selection.singleOrNull()?.let { id -> doc.comments.firstOrNull { it.id == id } }

    fun spec(b: Block): BlockSpec? = BlockCatalog[b.type]

    // ---------- edits ----------

    fun edit(coalesceKey: String? = null, change: (FlowDoc) -> FlowDoc) {
        val before = doc
        val after = change(before)
        if (after == before) return
        history.record(before, coalesceKey)
        applyDoc(after)
    }

    private fun applyDoc(d: FlowDoc) {
        doc = d
        historyVersion++
        editCount++
        issues = Validator.check(d)
        selection = selection.filter { id -> d.block(id) != null || d.comments.any { it.id == id } }.toSet()
    }

    fun undo() {
        history.undo(doc)?.let { applyDoc(it) }
    }

    fun redo() {
        history.redo(doc)?.let { applyDoc(it) }
    }

    private var gestureStart: FlowDoc? = null

    fun beginGesture() {
        history.seal()
        gestureStart = doc
    }

    fun endGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        if (start != doc) {
            history.record(start)
            historyVersion++
            editCount++
            issues = Validator.check(doc)
        }
    }

    /** Live move during a drag; recorded as one undo step by [endGesture]. */
    fun dragItems(ids: Set<String>, dx: Float, dy: Float) {
        doc = doc.moveItems(ids, dx, dy)
    }

    fun rename(name: String) = edit("rename") { it.copy(name = name) }

    /**
     * Adds a block at [at] (canvas units), or in the middle of the view. When added by tapping
     * the palette while one block with a single free output is selected, it goes right below
     * that block and gets wired to it, so tapping blocks in order builds a chain.
     */
    fun addBlock(blockSpec: BlockSpec, at: Offset? = null): String {
        val prev = selectedBlock
        val prevPort = prev?.let { spec(it) }?.outputs?.singleOrNull()
        val autoWire = at == null && blockSpec.hasInput && prev != null && prevPort != null &&
            doc.linkFrom(prev.id, prevPort.id) == null
        val pos = if (autoWire) Offset(prev!!.x, prev.y + 130f)
        else (at ?: viewCenter()).let { Offset(snap(it.x - Geo.BLOCK_W / 2), snap(it.y - Geo.BLOCK_H / 2)) }
        var id = ""
        edit {
            val (d, newId) = it.addBlock(blockSpec.type, pos.x, pos.y)
            id = newId
            if (autoWire) d.connect(prev!!.id, prevPort!!.id, newId) else d
        }
        selection = setOf(id)
        return id
    }

    fun addComment() {
        val c = viewCenter()
        var id = ""
        edit { val (d, newId) = it.addComment(snap(c.x - 110f), snap(c.y - 40f), "Note"); id = newId; d }
        selection = setOf(id)
    }

    fun deleteSelection() {
        if (selection.isEmpty()) return
        val ids = selection
        edit { it.removeItems(ids) }
        selection = emptySet()
    }

    fun copySelection(): Snippet? {
        if (selection.isEmpty()) return null
        return doc.copyItems(selection).takeUnless { it.isEmpty }?.also { clipboard = it }
    }

    fun cutSelection() {
        if (copySelection() != null) deleteSelection()
    }

    fun paste(snippet: Snippet? = clipboard) {
        if (snippet == null || snippet.isEmpty) return
        val left = (snippet.blocks.map { it.x } + snippet.comments.map { it.x }).min()
        val top = (snippet.blocks.map { it.y } + snippet.comments.map { it.y }).min()
        val c = viewCenter()
        var ids = emptySet<String>()
        edit {
            val (d, newIds) = it.paste(snippet, snap(c.x - Geo.BLOCK_W / 2 - left), snap(c.y - Geo.BLOCK_H / 2 - top))
            ids = newIds
            d
        }
        selection = ids
    }

    fun duplicateSelection() {
        val s = doc.copyItems(selection).takeUnless { it.isEmpty } ?: return
        var ids = emptySet<String>()
        edit { val (d, newIds) = it.paste(s, 40f, 40f); ids = newIds; d }
        selection = ids
    }

    fun setParam(blockId: String, key: String, value: String) =
        edit("param:$blockId:$key") { it.setParam(blockId, key, value) }

    fun setLabel(blockId: String, label: String) = edit("label:$blockId") { it.setLabel(blockId, label) }

    fun setComment(id: String, text: String) = edit("comment:$id") { it.editComment(id, text) }

    fun disconnect(blockId: String, port: String) = edit { it.disconnect(blockId, port) }

    // ---------- selection ----------

    fun tapItem(id: String) {
        selection = if (multiSelect) {
            if (id in selection) selection - id else selection + id
        } else {
            setOf(id)
        }
    }

    fun longPressItem(id: String) {
        multiSelect = true
        selection = selection + id
    }

    fun clearSelection() {
        selection = emptySet()
    }

    fun selectAll() {
        selection = doc.blocks.map { it.id }.toSet() + doc.comments.map { it.id }
    }

    // ---------- arrows ----------

    fun startLink(from: String, port: String, start: Offset) {
        dragLink = DragLink(from, port, start, start)
        dropTarget = null
    }

    fun moveLink(dx: Float, dy: Float) {
        val l = dragLink ?: return
        val end = l.end + Offset(dx, dy)
        dragLink = l.copy(end = end)
        dropTarget = doc.blocks.lastOrNull { Geo.contains(it, end) && spec(it)?.hasInput != false }?.id
    }

    fun finishLink() {
        val l = dragLink ?: return
        val target = dropTarget
        dragLink = null
        dropTarget = null
        if (target != null) edit { it.connect(l.from, l.port, target) }
    }

    fun cancelLink() {
        dragLink = null
        dropTarget = null
    }

    // ---------- view ----------

    fun toScreen(world: Offset) = pan + world * (density * scale)
    fun toWorld(screen: Offset) = (screen - pan) / (density * scale)

    fun viewCenter(): Offset =
        if (viewport == IntSize.Zero) Offset(200f, 200f)
        else toWorld(Offset(viewport.width / 2f, viewport.height / 2f))

    /** Pinch/pan: [centroid] stays under the fingers while zooming. */
    fun transform(centroid: Offset, panBy: Offset, zoom: Float) {
        val newScale = (scale * zoom).coerceIn(Geo.MIN_SCALE, Geo.MAX_SCALE)
        val f = newScale / scale
        pan = centroid - (centroid - pan) * f + panBy
        scale = newScale
    }

    fun zoomBy(factor: Float) {
        transform(Offset(viewport.width / 2f, viewport.height / 2f), Offset.Zero, factor)
    }

    fun fitToView() {
        val b = doc.bounds(Geo.BLOCK_W, Geo.BLOCK_H + Geo.PAD_BOTTOM, Geo.COMMENT_H) ?: return
        if (viewport == IntSize.Zero) return
        val margin = 48f * density
        val sx = (viewport.width - 2 * margin) / (b.width * density)
        val sy = (viewport.height - 2 * margin) / (b.height * density)
        scale = minOf(sx, sy, 1.25f).coerceIn(Geo.MIN_SCALE, Geo.MAX_SCALE)
        val contentW = b.width * density * scale
        val contentH = b.height * density * scale
        pan = Offset(
            (viewport.width - contentW) / 2f - b.left * density * scale,
            (viewport.height - contentH) / 2f - b.top * density * scale,
        )
    }

    fun issuesFor(blockId: String): List<Issue> = issues.filter { it.blockId == blockId }

    private fun snap(v: Float) = (Math.round(v / 10f) * 10).toFloat()
}
