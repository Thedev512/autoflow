// Mirrors androidx.lifecycle 2.8.x.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.lifecycle

import android.app.Application
import kotlinx.coroutines.CoroutineScope

abstract class ViewModel {
    protected open fun onCleared() {}
}

open class AndroidViewModel(private val application: Application) : ViewModel() {
    @Suppress("UNCHECKED_CAST")
    open fun <T : Application> getApplication(): T = application as T
}

val ViewModel.viewModelScope: CoroutineScope get() = TODO()
