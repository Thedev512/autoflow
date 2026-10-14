// Signature stubs mirroring androidx.activity 1.9.x. Bodies are never run.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.activity

import android.content.res.Resources
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.ViewModel

open class ComponentActivity : android.app.Activity() {
    fun <I, O> registerForActivityResult(
        contract: ActivityResultContract<I, O>,
        callback: ActivityResultCallback<O>,
    ): ActivityResultLauncher<I> = TODO()
}

class SystemBarStyle private constructor() {
    companion object {
        fun auto(lightScrim: Int, darkScrim: Int, detectDarkMode: (Resources) -> Boolean = { false }): SystemBarStyle = TODO()
        fun dark(scrim: Int): SystemBarStyle = TODO()
        fun light(scrim: Int, darkScrim: Int): SystemBarStyle = TODO()
    }
}

fun ComponentActivity.enableEdgeToEdge(
    statusBarStyle: SystemBarStyle = SystemBarStyle.auto(0, 0),
    navigationBarStyle: SystemBarStyle = SystemBarStyle.auto(0, 0),
) {}

inline fun <reified VM : ViewModel> ComponentActivity.viewModels(): Lazy<VM> = TODO()
