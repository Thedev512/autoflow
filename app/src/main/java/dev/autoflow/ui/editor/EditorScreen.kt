@file:OptIn(ExperimentalMaterial3Api::class)

package dev.autoflow.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.autoflow.core.blocks.BlockSpec
import dev.autoflow.core.engine.Issue
import dev.autoflow.core.engine.RunPhase
import dev.autoflow.core.engine.RunStatus
import dev.autoflow.ui.common.StatusLine
import kotlin.math.roundToInt

class EditorActions(
    val onBack: () -> Unit,
    val onRun: () -> Unit,
    val onStop: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onLogs: () -> Unit,
    val onExport: () -> Unit,
)

@Composable
fun EditorScreen(state: EditorState, status: RunStatus?, host: InspectorHost, actions: EditorActions) {
    val anchor = remember { CanvasAnchor() }
    val drag = remember { PaletteDrag() }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var showPalette by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var showIssues by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density

    LaunchedEffect(Unit) {
        // Frame the flow once the canvas knows its size.
        var tries = 0
        while (state.viewport.width == 0 && tries++ < 20) kotlinx.coroutines.delay(16)
        if (state.doc.blocks.size > 1) state.fitToView()
    }

    fun drop(spec: BlockSpec, windowPos: Offset) {
        val local = windowPos - anchor.origin
        val v = state.viewport
        if (local.x in 0f..v.width.toFloat() && local.y in 0f..v.height.toFloat()) {
            state.addBlock(spec, state.toWorld(local))
            showPalette = false
        }
    }

    Scaffold(
        topBar = { EditorTopBar(state, status, actions, onRename = { renaming = true }, onIssues = { showIssues = true }) },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .onGloballyPositioned { rootOrigin = it.positionInRoot() }
        ) {
            val maxW = maxWidth
            val maxH = maxHeight
            val sidebar = maxW >= 720.dp
            val sideInspector = maxW >= 900.dp
            val hasSelection = state.selection.isNotEmpty()
            Row(Modifier.fillMaxSize()) {
                if (sidebar) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(if (maxW < 960.dp) 248.dp else 280.dp).fillMaxHeight()) {
                        BlockPalette(drag, onAdd = { state.addBlock(it) }, onDrop = ::drop)
                    }
                    VerticalDivider()
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    FlowCanvas(state, status?.activeBlocks.orEmpty(), anchor, Modifier.fillMaxSize())

                    IssueBanner(state.issues, Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) { showIssues = true }

                    ZoomControls(state, Modifier.align(Alignment.BottomEnd).padding(16.dp))

                    if (!sidebar) {
                        ExtendedFloatingActionButton(
                            onClick = { showPalette = true },
                            icon = { Icon(Icons.Rounded.Add, null) },
                            text = { Text("Block") },
                            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                        )
                    }

                    if (!sideInspector) {
                        BottomPanel(hasSelection, maxH * 0.55f, Modifier.align(Alignment.BottomCenter)) {
                            Inspector(state, host, onClose = { state.clearSelection(); state.multiSelect = false })
                        }
                    }
                }
                if (sideInspector) {
                    AnimatedVisibility(
                        visible = hasSelection,
                        enter = slideInHorizontally { it } + fadeIn(),
                        exit = slideOutHorizontally { it } + fadeOut(),
                    ) {
                        Row {
                            VerticalDivider()
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(360.dp).fillMaxHeight()) {
                                Inspector(state, host, onClose = { state.clearSelection(); state.multiSelect = false })
                            }
                        }
                    }
                }
            }

            // Phone layout: the palette slides up over the canvas.
            AnimatedVisibility(
                visible = showPalette && !sidebar,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    tonalElevation = 3.dp,
                    shadowElevation = 16.dp,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.7f),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp)) {
                            Text("Add a block", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showPalette = false }) { Text("Done") }
                        }
                        BlockPalette(drag, onAdd = { state.addBlock(it); showPalette = false }, onDrop = ::drop)
                    }
                }
            }

            drag.spec?.let { spec ->
                val ghostW = Geo.BLOCK_W * density
                BlockGhost(
                    spec,
                    Modifier.offset {
                        val p = drag.position - rootOrigin
                        IntOffset((p.x - ghostW / 2).roundToInt(), (p.y - 40 * density).roundToInt())
                    },
                )
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(state.doc.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename flow") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { if (name.isNotBlank()) state.rename(name.trim()); renaming = false }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    if (showIssues) {
        AlertDialog(
            onDismissRequest = { showIssues = false },
            title = { Text(if (state.issues.isEmpty()) "No problems" else "Problems") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.issues.isEmpty()) Text("The flow looks good to go.")
                    for (issue in state.issues.take(12)) {
                        Box(Modifier.clickable {
                            issue.blockId?.let { id -> state.selection = setOf(id); centerOn(state, id) }
                            showIssues = false
                        }) { IssueCard(issue) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showIssues = false }) { Text("OK") } },
        )
    }
}

