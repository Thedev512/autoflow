package dev.autoflow.ui.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.autoflow.core.blocks.Permission
import dev.autoflow.core.templates.Templates
import dev.autoflow.runtime.Perms
import dev.autoflow.ui.common.PermissionDialog
import dev.autoflow.ui.editor.EditorActions
import dev.autoflow.ui.editor.EditorScreen
import dev.autoflow.ui.editor.InspectorHost
import dev.autoflow.ui.home.HomeActions
import dev.autoflow.ui.home.HomeScreen
import dev.autoflow.ui.logs.LogsScreen
import dev.autoflow.ui.settings.SettingsActions
import dev.autoflow.ui.settings.SettingsScreen
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        // Refused for good: Android stops showing the dialog, so send the user to the app's settings page.
        if (!ok && lastRequested != null && !shouldShowRequestPermissionRationale(lastRequested!!)) {
            lastPermission?.let { p -> Perms.settingsIntent(this, p)?.let(::open) }
        }
        vm.refreshPermissions()
    }
    private var lastRequested: String? = null
    private var lastPermission: Permission? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { vm.setFolder(uri) }.onFailure { toast("Couldn't use that folder") }
    }

    private val exportTo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri, ::toast)
    }

    private val importFrom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri, ::toast)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(0, 0))
        super.onCreate(savedInstanceState)
        handleOpenIntent(intent)
        setContent {
            AppTheme(vm.themeMode, vm.dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    /** Opening an exported flow file from a file manager or a chat app imports it. */
    private fun handleOpenIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { vm.import(it, ::toast) }
    }

    override fun onResume() {
        super.onResume()
        // The user may come back from a settings screen having granted something.
        vm.refreshPermissions()
    }

    override fun onStop() {
        vm.saveEditor()
        super.onStop()
    }

    fun grant(p: Permission) {
        when {
            p == Permission.FOLDER -> pickFolder.launch(null)
            Perms.runtimePermission(p) != null -> {
                lastPermission = p
                lastRequested = Perms.runtimePermission(p)
                requestPermission.launch(lastRequested!!)
            }
            else -> Perms.settingsIntent(this, p)?.let(::open)
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("This tablet doesn't have that settings screen")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun export(ids: List<String>) {
        vm.pendingExport = ids
        exportTo.launch(vm.exportFileName(ids))
    }

    private val version: String by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
    }

    @OptIn(FlowPreview::class)
    @Composable
    private fun Root() {
        val statuses by vm.statuses.collectAsStateWithLifecycle()
        val screen = vm.screen
        BackHandler(enabled = vm.canGoBack) { vm.back() }

        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.97f)) togetherWith fadeOut(tween(120))
            },
            contentKey = { it::class },
            label = "screen",
        ) { s ->
            when (s) {
                Screen.Home -> {
                    val flows by vm.summaries.collectAsStateWithLifecycle()
                    val actions = remember {
                        HomeActions(
                            onOpen = { vm.openEditor(it) },
                            onRun = { vm.requestRun(it) },
                            onStop = vm::stop,
                            onPause = vm::pause,
                            onResume = vm::resume,
                            onLogs = { vm.navigate(Screen.Logs(it)) },
                            onCreate = { vm.create(it) },
                            onImport = { importFrom.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                            onExport = { export(listOf(it)) },
                            onExportAll = { export(emptyList()) },
                            onDuplicate = { vm.duplicate(it) },
                            onDelete = { vm.delete(it) },
                            onRename = { id, name -> vm.rename(id, name) },
                            onToggleBoot = { id, on -> vm.setStartAtBoot(id, on) },
                            onSettings = { vm.navigate(Screen.Settings) },
                            onStopAll = vm::stopAll,
                        )
                    }
                    HomeScreen(flows, Templates.all, actions)
                }

                is Screen.Editor -> {
                    val state = vm.editor
                    if (state != null && state.doc.id == s.flowId) {
                        // Autosave shortly after the user stops editing.
                        LaunchedEffect(state) {
                            snapshotFlow { state.editCount }.drop(1).debounce(800).collect { vm.saveEditor() }
                        }
                        val id = s.flowId
                        EditorScreen(
                            state = state,
                            status = statuses[id],
                            host = InspectorHost(vm.apps, vm::loadApps, vm.granted, ::grant),
                            actions = remember(id) {
                                EditorActions(
                                    onBack = vm::back,
                                    onRun = { vm.requestRun(id) },
                                    onStop = { vm.stop(id) },
                                    onPause = { vm.pause(id) },
                                    onResume = { vm.resume(id) },
                                    onLogs = { vm.navigate(Screen.Logs(id)) },
                                    onExport = { export(listOf(id)) },
                                )
                            },
                        )
                    }
                }

                is Screen.Logs -> {
                    val entries by remember(s.flowId) { vm.logs(s.flowId) }.collectAsState(initial = emptyList())
                    LogsScreen(
                        flowName = vm.flowName(s.flowId),
                        status = statuses[s.flowId],
                        entries = entries,
                        onBack = vm::back,
                        onClear = { vm.clearLogs(s.flowId) },
                    )
                }

                Screen.Settings -> SettingsScreen(
                    model = vm.settingsModel(version),
                    actions = remember {
                        SettingsActions(
                            onBack = vm::back,
                            onThemeMode = vm::setTheme,
                            onDynamicColor = vm::setDynamic,
                            onPickFolder = { pickFolder.launch(null) },
                            onClearFolder = { vm.setFolder(null) },
                            onGrant = ::grant,
                            onBatterySettings = { open(Perms.batteryIntent(this@MainActivity)) },
                        )
                    },
                )
            }
        }

        vm.pendingRun?.let { (_, needed) ->
            PermissionDialog(
                needed = needed,
                granted = vm.granted,
                onGrant = ::grant,
                onStart = vm::confirmRun,
                onDismiss = vm::dismissRun,
            )
        }
    }
}
