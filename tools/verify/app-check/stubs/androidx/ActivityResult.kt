@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.activity.result

fun interface ActivityResultCallback<O> {
    fun onActivityResult(result: O)
}

abstract class ActivityResultLauncher<I> {
    open fun launch(input: I) {}
    abstract fun unregister()
}
