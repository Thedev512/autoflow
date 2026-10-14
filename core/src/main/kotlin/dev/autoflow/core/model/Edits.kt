package dev.autoflow.core.model

import kotlinx.serialization.Serializable

/** Pure editing operations. Every edit returns a new [FlowDoc], which keeps undo/redo trivial. */

fun FlowDoc.addBlock(type: String, x: Float, y: Float, params: Map<String, String> = emptyMap()): Pair<FlowDoc, String> {
    val id = Ids.next()
    return copy(blocks = blocks + Block(id, type, x, y, params)) to id
}

fun FlowDoc.moveItems(ids: Set<String>, dx: Float, dy: Float): FlowDoc = copy(
    blocks = blocks.map { if (it.id in ids) it.copy(x = it.x + dx, y = it.y + dy) else it },
    comments = comments.map { if (it.id in ids) it.copy(x = it.x + dx, y = it.y + dy) else it },
)

fun FlowDoc.removeItems(ids: Set<String>): FlowDoc = copy(
    blocks = blocks.filterNot { it.id in ids },
    links = links.filterNot { it.from in ids || it.to in ids },
    comments = comments.filterNot { it.id in ids },
)

/** One arrow per output port: connecting a port replaces whatever it pointed at before. */
fun FlowDoc.connect(from: String, port: String, to: String): FlowDoc {
    if (block(from) == null || block(to) == null) return this
    return copy(links = links.filterNot { it.from == from && it.port == port } + Link(from, port, to))
}

fun FlowDoc.disconnect(from: String, port: String): FlowDoc =
    copy(links = links.filterNot { it.from == from && it.port == port })

fun FlowDoc.setParam(blockId: String, key: String, value: String): FlowDoc = copy(
    blocks = blocks.map { if (it.id == blockId) it.copy(params = it.params + (key to value)) else it }
)

fun FlowDoc.setLabel(blockId: String, label: String): FlowDoc = copy(
    blocks = blocks.map { if (it.id == blockId) it.copy(label = label) else it }
)

fun FlowDoc.addComment(x: Float, y: Float, text: String = "Note"): Pair<FlowDoc, String> {
    val id = Ids.next()
    return copy(comments = comments + Comment(id, x, y, text)) to id
}

fun FlowDoc.editComment(id: String, text: String): FlowDoc =
    copy(comments = comments.map { if (it.id == id) it.copy(text = text) else it })

/** A copied piece of a flow: the chosen blocks, the arrows between them, and comments. */
@Serializable
data class Snippet(
    val blocks: List<Block> = emptyList(),
    val links: List<Link> = emptyList(),
    val comments: List<Comment> = emptyList(),
) {
    val isEmpty get() = blocks.isEmpty() && comments.isEmpty()
}

fun FlowDoc.copyItems(ids: Set<String>): Snippet = Snippet(
    blocks = blocks.filter { it.id in ids },
    links = links.filter { it.from in ids && it.to in ids },
    comments = comments.filter { it.id in ids },
)

/** Pastes with fresh ids and returns the ids of the pasted items, so the editor can select them. */
fun FlowDoc.paste(snippet: Snippet, dx: Float, dy: Float): Pair<FlowDoc, Set<String>> {
    val idMap = (snippet.blocks.map { it.id } + snippet.comments.map { it.id }).associateWith { Ids.next() }
    val newBlocks = snippet.blocks.map { it.copy(id = idMap.getValue(it.id), x = it.x + dx, y = it.y + dy) }
    val newLinks = snippet.links.mapNotNull { l ->
        val f = idMap[l.from] ?: return@mapNotNull null
        val t = idMap[l.to] ?: return@mapNotNull null
        Link(f, l.port, t)
    }
    val newComments = snippet.comments.map { it.copy(id = idMap.getValue(it.id), x = it.x + dx, y = it.y + dy) }
    return copy(
        blocks = blocks + newBlocks,
        links = links + newLinks,
        comments = comments + newComments,
    ) to idMap.values.toSet()
}

data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Bounding box of everything on the canvas, given the size of a block. */
fun FlowDoc.bounds(blockW: Float, blockH: Float, commentH: Float = 90f): Bounds? {
    if (blocks.isEmpty() && comments.isEmpty()) return null
    val lefts = blocks.map { it.x } + comments.map { it.x }
    val tops = blocks.map { it.y } + comments.map { it.y }
    val rights = blocks.map { it.x + blockW } + comments.map { it.x + it.width }
    val bottoms = blocks.map { it.y + blockH } + comments.map { it.y + commentH }
    return Bounds(lefts.min(), tops.min(), rights.max(), bottoms.max())
}
