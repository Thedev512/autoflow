@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.autoflow.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.autoflow.core.blocks.BlockCatalog
import dev.autoflow.core.blocks.ParamKind
import dev.autoflow.core.blocks.ParamSpec
import dev.autoflow.core.blocks.Permission
import dev.autoflow.core.engine.Issue
import dev.autoflow.core.engine.LogicExecutors
import dev.autoflow.core.model.Block
import dev.autoflow.core.model.Comment
import dev.autoflow.ui.common.AppInfo
import dev.autoflow.ui.common.AppPickerDialog
import dev.autoflow.ui.common.blockIcon
import dev.autoflow.ui.theme.BlockColors

/** Things the inspector needs from the platform. */
class InspectorHost(
    val apps: List<AppInfo>,
    val onLoadApps: () -> Unit,
    val granted: Set<Permission>,
    val onGrant: (Permission) -> Unit,
)

/** Side panel (or bottom sheet on phones) for editing what's selected. */
@Composable
fun Inspector(state: EditorState, host: InspectorHost, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val block = state.selectedBlock
    val comment = state.selectedComment
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        when {
            block != null -> key(block.id) { BlockInspector(state, block, host, onClose) }
            comment != null -> key(comment.id) { CommentInspector(state, comment, onClose) }
            state.selection.size > 1 -> MultiInspector(state, onClose)
        }
    }
}

@Composable
private fun PanelHeader(title: String, subtitle: String?, onClose: () -> Unit, leading: @Composable (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close panel") }
    }
}

