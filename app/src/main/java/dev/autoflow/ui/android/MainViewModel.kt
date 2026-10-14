package dev.autoflow.ui.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.autoflow.AutoFlowApp
import dev.autoflow.core.blocks.Permission
import dev.autoflow.core.engine.LogEntry
import dev.autoflow.core.engine.RunStatus
import dev.autoflow.core.engine.Validator
import dev.autoflow.core.model.FlowDoc
import dev.autoflow.core.model.FlowJson
import dev.autoflow.core.model.Ids
import dev.autoflow.core.templates.Template
import dev.autoflow.core.templates.Templates
import dev.autoflow.data.StoredFlow
import dev.autoflow.runtime.Perms
import dev.autoflow.ui.common.AppInfo
import dev.autoflow.ui.editor.EditorState
import dev.autoflow.ui.home.FlowSummary
import dev.autoflow.ui.settings.SettingsModel
import dev.autoflow.ui.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Editor(val flowId: String) : Screen
    data class Logs(val flowId: String) : Screen
    data object Settings : Screen
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AutoFlowApp
    private val repo = app.repo
    private val prefs = app.prefs
    val controller = app.controller

    // ---------- navigation ----------
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun navigate(to: Screen) {
        if (screen != to) stack.add(to)
    }

    fun back() {
        if (stack.size <= 1) return
        if (screen is Screen.Editor) closeEditor()
        stack.removeAt(stack.lastIndex)
    }

    // ---------- data ----------
    val flows: StateFlow<List<StoredFlow>> = repo.flows.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val statuses: StateFlow<Map<String, RunStatus>> get() = controller.statuses

    val summaries: StateFlow<List<FlowSummary>> = combine(repo.flows, controller.statuses) { list, st ->
        list.map { f -> FlowSummary(f.doc.id, f.doc.name, f.doc.blocks.map { it.type }, f.startAtBoot, st[f.doc.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun flowName(id: String): String = flows.value.firstOrNull { it.doc.id == id }?.doc?.name
        ?: editor?.takeIf { it.doc.id == id }?.doc?.name ?: "Flow"

    fun logs(id: String): Flow<List<LogEntry>> = repo.logs(id)
    fun clearLogs(id: String) = viewModelScope.launch { repo.clearLogs(id) }

    init {
        // First launch: a couple of ready-made flows so the home screen shows what's possible.
        if (!prefs.seededTemplates) {
            prefs.seededTemplates = true
            viewModelScope.launch {
                Templates.all.take(3).forEach { repo.save(it.build()) }
            }
        }
    }

    // ---------- editor ----------
    var editor by mutableStateOf<EditorState?>(null)
        private set

    fun openEditor(id: String) = viewModelScope.launch {
        saveEditorNow()
        val doc = repo.load(id) ?: return@launch
        startEditing(doc)
    }

    fun create(template: Template?) = viewModelScope.launch {
        val doc = template?.build() ?: Templates.blank()
        val named = doc.copy(name = uniqueName(doc.name))
        repo.save(named)
        startEditing(named)
    }

    private var savedCount = 0

    private fun startEditing(doc: FlowDoc) {
        editor = EditorState(doc)
        savedCount = 0
        navigate(Screen.Editor(doc.id))
    }

    /** Autosave, called a moment after the user stops editing, and when leaving the editor or the app. */
    fun saveEditor() = viewModelScope.launch { saveEditorNow() }

    private suspend fun saveEditorNow() {
        val state = editor ?: return
        val count = state.editCount
        if (count > savedCount) {
            savedCount = count
            repo.save(state.doc)
        }
    }

    private fun closeEditor() {
        val state = editor ?: return
        viewModelScope.launch {
            saveEditorNow()
            if (editor === state) editor = null
        }
    }

    // ---------- running ----------
    /** Set when a flow needs permissions first; the screen shows the permission dialog. */
    var pendingRun by mutableStateOf<Pair<String, List<Permission>>?>(null)
        private set

    fun requestRun(id: String) = viewModelScope.launch {
        saveEditorNow()
        val doc = repo.load(id) ?: return@launch
        val needed = (Validator.permissions(doc) + Permission.NOTIFICATIONS).distinct()
        refreshPermissions()
        if (needed.all { it in granted }) controller.start(id) else pendingRun = id to needed
    }

    fun confirmRun() {
        val (id, _) = pendingRun ?: return
        pendingRun = null
        controller.start(id)
    }

    fun dismissRun() {
        pendingRun = null
    }

    fun stop(id: String) = controller.stop(id)
    fun pause(id: String) = controller.pause(id)
    fun resume(id: String) = controller.resume(id)
    fun stopAll() = controller.stopAll()

    // ---------- flow management ----------
    fun duplicate(id: String) = viewModelScope.launch {
        val doc = repo.load(id) ?: return@launch
        repo.save(doc.copy(id = Ids.next(), name = uniqueName(doc.name + " copy")))
    }

    fun delete(id: String) = viewModelScope.launch {
        controller.forget(id)
        repo.delete(id)
    }

    fun rename(id: String, name: String) = viewModelScope.launch {
        val doc = repo.load(id) ?: return@launch
        repo.save(doc.copy(name = name.trim().ifEmpty { doc.name }))
    }

    fun setStartAtBoot(id: String, value: Boolean) = viewModelScope.launch { repo.setStartAtBoot(id, value) }

    private fun uniqueName(base: String): String {
        val names = flows.value.map { it.doc.name }.toSet()
        if (base !in names) return base
        var i = 2
        while ("$base $i" in names) i++
        return "$base $i"
    }

    // ---------- import / export ----------
    /** Flow ids waiting for the user to pick where to save them. Empty list means all flows. */
    var pendingExport: List<String> = emptyList()

    fun exportFileName(ids: List<String>): String {
        val name = if (ids.size == 1) flowName(ids[0]) else "autoflow-flows"
        return name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "flow" } + ".autoflow.json"
    }

    fun export(uri: Uri, onDone: (String) -> Unit) = viewModelScope.launch {
        saveEditorNow()
        val docs = if (pendingExport.isEmpty()) flows.value.map { it.doc } else pendingExport.mapNotNull { repo.load(it) }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(FlowJson.exportFile(docs)) }
            }
        }
        onDone(if (result.isSuccess) "Exported ${docs.size} flow${if (docs.size == 1) "" else "s"}" else "Couldn't save the file")
    }

    fun import(uri: Uri, onDone: (String) -> Unit) = viewModelScope.launch {
        val result = runCatching {
            val text = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            }
            FlowJson.importFile(text).onEach { repo.save(it.copy(name = uniqueName(it.name))) }
        }
        onDone(
            result.fold(
                { if (it.isEmpty()) "No flows in that file" else "Imported ${it.size} flow${if (it.size == 1) "" else "s"}" },
                { "That file isn't an AutoFlow export" },
            )
        )
    }

    // ---------- permissions & settings ----------
    var granted by mutableStateOf(emptySet<Permission>())
        private set
    var batteryUnrestricted by mutableStateOf(false)
        private set
    var themeMode by mutableStateOf(runCatching { ThemeMode.valueOf(prefs.themeMode) }.getOrDefault(ThemeMode.SYSTEM))
        private set
    var dynamicColor by mutableStateOf(prefs.dynamicColor)
        private set
    var folderName by mutableStateOf<String?>(null)
        private set

    fun refreshPermissions() {
        granted = Perms.granted(app, prefs)
        batteryUnrestricted = Perms.ignoresBatteryOptimizations(app)
        folderName = prefs.folderUri?.takeIf { Permission.FOLDER in granted }?.let { uri ->
            runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifEmpty { "Storage" } }.getOrNull()
        }
    }

    fun setTheme(mode: ThemeMode) {
        themeMode = mode
        prefs.themeMode = mode.name
    }

    fun setDynamic(value: Boolean) {
        dynamicColor = value
        prefs.dynamicColor = value
    }

    fun setFolder(uri: Uri?) {
        prefs.folderUri?.let { old ->
            if (old != uri) runCatching {
                app.contentResolver.releasePersistableUriPermission(
                    old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        if (uri != null) {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs.folderUri = uri
        refreshPermissions()
    }

    fun settingsModel(version: String) = SettingsModel(
        themeMode = themeMode,
        dynamicColor = dynamicColor,
        dynamicSupported = Build.VERSION.SDK_INT >= 31,
        folderName = folderName,
        granted = granted,
        batteryUnrestricted = batteryUnrestricted,
        version = version,
    )

    // ---------- installed apps, for the app pickers ----------
    var apps by mutableStateOf(emptyList<AppInfo>())
        private set
    private var appsLoaded = false

    fun loadApps() {
        if (appsLoaded) return
        appsLoaded = true
        viewModelScope.launch {
            apps = withContext(Dispatchers.IO) {
                val pm = app.packageManager
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(launcher, 0)
                    .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                    .filter { it.packageName != app.packageName }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
            }
        }
    }
}
