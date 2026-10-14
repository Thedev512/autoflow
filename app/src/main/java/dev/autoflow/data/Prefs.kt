package dev.autoflow.data

import android.content.Context
import android.net.Uri

/** Small settings, kept in SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("autoflow", Context.MODE_PRIVATE)

    var themeMode: String
        get() = sp.getString("theme", "SYSTEM") ?: "SYSTEM"
        set(v) = sp.edit().putString("theme", v).apply()

    var dynamicColor: Boolean
        get() = sp.getBoolean("dynamic", true)
        set(v) = sp.edit().putBoolean("dynamic", v).apply()

    /** Folder picked with the system file picker for the read/write file blocks. */
    var folderUri: Uri?
        get() = sp.getString("folder", null)?.let(Uri::parse)
        set(v) = sp.edit().putString("folder", v?.toString()).apply()

    var seededTemplates: Boolean
        get() = sp.getBoolean("seeded", false)
        set(v) = sp.edit().putBoolean("seeded", v).apply()
}
