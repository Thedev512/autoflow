package dev.autoflow.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.autoflow.core.blocks.BlockCatalog
import dev.autoflow.core.blocks.Category
import dev.autoflow.core.engine.Issue
import dev.autoflow.ui.theme.BlockColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Where the canvas sits in the window, for dropping blocks dragged from the palette. */
class CanvasAnchor {
    var origin = Offset.Zero
}

/**
 * The infinite canvas: pinch to zoom, drag the background to pan, drag blocks to move them,
 * drag from an output dot onto a block to connect them.
 */
@Composable
fun FlowCanvas(
    state: EditorState,
    activeBlocks: Set<String>,
    anchor: CanvasAnchor,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    state.density = density
    val scheme = MaterialTheme.colorScheme
    val gridColor = scheme.outlineVariant.copy(alpha = 0.55f)
    val categoryColors = Category.entries.associateWith { BlockColors.of(it) }
    val flowColor = BlockColors.of(BlockCatalog["flow.start"])
    val yesColor = BlockColors.port("yes", Color.Unspecified)
    val noColor = BlockColors.port("no", Color.Unspecified)
    val dragColor = scheme.primary

    Box(
        modifier
            .clipToBounds()
            .background(scheme.surfaceContainerLowest)
            .onSizeChanged { state.viewport = it }
            .onGloballyPositioned { anchor.origin = it.positionInRoot() }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ -> state.transform(centroid, pan, zoom) }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { state.clearSelection(); state.multiSelect = false },
                    onDoubleTap = { pos -> state.transform(pos, Offset.Zero, 1.6f) },
                )
            }
    ) {
        val doc = state.doc
        Canvas(Modifier.fillMaxSize()) {
            val unit = density * state.scale
            drawGrid(state.pan, 24f * unit, gridColor)
            val byId = doc.blocks.associateBy { it.id }
            for (l in doc.links) {
                val from = byId[l.from] ?: continue
                val to = byId[l.to] ?: continue
                val spec = BlockCatalog[from.type] ?: continue
                val idx = spec.outputs.indexOfFirst { it.id == l.port }
                if (idx < 0) continue
                val s = state.toScreen(Geo.outputPort(from, idx, spec.outputs.size))
                val e = state.toScreen(Geo.inputPort(to))
                val color = when (l.port) {
                    "yes", "ok" -> yesColor
                    "no", "fail", "cancel" -> noColor
                    else -> if (spec.type == "flow.start") flowColor else categoryColors.getValue(spec.category)
                }
                drawLink(s, e, unit, from.id == to.id, color, highlighted = l.from in state.selection || l.to in state.selection)
            }
            state.dragLink?.let { d ->
                val s = state.toScreen(d.start)
                val e = state.toScreen(d.end)
                drawPath(
                    linkPath(s, e, unit, false),
                    dragColor,
                    style = Stroke(width = 2.5f * unit, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * unit, 8f * unit))),
                )
                drawCircle(dragColor, 6f * unit, e)
            }
        }

        val vw = state.viewport.width
        val vh = state.viewport.height
        val unit = density * state.scale
        val margin = 60f * unit

        for (c in doc.comments) key(c.id) {
            val p = state.toScreen(Offset(c.x, c.y))
            val visible = vw == 0 || !(p.x > vw + margin || p.y > vh + margin || p.x + c.width * unit < -margin || p.y + 400 * unit < -margin)
            if (visible) CommentNote(
                c,
                selected = c.id in state.selection,
                modifier = Modifier
                    .offset { IntOffset(state.toScreen(Offset(c.x, c.y)).x.roundToInt(), state.toScreen(Offset(c.x, c.y)).y.roundToInt()) }
                    .graphicsLayer {
                        scaleX = state.scale; scaleY = state.scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .width(c.width.dp)
                    .itemGestures(state, c.id, density),
            )
        }

        for (b in doc.blocks) key(b.id) {
            val p = state.toScreen(Offset(b.x, b.y))
            val dragging = state.dragLink?.from == b.id
            val visible = dragging || vw == 0 ||
                !(p.x > vw + margin || p.y > vh + margin || p.x + Geo.BLOCK_W * unit < -margin || p.y + (Geo.BLOCK_H + Geo.PAD_BOTTOM) * unit < -margin)
            val spec = BlockCatalog[b.type]
            if (visible) BlockNode(
                block = b,
                spec = spec,
                selected = b.id in state.selection,
                running = b.id in activeBlocks,
                isDropTarget = state.dropTarget == b.id,
                hasError = state.issues.any { it.blockId == b.id && it.severity == Issue.Severity.ERROR },
                linkedPorts = doc.links.filter { it.from == b.id }.map { it.port }.toSet(),
                portDrag = PortDrag(
                    onStart = { portId, index ->
                        val cur = state.doc.block(b.id)
                        if (cur != null) state.startLink(b.id, portId, Geo.outputPort(cur, index, spec?.outputs?.size ?: 1))
                    },
                    onDrag = { d -> state.moveLink(d.x / density, d.y / density) },
                    onEnd = { state.finishLink() },
                    onCancel = { state.cancelLink() },
                ),
                modifier = Modifier
                    .offset {
                        val sp = state.toScreen(Offset(b.x, b.y - Geo.PAD_TOP))
                        IntOffset(sp.x.roundToInt(), sp.y.roundToInt())
                    }
                    .graphicsLayer {
                        scaleX = state.scale; scaleY = state.scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .size(Geo.BLOCK_W.dp, (Geo.PAD_TOP + Geo.BLOCK_H + Geo.PAD_BOTTOM).dp)
                    .itemGestures(state, b.id, density),
            )
        }
    }
}

