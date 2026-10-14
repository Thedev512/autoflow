package dev.autoflow.ui.editor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autoflow.core.blocks.BlockSpec
import dev.autoflow.core.model.Block
import dev.autoflow.core.model.Comment
import dev.autoflow.ui.common.blockIcon
import dev.autoflow.ui.theme.BlockColors

/** Callbacks for dragging an arrow out of an output port. Deltas are in the block's local px. */
class PortDrag(
    val onStart: (portId: String, index: Int) -> Unit,
    val onDrag: (Offset) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * One block: a card with its input dot on top and output dots underneath.
 * The composable is [Geo.BLOCK_W] wide and includes [Geo.PAD_TOP]/[Geo.PAD_BOTTOM] for the dots.
 */
@Composable
fun BlockNode(
    block: Block,
    spec: BlockSpec?,
    selected: Boolean,
    running: Boolean,
    isDropTarget: Boolean,
    hasError: Boolean,
    linkedPorts: Set<String>,
    portDrag: PortDrag,
    modifier: Modifier = Modifier,
) {
    val color = BlockColors.of(spec)
    val scheme = MaterialTheme.colorScheme
    val lift by animateFloatAsState(if (selected || isDropTarget) 1f else 0f, tween(160), label = "lift")
    val pulse = if (running) {
        val t = rememberInfiniteTransition(label = "run")
        t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "pulse").value
    } else 0f

    Box(modifier) {
        val border = when {
            isDropTarget -> BorderStroke(2.5.dp, color)
            selected -> BorderStroke(2.dp, scheme.primary)
            running -> BorderStroke(2.5.dp, color.copy(alpha = pulse))
            hasError -> BorderStroke(1.5.dp, scheme.error)
            else -> BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f))
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = scheme.surfaceContainerLow,
            border = border,
            shadowElevation = (2 + 6 * lift).dp,
            modifier = Modifier
                .offset(y = Geo.PAD_TOP.dp)
                .size(Geo.BLOCK_W.dp, Geo.BLOCK_H.dp)
                .graphicsLayer { val s = 1f + 0.02f * lift; scaleX = s; scaleY = s },
        ) {
            Row {
                Box(Modifier.width(5.dp).fillMaxHeight().background(color))
                Row(
                    Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(34.dp).background(color.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(blockIcon(spec?.icon ?: ""), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            block.label.ifBlank { spec?.title ?: block.type },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val summary = spec?.summaryOf(block.params).orEmpty()
                        if (summary.isNotBlank()) {
                            Text(
                                summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (hasError) {
                        Icon(Icons.Rounded.Error, "Has a problem", tint = scheme.error, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        if (spec?.hasInput != false) {
            PortDot(
                color = color,
                filled = false,
                modifier = Modifier.offset(x = (Geo.BLOCK_W / 2 - 7).dp, y = (Geo.PAD_TOP - 7).dp),
            )
        }

        val outputs = spec?.outputs.orEmpty()
        outputs.forEachIndexed { i, port ->
            val x = Geo.BLOCK_W * (i + 1) / (outputs.size + 1)
            val portColor = BlockColors.port(port.id, color)
            Box(
                Modifier
                    .offset(x = (x - 16).dp, y = (Geo.PAD_TOP + Geo.BLOCK_H - 16).dp)
                    .size(32.dp)
                    .pointerInput(block.id, port.id) {
                        detectDragGestures(
                            onDragStart = { portDrag.onStart(port.id, i) },
                            onDragEnd = { portDrag.onEnd() },
                            onDragCancel = { portDrag.onCancel() },
                            onDrag = { change, amount -> change.consume(); portDrag.onDrag(amount) },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                PortDot(portColor, filled = port.id in linkedPorts)
            }
            if (port.label.isNotEmpty()) {
                Text(
                    port.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = portColor,
                    maxLines = 1,
                    modifier = Modifier.offset(x = (x + 9).dp, y = (Geo.PAD_TOP + Geo.BLOCK_H + 2).dp),
                )
            }
        }
    }
}

@Composable
private fun PortDot(color: Color, filled: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(14.dp)
            .background(if (filled) color else MaterialTheme.colorScheme.surface, CircleShape)
            .border(2.5.dp, color, CircleShape)
    )
}

@Composable
fun CommentNote(comment: Comment, selected: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
        color = scheme.tertiaryContainer.copy(alpha = 0.92f),
        border = if (selected) BorderStroke(2.dp, scheme.primary) else null,
        shadowElevation = 1.dp,
        modifier = modifier,
    ) {
        Text(
            comment.text.ifBlank { "Empty note" },
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onTertiaryContainer,
            modifier = Modifier.padding(12.dp).alpha(if (comment.text.isBlank()) 0.6f else 1f),
        )
    }
}

/** Ghost shown under the finger while dragging a block from the palette. */
@Composable
fun BlockGhost(spec: BlockSpec, modifier: Modifier = Modifier) {
    val color = BlockColors.of(spec)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        border = BorderStroke(2.dp, color),
        shadowElevation = 12.dp,
        modifier = modifier.size(Geo.BLOCK_W.dp, Geo.BLOCK_H.dp),
    ) {
        Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(blockIcon(spec.icon), null, tint = color)
            Spacer(Modifier.width(10.dp))
            Text(spec.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}
