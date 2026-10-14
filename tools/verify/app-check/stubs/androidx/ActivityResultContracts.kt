@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.activity.result.contract

import android.content.Context
import android.content.Intent
import android.net.Uri

abstract class ActivityResultContract<I, O> {
    abstract fun createIntent(context: Context, input: I): Intent
    abstract fun parseResult(resultCode: Int, intent: Intent?): O
}

class ActivityResultContracts private constructor() {
    class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun createIntent(context: Context, input: String): Intent = TODO()
        override fun parseResult(resultCode: Int, intent: Intent?): Boolean = TODO()
    }

    open class OpenDocumentTree : ActivityResultContract<Uri?, Uri?>() {
        override fun createIntent(context: Context, input: Uri?): Intent = TODO()
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = TODO()
    }

    open class CreateDocument(private val mimeType: String) : ActivityResultContract<String, Uri?>() {
        override fun createIntent(context: Context, input: String): Intent = TODO()
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = TODO()
    }

    open class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun createIntent(context: Context, input: Array<String>): Intent = TODO()
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = TODO()
    }
}
