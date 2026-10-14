package dev.autoflow.runtime

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import dev.autoflow.core.blocks.Permission
import dev.autoflow.data.Prefs

/** Maps block permissions to Android: how to check them and how to ask. */
object Perms {
    fun isGranted(context: Context, p: Permission, prefs: Prefs): Boolean = when (p) {
        Permission.NOTIFICATIONS -> Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        Permission.BLUETOOTH -> Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        Permission.OVERLAY -> Settings.canDrawOverlays(context)
        Permission.WRITE_SETTINGS -> Settings.System.canWrite(context)
        Permission.NOTIFICATION_LISTENER -> notificationListenerEnabled(context)
        Permission.USAGE_ACCESS -> usageAccessGranted(context)
        Permission.EXACT_ALARM -> Build.VERSION.SDK_INT < 31 ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        Permission.FOLDER -> prefs.folderUri?.let { uri ->
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        } == true
    }

    fun granted(context: Context, prefs: Prefs): Set<Permission> =
        Permission.entries.filter { isGranted(context, it, prefs) }.toSet()

    /** A runtime permission to request with the system dialog, if this one is that kind. */
    fun runtimePermission(p: Permission): String? = when (p) {
        Permission.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        Permission.BLUETOOTH -> if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else null
        else -> null
    }

    /** The settings screen where the user turns on a special-access permission. */
    fun settingsIntent(context: Context, p: Permission): Intent? {
        val pkg = Uri.parse("package:" + context.packageName)
        return when (p) {
            Permission.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            Permission.WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)
            Permission.NOTIFICATION_LISTENER -> if (Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, AutoFlowNotificationListener::class.java).flattenToString(),
                )
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
            Permission.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            Permission.EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
            // Runtime permissions fall back to the app's settings page if the dialog was refused twice.
            Permission.NOTIFICATIONS, Permission.BLUETOOTH -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            Permission.FOLDER -> null
        }
    }

    fun notificationListenerEnabled(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return if (Build.VERSION.SDK_INT >= 27) {
            nm.isNotificationListenerAccessGranted(ComponentName(context, AutoFlowNotificationListener::class.java))
        } else {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.contains(context.packageName) == true
        }
    }

    fun usageAccessGranted(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun ignoresBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    @android.annotation.SuppressLint("BatteryLife")
    fun batteryIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
}
