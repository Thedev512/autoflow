package dev.autoflow.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** One automation: blocks on a canvas, arrows between them, and sticky-note comments. */
@Serializable
data class FlowDoc(
    val id: String,
    val name: String,
    val description: String = "",
    val blocks: List<Block> = emptyList(),
    val links: List<Link> = emptyList(),
    val comments: List<Comment> = emptyList(),
) {
    fun block(id: String): Block? = blocks.firstOrNull { it.id == id }
    fun linkFrom(blockId: String, port: String): Link? = links.firstOrNull { it.from == blockId && it.port == port }
}

/** A block placed on the canvas. Coordinates are canvas units (dp at 100% zoom), top-left corner. */
@Serializable
data class Block(
    val id: String,
    val type: String,
    val x: Float,
    val y: Float,
    val params: Map<String, String> = emptyMap(),
    val label: String = "",
)

/** An arrow from one output port of a block to the input of another block. */
@Serializable
data class Link(val from: String, val port: String, val to: String)

@Serializable
data class Comment(
    val id: String,
    val x: Float,
    val y: Float,
    val text: String,
    val width: Float = 220f,
)

/** File format for import/export: a small envelope so future versions can migrate. */
@Serializable
data class FlowFile(
    @SerialName("autoflow") val formatVersion: Int = FORMAT_VERSION,
    val flows: List<FlowDoc>,
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

object FlowJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }
    private val pretty = Json(json) { prettyPrint = true }

    fun encode(doc: FlowDoc): String = json.encodeToString(FlowDoc.serializer(), doc)
    fun decode(text: String): FlowDoc = json.decodeFromString(FlowDoc.serializer(), text)

    fun exportFile(docs: List<FlowDoc>): String = pretty.encodeToString(FlowFile.serializer(), FlowFile(flows = docs))

    /** Accepts our envelope or a bare flow object. Imported flows get fresh ids so they never overwrite. */
    fun importFile(text: String): List<FlowDoc> {
        val trimmed = text.trim()
        val docs = if (trimmed.contains("\"flows\"")) {
            val file = json.decodeFromString(FlowFile.serializer(), trimmed)
            if (file.formatVersion > FlowFile.FORMAT_VERSION) {
                throw IllegalArgumentException("This file was made by a newer version of AutoFlow")
            }
            file.flows
        } else {
            listOf(decode(trimmed))
        }
        if (docs.isEmpty()) throw IllegalArgumentException("The file has no flows in it")
        return docs.map { it.copy(id = Ids.next()) }
    }
}

object Ids {
    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
    fun next(length: Int = 10): String = buildString {
        repeat(length) { append(ALPHABET[Random.nextInt(ALPHABET.length)]) }
    }
}
