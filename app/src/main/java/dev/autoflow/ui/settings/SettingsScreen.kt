@file:OptIn(ExperimentalMaterial3Api::class)

package dev.autoflow.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.autoflow.core.blocks.Permission
import dev.autoflow.ui.editor.PermissionRow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class SettingsModel(
    val themeMode: ThemeMode,
    val dynamicColor: Boolean,
    val dynamicSupported: Boolean,
    val folderName: String?,
    val granted: Set<Permission>,
    val batteryUnrestricted: Boolean,
    val version: String,
)

class SettingsActions(
    val onBack: () -> Unit,
    val onThemeMode: (ThemeMode) -> Unit,
    val onDynamicColor: (Boolean) -> Unit,
    val onPickFolder: () -> Unit,
    val onClearFolder: () -> Unit,
    val onGrant: (Permission) -> Unit,
    val onBatterySettings: () -> Unit,
)

@Composable
fun SettingsScreen(model: SettingsModel, actions: SettingsActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = { Text("Settings") },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).padding(16.dp)) {
                Section("Look")
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = model.themeMode == m,
                            onClick = { actions.onThemeMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        ) { Text(when (m) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" }) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Colors from wallpaper")
                        Text(
                            if (model.dynamicSupported) "Material You dynamic colors" else "Needs Android 12 or newer",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = model.dynamicColor && model.dynamicSupported, enabled = model.dynamicSupported, onCheckedChange = actions.onDynamicColor)
                }

                Section("Files")
                Text(
                    model.folderName?.let { "Read and write file blocks use: $it" }
                        ?: "No folder picked. File blocks use AutoFlow's own folder, which other apps can't easily see.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = actions.onPickFolder) { Text(if (model.folderName == null) "Pick a folder" else "Change folder") }
                    if (model.folderName != null) TextButton(onClick = actions.onClearFolder) { Text("Forget folder") }
                }

                Section("Battery")
                Text(
                    if (model.batteryUnrestricted) "AutoFlow is allowed to run in the background without limits."
                    else "Samsung may pause background apps. If flows stop on their own, set AutoFlow's battery use to Unrestricted.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!model.batteryUnrestricted) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = actions.onBatterySettings) { Text("Open battery settings") }
                }

                Section("Permissions")
                Text(
                    "AutoFlow asks for these only when a flow uses a block that needs them. You can also allow them here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                for (p in Permission.entries.filter { it != Permission.FOLDER }) {
                    PermissionRow(p, p in model.granted) { actions.onGrant(p) }
                }

                Section("About")
                Text("AutoFlow ${model.version}", fontWeight = FontWeight.Medium)
                Text(
                    "Flowchart automations, no root needed. Flows run in a foreground service only while one is active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(10.dp))
}
