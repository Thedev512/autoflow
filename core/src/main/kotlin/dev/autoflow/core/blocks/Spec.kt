package dev.autoflow.core.blocks

enum class Category(val title: String) {
    TRIGGER("Triggers"),
    ACTION("Actions"),
    LOGIC("Logic"),
}

enum class ParamKind {
    /** Plain text; `{expression}` parts are filled in when the block runs. */
    TEXT,
    MULTILINE,
    /** A number, or an expression that gives one. */
    NUMBER,
    /** An expression such as `battery < 20 && !charging`. */
    EXPRESSION,
    BOOLEAN,
    CHOICE,
    /** A variable name to read or write. */
    VARIABLE,
    /** A package name, picked from installed apps. The picker also stores `<key>Name`. */
    APP,
    /** HH:mm */
    TIME,
    /** Days of week as digits, 1 = Monday ... 7 = Sunday. */
    DAYS,
}

data class Choice(val value: String, val label: String)

data class ParamSpec(
    val key: String,
    val label: String,
    val kind: ParamKind,
    val default: String = "",
    val choices: List<Choice> = emptyList(),
    val hint: String = "",
    /** Only shown when another param has one of these values. */
    val showWhen: Pair<String, Set<String>>? = null,
) {
    fun isVisible(values: Map<String, String>, specParams: List<ParamSpec>): Boolean {
        val (other, allowed) = showWhen ?: return true
        val v = values[other] ?: specParams.firstOrNull { it.key == other }?.default
        return v in allowed
    }
}

data class PortSpec(val id: String, val label: String)

object Ports {
    val NEXT = PortSpec("next", "")
    val YES = PortSpec("yes", "Yes")
    val NO = PortSpec("no", "No")
    val LOOP = PortSpec("loop", "Repeat")
    val DONE = PortSpec("done", "Done")
    val OK = PortSpec("ok", "OK")
    val FAIL = PortSpec("fail", "Fail")
    val CANCEL = PortSpec("cancel", "Cancel")
}

/** Android permissions a block may need, described in plain words so the app can ask only when needed. */
enum class Permission(val title: String, val reason: String) {
    NOTIFICATIONS("Notifications", "Lets flows show notifications."),
    BLUETOOTH("Nearby devices", "Lets flows notice Bluetooth turning on or off and devices connecting."),
    OVERLAY(
        "Display over other apps",
        "Android only lets apps open other apps, links, share sheets and dialogs from the background with this.",
    ),
    WRITE_SETTINGS("Modify system settings", "Needed to change screen brightness."),
    NOTIFICATION_LISTENER("Notification access", "Lets flows react to notifications from other apps."),
    USAGE_ACCESS("Usage access", "Lets flows notice when an app is opened."),
    EXACT_ALARM(
        "Alarms & reminders",
        "Lets schedules fire on time, and lets AutoFlow fully sleep between them to save battery.",
    ),
    FOLDER("Files folder", "Pick a folder for the read and write file blocks. Without it, files go in AutoFlow's own folder."),
}

data class BlockSpec(
    val type: String,
    val category: Category,
    val title: String,
    val description: String,
    val icon: String,
    val params: List<ParamSpec> = emptyList(),
    val outputs: List<PortSpec> = listOf(Ports.NEXT),
    val permissions: Set<Permission> = emptySet(),
    val hasInput: Boolean = true,
    /** Waits for something to happen (shown as "waiting for ..." while it does). */
    val waits: Boolean = false,
    val summary: (Map<String, String>) -> String = { "" },
    /** Permissions that depend on the chosen options. Defaults to [permissions]. */
    val permissionsFor: ((Map<String, String>) -> Set<Permission>)? = null,
) {
    fun defaults(): Map<String, String> = params.associate { it.key to it.default }

    /** Params merged over defaults, so missing keys still have a value. */
    fun resolve(values: Map<String, String>): Map<String, String> = defaults() + values

    fun requiredPermissions(values: Map<String, String>): Set<Permission> =
        permissionsFor?.invoke(resolve(values)) ?: permissions

    fun summaryOf(values: Map<String, String>): String = runCatching { summary(resolve(values)) }.getOrDefault("")
}
