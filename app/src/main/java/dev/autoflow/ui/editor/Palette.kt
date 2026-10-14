package dev.autoflow.ui.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.autoflow.core.blocks.BlockCatalog
import dev.autoflow.core.blocks.BlockSpec
import dev.autoflow.core.blocks.Category
import dev.autoflow.ui.common.blockIcon
import dev.autoflow.ui.theme.BlockColors

/** A block being dragged out of the palette, in window coordinates. */
class PaletteDrag {
    var spec by mutableStateOf<BlockSpec?>(null)
    var position by mutableStateOf(Offset.Zero)
}

/**
 * The block list: search plus color-coded categories. Tap a block to add it to the middle
 * of the canvas (wired under the selected block), or long-press and drag it onto the canvas.
 */
@Composable
fun BlockPalette(
    drag: PaletteDrag,
    onAdd: (BlockSpec) -> Unit,
    onDrop: (BlockSpec, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search blocks", maxLines = 1) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (query.isNotBlank()) {
                val found = BlockCatalog.search(query)
                if (found.isEmpty()) {
                    item {
                        Text(
                            "No block matches “$query”",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(found, key = { it.type }) { PaletteItem(it, drag, onAdd, onDrop) }
            } else {
                for (cat in Category.entries) {
                    val open = cat.name !in collapsed
                    item(key = "h-${cat.name}") {
                        CategoryHeader(cat, open) {
                            collapsed = if (open) collapsed + cat.name else collapsed - cat.name
                        }
                    }
                    if (open) {
                        items(BlockCatalog.byCategory(cat), key = { it.type }) { PaletteItem(it, drag, onAdd, onDrop) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(category: Category, open: Boolean, onToggle: () -> Unit) {
    val color = BlockColors.of(category)
    val rot by animateFloatAsState(if (open) 0f else -90f, label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(category.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.weight(1f))
        Text("${BlockCatalog.byCategory(category).size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand", modifier = Modifier.rotate(rot))
    }
}

@Composable
private fun PaletteItem(
    spec: BlockSpec,
    drag: PaletteDrag,
    onAdd: (BlockSpec) -> Unit,
    onDrop: (BlockSpec, Offset) -> Unit,
) {
    val color = BlockColors.of(spec)
    val origin = remember { arrayOf(Offset.Zero) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .onGloballyPositioned { origin[0] = it.positionInRoot() }
            .pointerInput(spec.type) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { local ->
                        drag.spec = spec
                        drag.position = origin[0] + local
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        drag.position += amount
                    },
                    onDragEnd = {
                        onDrop(spec, drag.position)
                        drag.spec = null
                    },
                    onDragCancel = { drag.spec = null },
                )
            }
            .clickable { onAdd(spec) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(color.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(blockIcon(spec.icon), null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(spec.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                spec.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