private fun centerOn(state: EditorState, blockId: String) {
    val b = state.doc.block(blockId) ?: return
    val unit = state.density * state.scale
    state.pan = Offset(
        state.viewport.width / 2f - (b.x + Geo.BLOCK_W / 2) * unit,
        state.viewport.height / 2f - (b.y + Geo.BLOCK_H / 2) * unit,
    )
}

@Composable
private fun BottomPanel(visible: Boolean, maxHeight: Dp, modifier: Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 3.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
        ) { content() }
    }
}

@Composable
private fun EditorTopBar(
    state: EditorState,
    status: RunStatus?,
    actions: EditorActions,
    onRename: () -> Unit,
    onIssues: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val active = status?.isActive == true
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        title = {
            Column(Modifier.clickable(onClick = onRename)) {
                Text(state.doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                StatusLine(status)
            }
        },
        actions = {
            IconButton(onClick = state::undo, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
            IconButton(onClick = state::redo, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
            IconButton(onClick = { state.paste() }, enabled = state.clipboard != null) { Icon(Icons.Rounded.ContentPaste, "Paste") }
            IconButton(onClick = state::addComment) { Icon(Icons.Rounded.StickyNote2, "Add comment") }
            IconToggleButton(checked = state.multiSelect, onCheckedChange = { state.multiSelect = it; if (!it) state.clearSelection() }) {
                Icon(Icons.Rounded.Checklist, if (state.multiSelect) "Stop selecting several" else "Select several")
            }
            if (active) {
                val paused = status?.phase == RunPhase.PAUSED
                IconButton(onClick = if (paused) actions.onResume else actions.onPause) {
                    Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Resume" else "Pause")
                }
                FilledTonalButton(
                    onClick = actions.onStop,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop")
                }
            } else {
                Button(onClick = actions.onRun) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Run")
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val hasSel = state.selection.isNotEmpty()
                    DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, enabled = hasSel,
                        onClick = { state.copySelection(); menu = false })
                    DropdownMenuItem(text = { Text("Cut") }, leadingIcon = { Icon(Icons.Rounded.ContentCut, null) }, enabled = hasSel,
                        onClick = { state.cutSelection(); menu = false })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, enabled = hasSel,
                        onClick = { state.deleteSelection(); menu = false })
                    DropdownMenuItem(text = { Text("Select all") }, leadingIcon = { Icon(Icons.Rounded.SelectAll, null) },
                        onClick = { state.selectAll(); menu = false })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Fit to screen") }, leadingIcon = { Icon(Icons.Rounded.FitScreen, null) },
                        onClick = { state.fitToView(); menu = false })
                    DropdownMenuItem(text = { Text("Check for problems") }, leadingIcon = { Icon(Icons.Rounded.ErrorOutline, null) },
                        onClick = { onIssues(); menu = false })
                    DropdownMenuItem(text = { Text("Logs") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ListAlt, null) },
                        onClick = { actions.onLogs(); menu = false })
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { onRename(); menu = false })
                    DropdownMenuItem(text = { Text("Export to file") }, leadingIcon = { Icon(Icons.Rounded.FileUpload, null) },
                        onClick = { actions.onExport(); menu = false })
                }
            }
        },
    )
}

@Composable
private fun IssueBanner(issues: List<Issue>, modifier: Modifier, onClick: () -> Unit) {
    val errors = issues.count { it.severity == Issue.Severity.ERROR }
    AnimatedVisibility(errors > 0, modifier = modifier, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.errorContainer,
            shadowElevation = 4.dp,
            modifier = Modifier.clickable(onClick = onClick),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (errors == 1) "1 problem to fix" else "$errors problems to fix",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ZoomControls(state: EditorState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SmallFloatingActionButton(onClick = { state.zoomBy(1.25f) }) { Icon(Icons.Rounded.Add, "Zoom in") }
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(
                "${(state.scale * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).widthIn(min = 32.dp),
            )
        }
        SmallFloatingActionButton(onClick = { state.zoomBy(0.8f) }) { Icon(Icons.Rounded.Remove, "Zoom out") }
        SmallFloatingActionButton(onClick = state::fitToView) { Icon(Icons.Rounded.FitScreen, "Fit to screen") }
    }
}
