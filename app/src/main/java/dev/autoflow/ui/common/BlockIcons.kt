package dev.autoflow.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Launch
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

/** Maps the icon keys used in the block catalog to Material icons. */
@Suppress("DEPRECATION") // a few icons have newer auto-mirrored twins; these look the same here
fun blockIcon(key: String): ImageVector = when (key) {
    "play" -> Icons.Rounded.PlayArrow
    "stop" -> Icons.Rounded.Stop
    "branch" -> Icons.Rounded.AltRoute
    "timer" -> Icons.Rounded.Timer
    "loop" -> Icons.Rounded.Loop
    "variable" -> Icons.Rounded.Code
    "calculate" -> Icons.Rounded.Calculate
    "text" -> Icons.Rounded.TextFields
    "casino" -> Icons.Rounded.Casino
    "chat" -> Icons.Rounded.Forum
    "log" -> Icons.Rounded.Description
    "schedule" -> Icons.Rounded.Schedule
    "battery" -> Icons.Rounded.BatteryStd
    "power" -> Icons.Rounded.Power
    "wifi" -> Icons.Rounded.Wifi
    "bluetooth" -> Icons.Rounded.Bluetooth
    "apps" -> Icons.Rounded.Apps
    "notification" -> Icons.Rounded.NotificationsActive
    "screen" -> Icons.Rounded.Smartphone
    "headphones" -> Icons.Rounded.Headphones
    "notify" -> Icons.Rounded.Notifications
    "toast" -> Icons.Rounded.ChatBubble
    "open" -> Icons.Rounded.Launch
    "flashlight" -> Icons.Rounded.FlashlightOn
    "volume" -> Icons.Rounded.VolumeUp
    "brightness" -> Icons.Rounded.BrightnessMedium
    "settings" -> Icons.Rounded.Tune
    "vibrate" -> Icons.Rounded.Vibration
    "speak" -> Icons.Rounded.RecordVoiceOver
    "clipboard" -> Icons.Rounded.ContentPaste
    "save" -> Icons.Rounded.Save
    "file" -> Icons.Rounded.FolderOpen
    "http" -> Icons.Rounded.Public
    "share" -> Icons.Rounded.Share
    else -> Icons.Rounded.Extension
}
