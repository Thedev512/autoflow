@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.autoflow.ui.logs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.autoflow.core.blocks.Category
import dev.autoflow.core.engine.LogEntry
import dev.autoflow.core.engine.LogLevel
import dev.autoflow.core.engine.RunStatus
import dev.autoflow.ui.common.EmptyState
import dev.autoflow.ui.common.StatusLine
import dev.autoflow.ui.theme.BlockColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Per-flow log, newest first, grouped by run, with how long each block took. */
@Composable
fun LogsScreen(
    flowName: String,
    status: RunStatus?,
    entries: List<LogEntry>,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("all") }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = when (filter) {
        "problems" -> entries.filter { it.level == LogLevel.WARN || it.level == LogLevel.ERROR }
        "events" -> entries.filter { it.level != LogLevel.DEBUG }
        else -> entries
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text("Logs · $flowName", maxLines = 1)
                        StatusLine(status)
                    }
                },
                actions = {
                    IconButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                        Icon(Icons.Rounded.DeleteSweep, "Clear logs")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filter == "all", { filter = "all" }, label = { Text("Everything") })
                FilterChip(filter == "events", { filter = "events" }, label = { Text("Hide block steps") })
                FilterChip(filter == "problems", { filter = "problems" }, label = { Text("Problems") })
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState("Nothing logged yet", "Run the flow and every block it passes through shows up here, with timings.")
                }
            } else {
                val grouped = shown.groupBy { it.runId }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    for ((runId, rows) in grouped) {
                        stickyHeader(key = "run-$runId") {
                            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "Run started " + dayTime.format(Date(rows.minOf { it.time })),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                        items(rows) { LogRow(it) }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear this flow's logs?") },
            confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
private val dayTime = SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.getDefault())

fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "$ms ms"
    ms < 60_000 -> "%.1f s".format(Locale.ROOT, ms / 1000.0)
    ms < 3_600_000 -> "${ms / 60_000} min ${(ms / 1000) % 60} s"
    else -> "${ms / 3_600_000} h ${(ms / 60_000) % 60} min"
}

@Composable
private fun LogRow(e: LogEntry) {
    val scheme = MaterialTheme.colorScheme
    val accent: Color = when (e.level) {
        LogLevel.ERROR -> scheme.error
        LogLevel.WARN -> BlockColors.of(Category.TRIGGER)
        LogLevel.INFO -> scheme.primary
        LogLevel.DEBUG -> scheme.outlineVariant
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(3.dp).height(36.dp).background(accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Text(
            clock.format(Date(e.time)),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = scheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Column(Modifier.weight(1f)) {
            e.blockTitle?.let { Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
            Text(
                e.message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (e.level == LogLevel.ERROR) scheme.error else scheme.onSurface,
            )
        }
        e.durationMs?.let { d ->
            Surface(shape = RoundedCornerShape(8.dp), color = scheme.surfaceContainerHigh) {
                Text(formatDuration(d), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}