/** Tap to select, long-press to add to the selection, drag to move (the whole selection if it's selected). */
private fun Modifier.itemGestures(state: EditorState, id: String, density: Float): Modifier = this
    .pointerInput(id) {
        detectTapGestures(
            onTap = { state.tapItem(id) },
            onLongPress = { state.longPressItem(id) },
        )
    }
    .pointerInput(id) {
        detectDragGestures(
            onDragStart = {
                if (id !in state.selection) state.selection = setOf(id)
                state.beginGesture()
            },
            onDragEnd = { state.endGesture() },
            onDragCancel = { state.endGesture() },
            onDrag = { change, amount ->
                change.consume()
                // The item's layer is scaled, so local px divided by density are canvas units.
                state.dragItems(state.selection, amount.x / density, amount.y / density)
            },
        )
    }

private fun DrawScope.drawGrid(pan: Offset, step: Float, color: Color) {
    if (step < 9f) return
    val startX = pan.x % step
    val startY = pan.y % step
    val points = ArrayList<Offset>()
    var x = startX
    while (x < size.width) {
        var y = startY
        while (y < size.height) {
            points += Offset(x, y)
            y += step
        }
        x += step
    }
    drawPoints(points, PointMode.Points, color, strokeWidth = max(1.5f, step / 12f), cap = StrokeCap.Round)
}

internal fun linkPath(s: Offset, e: Offset, unit: Float, self: Boolean): Path {
    val p = Path()
    p.moveTo(s.x, s.y)
    val drop = 36f * unit
    when {
        self || e.y < s.y + 12f * unit -> {
            // Going back up (loops): leave downwards, swing around the side, come in from above.
            val right = self || e.x > s.x
            val side = if (right) max(s.x, e.x) + 130f * unit else minOf(s.x, e.x) - 130f * unit
            val midY = (s.y + e.y) / 2f
            p.cubicTo(s.x, s.y + drop * 1.6f, side, s.y + drop * 1.6f, side, midY)
            p.cubicTo(side, e.y - drop * 1.6f, e.x, e.y - drop * 1.6f, e.x, e.y)
        }
        else -> {
            val dy = max(24f * unit, abs(e.y - s.y) * 0.5f)
            p.cubicTo(s.x, s.y + dy, e.x, e.y - dy, e.x, e.y)
        }
    }
    return p
}

private fun DrawScope.drawLink(s: Offset, e: Offset, unit: Float, self: Boolean, color: Color, highlighted: Boolean) {
    val w = (if (highlighted) 3.5f else 2.5f) * unit
    drawPath(linkPath(s, e, unit, self), color, style = Stroke(width = w, cap = StrokeCap.Round))
    // Arrowhead just above the input dot.
    val tip = Offset(e.x, e.y - 7f * unit)
    val head = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - 6f * unit, tip.y - 10f * unit)
        lineTo(tip.x + 6f * unit, tip.y - 10f * unit)
        close()
    }
    drawPath(head, color)
}