@Composable
private fun BlockInspector(state: EditorState, block: Block, host: InspectorHost, onClose: () -> Unit) {
    val spec = BlockCatalog[block.type]
    if (spec == null) {
        PanelHeader("Unknown block", block.type, onClose)
        Text("This flow was made with a newer version of AutoFlow.")
        return
    }
    val color = BlockColors.of(spec)
    PanelHeader(spec.title, spec.category.title, onClose) {
        Box(Modifier.size(40.dp).background(color.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(blockIcon(spec.icon), null, tint = color)
        }
        Spacer(Modifier.width(12.dp))
    }
    Spacer(Modifier.height(8.dp))
    Text(spec.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    for (issue in state.issuesFor(block.id)) {
        Spacer(Modifier.height(8.dp))
        IssueCard(issue)
    }

    val values = spec.resolve(block.params)
    val variables = remember(state.doc) { knownVariables(state) }
    Spacer(Modifier.height(16.dp))
    for (p in spec.params) {
        if (!p.isVisible(values, spec.params)) continue
        ParamEditor(p, values[p.key].orEmpty(), values, variables, host) { key, v -> state.setParam(block.id, key, v) }
        Spacer(Modifier.height(12.dp))
    }
    OutlinedTextField(
        value = block.label,
        onValueChange = { state.setLabel(block.id, it) },
        label = { Text("Name on canvas (optional)") },
        placeholder = { Text(spec.title) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (spec.outputs.isNotEmpty()) {
        SectionTitle("Arrows")
        for (port in spec.outputs) {
            val link = state.doc.linkFrom(block.id, port.id)
            val target = link?.let { state.doc.block(it.to) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Box(Modifier.size(10.dp).background(BlockColors.port(port.id, color), CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(port.label.ifEmpty { "Next" }, fontWeight = FontWeight.Medium, modifier = Modifier.width(72.dp))
                Text(
                    target?.let { t -> t.label.ifBlank { BlockCatalog[t.type]?.title ?: t.type } } ?: "Not connected: the path ends",
                    color = if (target == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (target != null) {
                    IconButton(onClick = { state.disconnect(block.id, port.id) }) { Icon(Icons.Rounded.LinkOff, "Remove arrow") }
                }
            }
        }
        Text(
            "Drag from a dot under the block onto another block to connect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val perms = spec.requiredPermissions(block.params)
    if (perms.isNotEmpty()) {
        SectionTitle("Needs permission")
        for (p in perms) PermissionRow(p, p in host.granted) { host.onGrant(p) }
    }

    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { state.duplicateSelection() }) {
            Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Duplicate")
        }
        OutlinedButton(onClick = { state.deleteSelection() }) {
            Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Delete")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
}

@Composable
fun IssueCard(issue: Issue) {
    val error = issue.severity == Issue.Severity.ERROR
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (error) Icons.Rounded.ErrorOutline else Icons.Rounded.WarningAmber, null)
            Spacer(Modifier.width(10.dp))
            Text(issue.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun PermissionRow(p: Permission, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.Lock,
            null,
            tint = if (granted) BlockColors.port("yes", MaterialTheme.colorScheme.primary) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, fontWeight = FontWeight.Medium)
            Text(p.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!granted) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onGrant) { Text("Allow") }
        }
    }
}

private fun knownVariables(state: EditorState): List<String> {
    val names = sortedSetOf<String>()
    for (b in state.doc.blocks) {
        val spec = BlockCatalog[b.type] ?: continue
        val values = spec.resolve(b.params)
        for (p in spec.params) if (p.kind == ParamKind.VARIABLE && p.isVisible(values, spec.params)) {
            values[p.key]?.trim()?.takeIf { it.isNotEmpty() }?.let { names += it }
        }
        if (b.type == "trigger.notification") names += listOf("notif_title", "notif_text", "notif_app")
        if (spec.outputs.any { it.id == "fail" }) names += "error"
    }
    return names.toList()
}

@Composable
private fun ParamEditor(
    p: ParamSpec,
    value: String,
    all: Map<String, String>,
    variables: List<String>,
    host: InspectorHost,
    onChange: (String, String) -> Unit,
) {
    val set: (String) -> Unit = { onChange(p.key, it) }
    when (p.kind) {
        ParamKind.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(p.label, modifier = Modifier.weight(1f))
            Switch(checked = value == "true", onCheckedChange = { set(it.toString()) })
        }

        ParamKind.CHOICE -> ChoiceEditor(p, value, set)

        ParamKind.DAYS -> Column {
            Text(p.label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, label ->
                    val d = (i + 1).toString()
                    val on = d in value
                    FilterChip(
                        selected = on,
                        onClick = {
                            val next = if (on) value.replace(d, "") else (value + d).toCharArray().sorted().joinToString("")
                            set(next)
                        },
                        label = { Text(label) },
                    )
                }
            }
            Text(BlockCatalog.daysLabel(value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        ParamKind.TIME -> {
            var picking by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = value,
                onValueChange = set,
                label = { Text(p.label) },
                placeholder = { Text("07:30") },
                singleLine = true,
                isError = LogicExecutors.parseTime(value) == null,
                trailingIcon = { IconButton(onClick = { picking = true }) { Icon(Icons.Rounded.Schedule, "Pick time") } },
                supportingText = { if (p.hint.isNotEmpty()) Text(p.hint) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (picking) {
                val t = LogicExecutors.parseTime(value)
                val pickerState = rememberTimePickerState(t?.hour ?: 8, t?.minute ?: 0, is24Hour = true)
                AlertDialog(
                    onDismissRequest = { picking = false },
                    confirmButton = {
                        TextButton(onClick = {
                            set("%02d:%02d".format(pickerState.hour, pickerState.minute))
                            picking = false
                        }) { Text("OK") }
                    },
                    dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
                    text = { TimePicker(state = pickerState) },
                )
            }
        }

        ParamKind.APP -> {
            var picking by remember { mutableStateOf(false) }
            val name = all["${p.key}Name"].orEmpty()
            OutlinedTextField(
                value = value,
                onValueChange = { set(it); onChange("${p.key}Name", "") },
                label = { Text(p.label) },
                placeholder = { Text(if (p.hint.isNotEmpty()) p.hint else "Package name") },
                supportingText = { if (name.isNotEmpty()) Text(name) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { host.onLoadApps(); picking = true }) { Icon(Icons.Rounded.Apps, "Pick an app") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (picking) {
                AppPickerDialog(
                    apps = host.apps,
                    allowAny = p.key == "package" && p.default.isEmpty(),
                    onPick = { app ->
                        onChange(p.key, app?.packageName.orEmpty())
                        onChange("${p.key}Name", app?.label.orEmpty())
                        picking = false
                    },
                    onDismiss = { picking = false },
                )
            }
        }

        else -> {
            val code = p.kind == ParamKind.EXPRESSION || p.kind == ParamKind.VARIABLE
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = set,
                    label = { Text(p.label) },
                    singleLine = p.kind == ParamKind.VARIABLE || p.kind == ParamKind.NUMBER,
                    minLines = if (p.kind == ParamKind.MULTILINE) 3 else 1,
                    maxLines = if (p.kind == ParamKind.MULTILINE) 8 else 3,
                    textStyle = if (code) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
                    else MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(
                        capitalization = if (code) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
                        autoCorrect = !code,
                        keyboardType = if (p.kind == ParamKind.TEXT && p.key == "url") KeyboardType.Uri else KeyboardType.Text,
                    ),
                    supportingText = {
                        val hint = when {
                            p.hint.isNotEmpty() -> p.hint
                            p.kind == ParamKind.TEXT || p.kind == ParamKind.MULTILINE -> "Put a variable in braces, like {battery}"
                            p.kind == ParamKind.NUMBER -> "A number, or math like count + 1"
                            else -> ""
                        }
                        if (hint.isNotEmpty()) Text(hint)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                val insertable = p.kind == ParamKind.TEXT || p.kind == ParamKind.MULTILINE ||
                    p.kind == ParamKind.EXPRESSION || p.kind == ParamKind.NUMBER
                if (insertable && variables.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (v in variables.take(8)) {
                            AssistChip(
                                onClick = {
                                    val ins = if (p.kind == ParamKind.TEXT || p.kind == ParamKind.MULTILINE) "{$v}" else v
                                    set(if (value.isEmpty() || value.endsWith(" ")) value + ins else "$value $ins")
                                },
                                label = { Text(v, fontFamily = FontFamily.Monospace) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceEditor(p: ParamSpec, value: String, set: (String) -> Unit) {
    Column {
        Text(p.label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        val short = p.choices.size <= 3 && p.choices.all { it.label.length <= 12 }
        if (short) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                p.choices.forEachIndexed { i, c ->
                    SegmentedButton(
                        selected = c.value == value,
                        onClick = { set(c.value) },
                        shape = SegmentedButtonDefaults.itemShape(i, p.choices.size),
                    ) { Text(c.label, maxLines = 1) }
                }
            }
        } else {
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text(p.choices.firstOrNull { it.value == value }?.label ?: value, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.UnfoldMore, null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (c in p.choices) {
                        DropdownMenuItem(text = { Text(c.label) }, onClick = { set(c.value); open = false })
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentInspector(state: EditorState, comment: Comment, onClose: () -> Unit) {
    PanelHeader("Comment", "Notes for you, ignored when the flow runs", onClose)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = comment.text,
        onValueChange = { state.setComment(comment.id, it) },
        minLines = 4,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = { state.deleteSelection() }) {
        Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Delete")
    }
}

@Composable
private fun MultiInspector(state: EditorState, onClose: () -> Unit) {
    PanelHeader("${state.selection.size} selected", "Drag any of them to move them all", onClose)
    Spacer(Modifier.height(16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { state.copySelection() }) { Text("Copy") }
        OutlinedButton(onClick = { state.duplicateSelection() }) { Text("Duplicate") }
        OutlinedButton(onClick = { state.deleteSelection() }) { Text("Delete") }
        OutlinedButton(onClick = { state.clearSelection(); state.multiSelect = false }) { Text("Deselect") }
    }
}
