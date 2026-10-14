package dev.autoflow.runtime

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.autoflow.core.engine.BlockException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Text files for the read/write blocks. Uses the folder the user picked with the system file picker
 * (Storage Access Framework, no storage permission needed); without one, AutoFlow's own folder.
 */
class FileAccess(private val context: Context, private val folder: () -> Uri?) {

    suspend fun write(name: String, text: String, append: Boolean): String = withContext(Dispatchers.IO) {
        checkName(name)
        val tree = folder()
        if (tree == null) {
            val f = File(fallbackDir(), name)
            if (append) f.appendText(text) else f.writeText(text)
            return@withContext f.absolutePath
        }
        val uri = findChild(tree, name) ?: createChild(tree, name)
        context.contentResolver.openOutputStream(uri, if (append) "wa" else "wt")?.use { it.write(text.toByteArray()) }
            ?: throw BlockException("Couldn't open $name for writing")
        name
    }

    suspend fun read(name: String): String = withContext(Dispatchers.IO) {
        checkName(name)
        val tree = folder()
        if (tree == null) {
            val f = File(fallbackDir(), name)
            if (!f.exists()) throw BlockException("$name doesn't exist yet")
            return@withContext f.readText()
        }
        val uri = findChild(tree, name) ?: throw BlockException("$name isn't in the chosen folder")
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw BlockException("Couldn't open $name")
    }

    private fun fallbackDir(): File = (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() }

    private fun checkName(name: String) {
        if (name.isBlank()) throw BlockException("Give the file a name")
        if ('/' in name || name == "." || name == "..") throw BlockException("File names can't contain /")
    }

    private fun findChild(tree: Uri, name: String): Uri? {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        try {
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        } catch (e: SecurityException) {
            throw BlockException("AutoFlow lost access to the files folder. Pick it again in Settings.")
        }
        return null
    }

    private fun createChild(tree: Uri, name: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        // A generic type keeps providers from tacking an extra extension onto the name.
        val mime = if (name.endsWith(".txt", ignoreCase = true)) "text/plain" else "application/octet-stream"
        return DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
            ?: throw BlockException("Couldn't create $name in the chosen folder")
    }
}
