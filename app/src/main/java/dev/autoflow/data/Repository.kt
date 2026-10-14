package dev.autoflow.data

import dev.autoflow.core.engine.LogEntry
import dev.autoflow.core.engine.LogLevel
import dev.autoflow.core.engine.LogSink
import dev.autoflow.core.model.FlowDoc
import dev.autoflow.core.model.FlowJson
import dev.autoflow.runtime.FlowSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class StoredFlow(val doc: FlowDoc, val startAtBoot: Boolean, val updatedAt: Long)

class FlowRepository(private val db: AppDatabase) : FlowSource {
    val flows: Flow<List<StoredFlow>> = db.flows().observeAll().map { rows -> rows.mapNotNull { it.toStored() } }

    override suspend fun load(id: String): FlowDoc? = db.flows().get(id)?.toStored()?.doc

    override suspend fun bootFlows(): List<FlowDoc> = db.flows().bootFlows().mapNotNull { it.toStored()?.doc }

    suspend fun save(doc: FlowDoc) {
        val existing = db.flows().get(doc.id)
        db.flows().upsert(
            FlowEntity(doc.id, doc.name, FlowJson.encode(doc), System.currentTimeMillis(), existing?.startAtBoot ?: false)
        )
    }

    suspend fun setStartAtBoot(id: String, value: Boolean) = db.flows().setStartAtBoot(id, value)

    suspend fun delete(id: String) {
        db.flows().delete(id)
        db.logs().clear(id)
    }

    fun logs(flowId: String): Flow<List<LogEntry>> = db.logs().observe(flowId, 1000).map { rows -> rows.map { it.toEntry() } }

    suspend fun clearLogs(flowId: String) = db.logs().clear(flowId)

    private fun FlowEntity.toStored(): StoredFlow? =
        runCatching { StoredFlow(FlowJson.decode(json), startAtBoot, updatedAt) }.getOrNull()

    private fun LogEntity.toEntry() = LogEntry(
        flowId, runId, time, LogLevel.entries.getOrElse(level) { LogLevel.INFO }, blockId, blockTitle, message, durationMs,
    )
}

/**
 * Collects engine logs and writes them to Room in small batches, so busy flows don't hit
 * the database once per block. Keeps the newest [KEEP] rows per flow.
 */
class RoomLogSink(private val db: AppDatabase, scope: CoroutineScope) : LogSink {
    private val channel = Channel<LogEntry>(capacity = 2_000, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            val batch = ArrayList<LogEntity>()
            var writes = 0
            while (true) {
                val first = channel.receive()
                batch += first.toEntity()
                delay(BATCH_WINDOW_MS)
                while (true) {
                    val next = channel.tryReceive().getOrNull() ?: break
                    batch += next.toEntity()
                }
                runCatching {
                    db.logs().insertAll(batch.toList())
                    if (++writes % 20 == 0) batch.map { it.flowId }.distinct().forEach { db.logs().prune(it, KEEP) }
                }
                batch.clear()
            }
        }
    }

    override fun log(entry: LogEntry) {
        channel.trySend(entry)
    }

    private fun LogEntry.toEntity() = LogEntity(
        flowId = flowId, runId = runId, time = time, level = level.ordinal, blockId = blockId,
        blockTitle = blockTitle, message = message.take(2_000), durationMs = durationMs,
    )

    companion object {
        const val KEEP = 1_000
        private const val BATCH_WINDOW_MS = 400L
    }
}
