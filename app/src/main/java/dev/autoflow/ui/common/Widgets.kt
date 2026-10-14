@file:OptIn(ExperimentalMaterial3Api::class)

package dev.autoflow.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.autoflow.core.blocks.Permission
import dev.autoflow.core.engine.RunPhase
import dev.autoflow.core.engine.RunStatus
import dev.autoflow.ui.editor.PermissionRow
import dev.autoflow.ui.theme.BlockColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppInfo(val label: String, val packageName: String)

@Composable
fun AppPickerDialog(apps: List<AppInfo>, allowAny: Boolean, onPick: (AppInfo?) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick an app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    placeholder = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (apps.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val shown = apps.filter {
                        query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
                    }
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        if (allowAny && query.isBlank()) {
                            item { AppRow("Any app", "Fires for every app") { onPick(null) } }
                        }
                        items(shown, key = { it.packageName }) { app -> AppRow(app.label, app.packageName) { onPick(app) } }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AppRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Text(title, fontWeight = FontWeight.Medium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

fun statusText(status: RunStatus?): String {
    if (status == null) return "Not running"
    val fmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
    return when (status.phase) {
        RunPhase.RUNNING -> status.detail.ifBlank { "Running" }
        RunPhase.WAITING -> status.detail.ifBlank { "Waiting" } +
            (status.wakeAt?.let { " until " + fmt.format(Date(it)) } ?: "")
        RunPhase.PAUSED -> "Paused"
        RunPhase.SLEEPING -> "Asleep until " + (status.wakeAt?.let { fmt.format(Date(it)) } ?: "its next time")
        RunPhase.FINISHED -> "Finished"
        RunPhase.STOPPED -> "Stopped"
        RunPhase.FAILED -> "Failed: " + status.detail
    }
}

@Composable
fun statusColor(status: RunStatus?): Color = when (status?.phase) {
    RunPhase.RUNNING, RunPhase.WAITING -> BlockColors.port("yes", MaterialTheme.colorScheme.primary)
    RunPhase.SLEEPING -> MaterialTheme.colorScheme.tertiary
    RunPhase.PAUSED -> BlockColors.of(dev.autoflow.core.blocks.Category.TRIGGER)
    RunPhase.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

/** A dot plus a short status line; the dot breathes while the flow is active. */
@Composable
fun StatusLine(status: RunStatus?, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    val active = status?.isActive == true && status.phase != RunPhase.PAUSED
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val a = if (active) {
            rememberInfiniteTransition(label = "dot")
                .animateFloat(0.3f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "a").value
        } else 1f
        Box(Modifier.size(8.dp).alpha(a).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            statusText(status),
            style = MaterialTheme.typography.bodySmall,
            color = if (status?.phase == RunPhase.FAILED) color else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Asked right before a flow starts, listing only what its blocks need. */
@Composable
fun PermissionDialog(
    needed: List<Permission>,
    granted: Set<Permission>,
    onGrant: (Permission) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val missing = needed.filter { it !in granted }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (missing.isEmpty()) "All set" else "This flow needs a few permissions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "AutoFlow only asks for what this flow's blocks use. Blocks without their permission will log an error instead of working.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                for (p in needed) PermissionRow(p, p in granted) { onGrant(p) }
            }
        },
        confirmButton = { TextButton(onClick = onStart) { Text(if (missing.isEmpty()) "Start" else "Start anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(blockIcon("play"), null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}
