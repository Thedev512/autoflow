// The two Android-only Compose APIs the app uses, absent from Compose Desktop.
@file:Suppress("unused", "UNUSED_PARAMETER")
@file:JvmName("ComposeAndroidStubs")

package androidx.compose.ui.platform

import android.content.Context
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

val LocalContext: ProvidableCompositionLocal<Context> = staticCompositionLocalOf { TODO() }
