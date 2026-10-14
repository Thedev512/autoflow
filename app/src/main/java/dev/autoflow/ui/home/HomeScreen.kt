@file:OptIn(ExperimentalMaterial3Api::class)

package dev.autoflow.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.autoflow.core.blocks.BlockCatalog
import dev.autoflow.core.engine.RunPhase
import dev.autoflow.core.engine.RunStatus
import dev.autoflow.core.templates.Template
import dev.autoflow.ui.common.EmptyState
import dev.autoflow.ui.common.StatusLine
import dev.autoflow.ui.common.blockIcon
import dev.autoflow.ui.theme.BlockColors

data class FlowSummary(
    val id: String,
    val name: String,
    val blockTypes: List<String>,
    val startAtBoot: Boolean,
    val status: RunStatus?,
)

class HomeActions(
    val onOpen: (String) -> Unit,
    val onRun: (String) -> Unit,
    val onStop: (String) -> Unit,
    val onPause: (String) -> Unit,
    val onResume: (String) -> Unit,
    val onLogs: (String) -> Unit,
    val onCreate: (Template?) -> Unit,
    val onImport: () -> Unit,
    val onExport: (String) -> Unit,
    val onExportAll: () -> Unit,
    val onDuplicate: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onRename: (String, String) -> Unit,
    val onToggleBoot: (String, Boolean) -> Unit,
    val onSettings: () -> Unit,
    val onStopAll: () -> Unit,
)

@Composable
fun HomeScreen(flows: List<FlowSummary>, templates: List<Template>, actions: HomeActions) {
    var creating by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val running = flows.count { it.status?.isActive == true }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            androidx.compose.material3.LargeTopAppBar(
                title = {
                    Column {
                        Text("AutoFlow", fontWeight = FontWeight.Bold)
                        Text(
                            when (running) {
                                0 -> "${flows.size} flows · nothing running"
                                1 -> "${flows.size} flows · 1 running"
                                else -> "${flows.size} flows · $running running"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (running > 0) {
                        TextButton(onClick = actions.onStopAll) { Text("Stop all") }
                    }
                    IconButton(onClick = actions.onImport) { Icon(Icons.Rounded.FileDownload, "Import flows") }
                    IconButton(onClick = actions.onExportAll, enabled = flows.isNotEmpty()) { Icon(Icons.Rounded.FileUpload, "Export all flows") }
                    IconButton(onClick = actions.onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("New flow") },
            )
        },
    ) { padding ->
        if (flows.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    "No flows yet",
                    "Build an automation by connecting blocks, or start from a template.",
                ) {
                    Button(onClick = { creating = true }) { Text("Create a flow") }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(340.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                items(flows, key = { it.id }) { FlowCard(it, actions) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Flows only run in the background while they're active, and sleep between schedules to save battery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }

    if (creating) {
        NewFlowDialog(templates, onPick = { creating = false; actions.onCreate(it) }, onDismiss = { creating = false })
    }
}

@Composable
private fun FlowCard(flow: FlowSummary, actions: HomeActions) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val status = flow.status
    val active = status?.isActive == true
    ElevatedCard(
        onClick = { actions.onOpen(flow.id) },
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    flow.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More for ${flow.name}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                            onClick = { menu = false; actions.onDuplicate(flow.id) })
                        DropdownMenuItem(text = { Text("Export to file") }, leadingIcon = { Icon(Icons.Rounded.FileUpload, null) },
                            onClick = { menu = false; actions.onExport(flow.id) })
                        DropdownMenuItem(
                            text = { Text("Start when tablet turns on") },
                            leadingIcon = { Icon(Icons.Rounded.PowerSettingsNew, null) },
                            trailingIcon = { Checkbox(checked = flow.startAtBoot, onCheckedChange = null) },
                            onClick = { actions.onToggleBoot(flow.id, !flow.startAtBoot) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = { menu = false; deleting = true })
                    }
                }
            }
            BlockStrip(flow.blockTypes)
            Spacer(Modifier.height(10.dp))
            StatusLine(status)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    FilledTonalButton(
                        onClick = { actions.onStop(flow.id) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) { Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop") }
                    val paused = status?.phase == RunPhase.PAUSED
                    OutlinedButton(onClick = { if (paused) actions.onResume(flow.id) else actions.onPause(flow.id) }) {
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (paused) "Resume" else "Pause")
                    }
                } else {
                    Button(onClick = { actions.onRun(flow.id) }) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (status?.phase == RunPhase.SLEEPING) "Wake" else "Run")
                    }
                    if (status?.phase == RunPhase.SLEEPING) {
                        OutlinedButton(onClick = { actions.onStop(flow.id) }) { Text("Stop") }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { actions.onLogs(flow.id) }) { Icon(Icons.AutoMirrored.Rounded.ListAlt, "Logs") }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(flow.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename flow") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { actions.onRename(flow.id, name.trim().ifEmpty { flow.name }); renaming = false }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete “${flow.name}”?") },
            text = { Text("The flow and its logs will be removed. Export it first if you might want it back.") },
            confirmButton = { TextButton(onClick = { actions.onDelete(flow.id); deleting = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

/** A row of little colored icons for the triggers and actions a flow uses. */
@Composable
private fun BlockStrip(types: List<String>) {
    val specs = types.mapNotNull { BlockCatalog[it] }.filter { it.type != "flow.start" }.distinctBy { it.type }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (spec in specs.sortedBy { it.category.ordinal }.take(7)) {
            val c = BlockColors.of(spec)
            Box(Modifier.size(28.dp).background(c.copy(alpha = 0.16f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(blockIcon(spec.icon), spec.title, tint = c, modifier = Modifier.size(16.dp))
            }
        }
        if (specs.size > 7) Text("+${specs.size - 7}", style = MaterialTheme.typography.labelMedium)
        if (specs.isEmpty()) Text("Empty", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NewFlowDialog(templates: List<Template>, onPick: (Template?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New flow") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(220.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 520.dp),
            ) {
                item { TemplateCard("Blank flow", "Start with just a Flow start block.", "play") { onPick(null) } }
                items(templates, key = { it.title }) { t -> TemplateCard(t.title, t.description, t.icon) { onPick(t) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TemplateCard(title: String, body: String, icon: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp)) {
            Icon(blockIcon(icon), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
