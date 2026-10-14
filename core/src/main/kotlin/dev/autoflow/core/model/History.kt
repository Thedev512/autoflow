package dev.autoflow.core.model

/**
 * Undo/redo stacks of whole snapshots. Edits that share a [record] key back to back
 * (typing in one text field, nudging one slider) collapse into a single undo step.
 */
class History<T>(private val limit: Int = 100) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()
    private var lastKey: String? = null

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /** Call with the state *before* an edit. */
    fun record(before: T, key: String? = null) {
        if (key != null && key == lastKey) return
        undoStack.addLast(before)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        lastKey = key
    }

    fun undo(current: T): T? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        lastKey = null
        return prev
    }

    fun redo(current: T): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        lastKey = null
        return next
    }

    /** Ends the current coalescing run, so the next keyed edit becomes its own step. */
    fun seal() {
        lastKey = null
    }

    fun clear() {
        undoStack.clear(); redoStack.clear(); lastKey = null
    }
}
